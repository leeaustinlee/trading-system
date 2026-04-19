package com.austin.trading.scheduler;

import com.austin.trading.client.TaifexClient;
import com.austin.trading.client.TwseMisClient;
import com.austin.trading.client.dto.FuturesQuote;
import com.austin.trading.client.dto.StockQuote;
import com.austin.trading.dto.request.AiTaskCandidateRef;
import com.austin.trading.dto.response.CandidateResponse;
import com.austin.trading.entity.MarketSnapshotEntity;
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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 08:10 盤前資料準備排程。
 * <p>
 * 在 PremarketNotifyJob（08:30）之前執行：
 * 1. 抓取台指期近月報價
 * 2. 抓取昨日候選股即時報價（用昨收確認）
 * 3. 若有台指期資料，更新 market_snapshot（grade=PREMARKET）供 08:30 通知使用
 * </p>
 */
@Component
@ConditionalOnProperty(prefix = "trading.scheduler.premarket-data-prep", name = "enabled", havingValue = "true")
public class PremarketDataPrepJob {

    private static final Logger log = LoggerFactory.getLogger(PremarketDataPrepJob.class);

    private final TaifexClient                    taifexClient;
    private final TwseMisClient                   twseMisClient;
    private final CandidateScanService            candidateScanService;
    private final MarketSnapshotRepository        marketSnapshotRepository;
    private final SchedulerLogService             schedulerLogService;
    private final ClaudeCodeRequestWriterService  requestWriterService;
    private final DailyOrchestrationService       orchestrationService;
    private final AiTaskService                   aiTaskService;

    public PremarketDataPrepJob(
            TaifexClient taifexClient,
            TwseMisClient twseMisClient,
            CandidateScanService candidateScanService,
            MarketSnapshotRepository marketSnapshotRepository,
            SchedulerLogService schedulerLogService,
            ClaudeCodeRequestWriterService requestWriterService,
            DailyOrchestrationService orchestrationService,
            AiTaskService aiTaskService
    ) {
        this.taifexClient            = taifexClient;
        this.twseMisClient           = twseMisClient;
        this.candidateScanService    = candidateScanService;
        this.marketSnapshotRepository = marketSnapshotRepository;
        this.schedulerLogService     = schedulerLogService;
        this.requestWriterService    = requestWriterService;
        this.orchestrationService    = orchestrationService;
        this.aiTaskService           = aiTaskService;
    }

    @Scheduled(cron = "${trading.scheduler.premarket-data-prep-cron:0 10 8 * * MON-FRI}",
               zone  = "${trading.timezone:Asia/Taipei}")
    public void run() {
        LocalDateTime triggerTime = LocalDateTime.now();
        String jobName = "PremarketDataPrepJob";
        LocalDate today    = LocalDate.now();
        LocalDate yesterday = today.minusDays(1);
        OrchestrationStep step = OrchestrationStep.PREMARKET_DATA_PREP;

        if (!orchestrationService.markRunning(today, step)) {
            log.info("[{}] Step {} already DONE today, skip.", jobName, step);
            return;
        }
        try {

            // 1. 台指期近月
            Optional<FuturesQuote> txf = taifexClient.getTxfQuote(null);
            String txfSummary = txf.map(q ->
                    String.format("TX=%.0f (%+.0f)", q.currentPrice(), q.change() == null ? 0.0 : q.change())
            ).orElse("TX=N/A");

            // 2. 昨日候選股昨收報價；若昨日無（週末 / 假日），fallback 到 DB 最新有候選的交易日
            List<CandidateResponse> candidates = candidateScanService.getCandidatesByDate(yesterday, 10);
            if (candidates.isEmpty()) {
                candidates = candidateScanService.getCurrentCandidates(10);
                if (!candidates.isEmpty()) {
                    log.info("[PremarketDataPrepJob] yesterday={} 無候選，fallback 到最新交易日共 {} 檔",
                            yesterday, candidates.size());
                }
            }
            List<String> symbols = candidates.stream()
                    .map(CandidateResponse::symbol)
                    .collect(Collectors.toList());

            List<StockQuote> quotes = symbols.isEmpty()
                    ? List.of()
                    : twseMisClient.getTseQuotes(symbols);

            String quoteSummary = quotes.stream()
                    .filter(q -> q.prevClose() != null)
                    .map(q -> q.symbol() + "=" + q.prevClose())
                    .collect(Collectors.joining(","));

            // 3. 建立 PREMARKET 市場快照（grade 留空，等 09:30 決策後再補）
            String payload = buildPayload(txf.orElse(null), quoteSummary);
            saveOrUpdateSnapshot(today, payload);

            // 寫出研究請求給 Claude Code 排程 Agent（08:20 執行）
            requestWriterService.writeRequest("PREMARKET", today, symbols, buildPayload(txf.orElse(null), quoteSummary));

            // 建立 AI 任務供 Claude/Codex 認領（給 20 分鐘窗口至 08:30 Notify 前完成）
            try {
                List<AiTaskCandidateRef> refs = candidates.stream()
                        .map(c -> new AiTaskCandidateRef(
                                c.symbol(), c.stockName(), c.themeTag(), c.javaStructureScore()))
                        .collect(Collectors.toList());
                aiTaskService.createTask(
                        today, "PREMARKET", null, refs,
                        "今日盤前研究請求，共 " + refs.size() + " 檔",
                        "D:/ai/stock/claude-research-request.json"
                );
            } catch (Exception e) {
                log.warn("[PremarketDataPrepJob] createTask 失敗: {}", e.getMessage());
            }

            String msg = "txf=" + txfSummary + " candidates=" + candidates.size();
            log.info("[PremarketDataPrepJob] {}", msg);
            schedulerLogService.success(jobName, triggerTime, LocalDateTime.now(), msg);
            orchestrationService.markDone(today, step, msg);

        } catch (Exception e) {
            orchestrationService.markFailed(today, step, e.getMessage());
            schedulerLogService.failed(jobName, triggerTime, LocalDateTime.now(), e.getMessage());
            throw e;
        }
    }

    // ── 私有方法 ─────────────────────────────────────────────────────────────────

    private void saveOrUpdateSnapshot(LocalDate date, String payload) {
        MarketSnapshotEntity entity = new MarketSnapshotEntity();
        entity.setTradingDate(date);
        entity.setMarketGrade(null);        // 盤前尚未判定
        entity.setMarketPhase("PREMARKET");
        entity.setDecision("WATCH");
        entity.setPayloadJson(payload);
        marketSnapshotRepository.save(entity);
    }

    private String buildPayload(FuturesQuote txf, String candidateQuotes) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"source\":\"premarket_data_prep\"");
        if (txf != null) {
            sb.append(",\"txf_price\":").append(txf.currentPrice());
            if (txf.change() != null)
                sb.append(",\"txf_change\":").append(txf.change());
            if (txf.changePercent() != null)
                sb.append(",\"txf_change_pct\":").append(txf.changePercent());
        }
        if (candidateQuotes != null && !candidateQuotes.isBlank()) {
            sb.append(",\"candidate_prev_closes\":\"").append(candidateQuotes).append("\"");
        }
        sb.append("}");
        return sb.toString();
    }
}
