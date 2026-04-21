package com.austin.trading.scheduler;

import com.austin.trading.client.MarketBreadthClient;
import com.austin.trading.client.TwseMisClient;
import com.austin.trading.client.dto.MarketBreadth;
import com.austin.trading.client.dto.StockQuote;
import com.austin.trading.dto.request.AiTaskCandidateRef;
import com.austin.trading.dto.response.CandidateResponse;
import com.austin.trading.entity.CandidateStockEntity;
import com.austin.trading.entity.MarketSnapshotEntity;
import com.austin.trading.repository.CandidateStockRepository;
import com.austin.trading.repository.MarketSnapshotRepository;
import com.austin.trading.service.AiTaskService;
import com.austin.trading.service.CandidateScanService;
import com.austin.trading.service.ClaudeCodeRequestWriterService;
import com.austin.trading.service.DailyOrchestrationService;
import com.austin.trading.service.OrchestrationStep;
import com.austin.trading.service.SchedulerLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 15:05 收盤後資料準備排程。
 * <p>
 * 在 PostmarketAnalysis1530Job（15:30）之前執行：
 * 1. 抓取今日大盤漲跌家數與指數
 * 2. 抓取今日候選股收盤報價
 * 3. 更新 candidate_stock payload_json 加入收盤報價
 * 4. 儲存 market_snapshot（收盤版）
 * </p>
 */
@Component
@ConditionalOnProperty(prefix = "trading.scheduler.postmarket-data-prep", name = "enabled", havingValue = "true")
public class PostmarketDataPrepJob {

    private static final Logger log = LoggerFactory.getLogger(PostmarketDataPrepJob.class);

    private final MarketBreadthClient             marketBreadthClient;
    private final TwseMisClient                   twseMisClient;
    private final CandidateScanService            candidateScanService;
    private final CandidateStockRepository        candidateStockRepository;
    private final MarketSnapshotRepository        marketSnapshotRepository;
    private final SchedulerLogService             schedulerLogService;
    private final ClaudeCodeRequestWriterService  requestWriterService;
    private final DailyOrchestrationService       orchestrationService;
    private final AiTaskService                   aiTaskService;

    public PostmarketDataPrepJob(
            MarketBreadthClient marketBreadthClient,
            TwseMisClient twseMisClient,
            CandidateScanService candidateScanService,
            CandidateStockRepository candidateStockRepository,
            MarketSnapshotRepository marketSnapshotRepository,
            SchedulerLogService schedulerLogService,
            ClaudeCodeRequestWriterService requestWriterService,
            DailyOrchestrationService orchestrationService,
            AiTaskService aiTaskService
    ) {
        this.marketBreadthClient      = marketBreadthClient;
        this.twseMisClient            = twseMisClient;
        this.candidateScanService     = candidateScanService;
        this.candidateStockRepository = candidateStockRepository;
        this.marketSnapshotRepository = marketSnapshotRepository;
        this.schedulerLogService      = schedulerLogService;
        this.requestWriterService     = requestWriterService;
        this.orchestrationService     = orchestrationService;
        this.aiTaskService            = aiTaskService;
    }

