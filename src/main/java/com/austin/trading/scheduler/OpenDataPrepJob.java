package com.austin.trading.scheduler;

import com.austin.trading.client.TwseMisClient;
import com.austin.trading.client.dto.StockQuote;
import com.austin.trading.dto.request.AiTaskCandidateRef;
import com.austin.trading.dto.response.CandidateResponse;
import com.austin.trading.entity.CandidateStockEntity;
import com.austin.trading.repository.CandidateStockRepository;
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
import java.util.stream.Collectors;

/**
 * 09:01 開盤報價準備排程。
 * <p>
 * 在市場開盤約 1 分鐘後，擷取今日候選股的開盤價，
 * 更新 candidate_stock.payload_json（補入 open_price、gap 資訊），
 * 供 09:30 最終決策時比對現價與開盤的關係。
 * </p>
 */
@Component
@ConditionalOnProperty(prefix = "trading.scheduler.open-data-prep", name = "enabled", havingValue = "true")
public class OpenDataPrepJob {

    private static final Logger log = LoggerFactory.getLogger(OpenDataPrepJob.class);

    private final TwseMisClient            twseMisClient;
    private final CandidateScanService     candidateScanService;
    private final CandidateStockRepository candidateStockRepository;
    private final SchedulerLogService      schedulerLogService;
    private final DailyOrchestrationService orchestrationService;
    private final AiTaskService            aiTaskService;
    private final ClaudeCodeRequestWriterService requestWriterService;

    public OpenDataPrepJob(
            TwseMisClient twseMisClient,
            CandidateScanService candidateScanService,
            CandidateStockRepository candidateStockRepository,
            SchedulerLogService schedulerLogService,
            DailyOrchestrationService orchestrationService,
            AiTaskService aiTaskService,
            ClaudeCodeRequestWriterService requestWriterService
    ) {
        this.twseMisClient           = twseMisClient;
        this.candidateScanService    = candidateScanService;
        this.candidateStockRepository = candidateStockRepository;
        this.schedulerLogService     = schedulerLogService;
        this.orchestrationService    = orchestrationService;
        this.aiTaskService           = aiTaskService;
        this.requestWriterService    = requestWriterService;
    }

    @Scheduled(cron = "${trading.scheduler.open-data-prep-cron:0 1 9 * * MON-FRI}",
               zone  = "${trading.timezone:Asia/Taipei}")
    public void run() {
        LocalDateTime triggerTime = LocalDateTime.now();
        String jobName = "OpenDataPrepJob";
        LocalDate today = LocalDate.now();
        OrchestrationStep step = OrchestrationStep.OPEN_DATA_PREP;

        if (!orchestrationService.markRunning(today, step)) {
            log.info("[{}] Step {} already DONE today, skip.", jobName, step);
            return;
        }
        try {
            // 今日候選（最多 20 檔：超強勢 5 + 中短線 5 + 額外備選）
            List<CandidateResponse> candidates = candidateScanService.getCurrentCandidates(20);
            if (candidates.isEmpty()) {
                log.info("[OpenDataPrepJob] No candidates for {}, skip.", today);
                schedulerLogService.success(jobName, triggerTime, LocalDateTime.now(), "No candidates");
                orchestrationService.markDone(today, step, "No candidates");
                return;
            }

            // 擷取開盤報價
            List<String> symbols = candidates.stream()
                    .map(CandidateResponse::symbol)
                    .collect(Collectors.toList());

            List<StockQuote> quotes = twseMisClient.getQuotesWithOtcFallback(symbols);
            Map<String, StockQuote> quoteMap = quotes.stream()
                    .collect(Collectors.toMap(StockQuote::symbol, q -> q, (a, b) -> a));

            // 更新 payload_json（補入開盤價）
            List<CandidateStockEntity> entities =
                    candidateStockRepository.findByTradingDateOrderByScoreDesc(
                            today, PageRequest.of(0, 20));

            int updated = 0;
            for (CandidateStockEntity entity : entities) {
                StockQuote q = quoteMap.get(entity.getSymbol());
                if (q == null) continue;
                entity.setPayloadJson(mergeOpenPrice(entity.getPayloadJson(), q));
                candidateStockRepository.save(entity);
                updated++;
            }

            // v2.1：建 OPENING ai_task（供 Claude 09:20 / Codex 09:28 接手，09:30 FinalDecision 讀取）
            Long openingTaskId = null;
            try {
                List<AiTaskCandidateRef> refs = candidates.stream()
                        .map(c -> new AiTaskCandidateRef(
                                c.symbol(), c.stockName(), c.themeTag(), c.javaStructureScore()))
                        .collect(Collectors.toList());
                var task = aiTaskService.createTask(
                        today, "OPENING", null, refs,
                        "09:01 開盤候選（共 " + refs.size() + " 檔），等 Claude 09:20 / Codex 09:28 接手",
                        "D:/ai/stock/claude-research-request.json"
                );
                openingTaskId = task.getId();
            } catch (Exception e) {
                log.warn("[OpenDataPrepJob] createTask 失敗: {}", e.getMessage());
            }

            // v2.5：必寫 request.json 更新為 OPENING 候選，避免 Claude 讀到 08:10 PREMARKET 殘留內容
            try {
                String context = String.format("{\"source\":\"open_data_prep\",\"quotes\":%d,\"updated\":%d}",
                        quotes.size(), updated);
                requestWriterService.writeRequest(openingTaskId, "OPENING", today, symbols, context);
            } catch (Exception e) {
                log.warn("[OpenDataPrepJob] writeRequest 失敗: {}", e.getMessage());
            }

            String msg = String.format("symbols=%d quotes=%d updated=%d",
                    symbols.size(), quotes.size(), updated);
            log.info("[OpenDataPrepJob] {}", msg);
            schedulerLogService.success(jobName, triggerTime, LocalDateTime.now(), msg);
            orchestrationService.markDone(today, step, msg);

        } catch (Exception e) {
            orchestrationService.markFailed(today, step, e.getMessage());
            schedulerLogService.failed(jobName, triggerTime, LocalDateTime.now(), e.getMessage());
            throw e;
        }
    }

    // ── 私有方法 ─────────────────────────────────────────────────────────────────

    private String mergeOpenPrice(String existingJson, StockQuote q) {
        // gap = (open - prevClose) / prevClose * 100
        String gapPct = "null";
        if (q.open() != null && q.prevClose() != null && q.prevClose() != 0) {
            double gap = (q.open() - q.prevClose()) / q.prevClose() * 100.0;
            gapPct = String.format("%.2f", gap);
        }
        String openData = String.format(
                "\"open_price\":%s,\"prev_close\":%s,\"gap_pct\":%s",
                q.open()     != null ? String.valueOf(q.open())     : "null",
                q.prevClose() != null ? String.valueOf(q.prevClose()) : "null",
                gapPct
        );

        if (existingJson == null || existingJson.isBlank() || !existingJson.trim().startsWith("{")) {
            return "{" + openData + "}";
        }
        String trimmed = existingJson.trim();
        if (trimmed.equals("{}")) return "{" + openData + "}";
        return "{" + openData + "," + trimmed.substring(1);
    }
}