    @Scheduled(cron = "${trading.scheduler.postmarket-data-prep-cron:0 5 15 * * MON-FRI}",
               zone  = "${trading.timezone:Asia/Taipei}")
    public void run() {
        LocalDateTime triggerTime = LocalDateTime.now();
        String jobName = "PostmarketDataPrepJob";
        LocalDate today = LocalDate.now();
        OrchestrationStep step = OrchestrationStep.POSTMARKET_DATA_PREP;

        if (!orchestrationService.markRunning(today, step)) {
            log.info("[{}] Step {} already DONE today, skip.", jobName, step);
            return;
        }
        try {
            // 1. 大盤漲跌家數
            Optional<MarketBreadth> breadth = marketBreadthClient.getBreadth(today);

            // 2. 今日候選股收盤報價
            List<CandidateResponse> candidates = candidateScanService.getCurrentCandidates(20);
            List<String> symbols = candidates.stream()
                    .map(CandidateResponse::symbol)
                    .collect(Collectors.toList());

            Map<String, StockQuote> quoteMap = symbols.isEmpty()
                    ? Map.of()
                    : twseMisClient.getQuotesWithOtcFallback(symbols).stream()
                            .filter(q -> q.currentPrice() != null || q.prevClose() != null)
                            .collect(Collectors.toMap(StockQuote::symbol, q -> q, (a, b) -> a));

            // 3. 更新 candidate_stock payload_json
            List<CandidateStockEntity> entities =
                    candidateStockRepository.findByTradingDateOrderByScoreDesc(
                            today, PageRequest.of(0, 20));

            int updated = 0;
            for (CandidateStockEntity entity : entities) {
                StockQuote q = quoteMap.get(entity.getSymbol());
                if (q == null) continue;
                String merged = mergePayload(entity.getPayloadJson(), q);
                entity.setPayloadJson(merged);
                candidateStockRepository.save(entity);
                updated++;
            }

            // 4. 儲存收盤市場快照
            breadth.ifPresent(b -> saveCloseSnapshot(today, b));

            // v2.5：先建 AI task 拿 taskId，再 writeRequest 帶 taskId + allowed_symbols
            Long postmarketTaskId = null;
            try {
                List<AiTaskCandidateRef> refs = candidates.stream()
                        .map(c -> new AiTaskCandidateRef(
                                c.symbol(), c.stockName(), c.themeTag(), c.javaStructureScore()))
                        .collect(Collectors.toList());
                var task = aiTaskService.createTask(
                        today, "POSTMARKET", null, refs,
                        "15:05 盤後候選（共 " + refs.size() + " 檔），等 Claude 15:20 / Codex 15:28 接手",
                        "D:/ai/stock/claude-research-request.json"
                );
                postmarketTaskId = task.getId();
            } catch (Exception e) {
                log.warn("[PostmarketDataPrepJob] createTask 失敗: {}", e.getMessage());
            }

            // 寫出研究請求給 Claude Code 排程 Agent（15:20 執行）
            String breadthContext = breadth.map(b ->
                    String.format("{\"advances\":%d,\"declines\":%d,\"index_change\":%s}",
                            b.advances(), b.declines(),
                            b.indexChangePercent() == null ? "null" : b.indexChangePercent().toString())
            ).orElse(null);
            requestWriterService.writeRequest(postmarketTaskId, "POSTMARKET", today, symbols, breadthContext);

            String msg = String.format("breadth=%s candidates=%d updated=%d",
                    breadth.map(b -> b.advances() + "/" + b.declines()).orElse("N/A"),
                    candidates.size(), updated);
            log.info("[PostmarketDataPrepJob] {}", msg);
            schedulerLogService.success(jobName, triggerTime, LocalDateTime.now(), msg);
            orchestrationService.markDone(today, step, msg);

        } catch (Exception e) {
            orchestrationService.markFailed(today, step, e.getMessage());
            schedulerLogService.failed(jobName, triggerTime, LocalDateTime.now(), e.getMessage());
            throw e;
        }
    }

    // ── 私有方法 ─────────────────────────────────────────────────────────────────

    private void saveCloseSnapshot(LocalDate date, MarketBreadth breadth) {
        String payload = String.format(
                "{\"source\":\"postmarket_data_prep\",\"advances\":%d,\"declines\":%d,\"unchanged\":%d" +
                ",\"index_value\":%s,\"index_change\":%s,\"index_change_pct\":%s}",
                breadth.advances(), breadth.declines(), breadth.unchanged(),
                breadth.indexValue()        == null ? "null" : String.valueOf(breadth.indexValue()),
                breadth.indexChange()       == null ? "null" : String.valueOf(breadth.indexChange()),
                breadth.indexChangePercent() == null ? "null" : String.valueOf(breadth.indexChangePercent())
        );

        MarketSnapshotEntity entity = new MarketSnapshotEntity();
        entity.setTradingDate(date);
        entity.setMarketGrade(null);         // 由 Codex 在 09:30 填入，這裡僅補收盤資料
        entity.setMarketPhase("CLOSE");
        entity.setDecision("WATCH");
        entity.setPayloadJson(payload);
        marketSnapshotRepository.save(entity);
    }

    private String mergePayload(String existingJson, StockQuote quote) {
        // 在原有 payload 中補入收盤報價，保持 JSON 格式
        String closeData = String.format(
                "\"close_price\":%s,\"prev_close\":%s,\"day_high\":%s,\"day_low\":%s,\"volume\":%s",
                quote.currentPrice()  != null ? String.valueOf(quote.currentPrice()) : "null",
                quote.prevClose()     != null ? String.valueOf(quote.prevClose())    : "null",
                quote.dayHigh()       != null ? String.valueOf(quote.dayHigh())      : "null",
                quote.dayLow()        != null ? String.valueOf(quote.dayLow())       : "null",
                quote.volume()        != null ? String.valueOf(quote.volume())       : "null"
        );

        if (existingJson == null || existingJson.isBlank() || !existingJson.trim().startsWith("{")) {
            return "{" + closeData + "}";
        }
        // 注入到現有 JSON 物件最前面（最簡單的合併方式）
        String trimmed = existingJson.trim();
        if (trimmed.equals("{}")) return "{" + closeData + "}";
        return "{" + closeData + "," + trimmed.substring(1);
    }
}
