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

            // 2. 解析候選股來源（fix 2026-05-13 跨日交接 bug）：
            //    盤後流程已把 next-day universe 以 tradingDate=today 寫入 candidate_stock，
            //    因此 08:10 必須優先抓 today，再 fallback 到 latest(current)，最後才退回 yesterday。
            CandidateSourceResolution sourceResolution =
                    resolveCandidateSource(today, yesterday);
            List<CandidateResponse> candidates = sourceResolution.candidates();
            LocalDate candidateSourceDate    = sourceResolution.sourceDate();
            String candidateSourcePolicy     = sourceResolution.policy();
            log.info("[PremarketDataPrepJob] candidateSourcePolicy={} candidateSourceDate={} size={}",
                    candidateSourcePolicy, candidateSourceDate, candidates.size());

            List<String> symbols = candidates.stream()
                    .map(CandidateResponse::symbol)
                    .collect(Collectors.toList());

            List<StockQuote> quotes = symbols.isEmpty()
                    ? List.of()
                    : twseMisClient.getQuotesWithOtcFallback(symbols);

            String quoteSummary = quotes.stream()
                    .filter(q -> q.prevClose() != null)
                    .map(q -> q.symbol() + "=" + q.prevClose())
                    .collect(Collectors.joining(","));

            // 3. 建立 PREMARKET 市場快照（grade 留空，等 09:30 決策後再補）
            String payload = buildPayload(txf.orElse(null), quoteSummary,
                    candidateSourceDate, candidateSourcePolicy);
            saveOrUpdateSnapshot(today, payload);

            // v2.6：先建 AI task 拿 taskId，再 writeRequest 帶 taskId + allowed_symbols。
            // 若 task 建立失敗，直接 fail-fast，避免寫出沒有 taskId 的 request 造成 Claude/Codex 脫鉤。
            Long premarketTaskId = null;
            try {
                List<AiTaskCandidateRef> refs = candidates.stream()
                        .map(c -> new AiTaskCandidateRef(
                                c.symbol(), c.stockName(), c.themeTag(), c.javaStructureScore()))
                        .collect(Collectors.toList());
                String promptSummary = String.format(
                        "今日盤前研究請求，共 %d 檔（candidateSourceDate=%s, policy=%s）",
                        refs.size(), candidateSourceDate, candidateSourcePolicy);
                var task = aiTaskService.createTask(
                        today, "PREMARKET", null, refs,
                        promptSummary,
                        "D:/ai/stock/claude-research-request.json"
                );
                premarketTaskId = task.getId();
            } catch (Exception e) {
                log.warn("[PremarketDataPrepJob] createTask 失敗: {}", e.getMessage());
            }

            if (premarketTaskId == null) {
                throw new IllegalStateException("PREMARKET task 建立失敗，拒絕寫出無 taskId request");
            }

            // 寫出研究請求給 Claude Code 排程 Agent（08:20 執行）
            boolean requestWritten = requestWriterService.writeRequest(premarketTaskId, "PREMARKET", today, symbols,
                    buildPayload(txf.orElse(null), quoteSummary, candidateSourceDate, candidateSourcePolicy));
            if (!requestWritten) {
                throw new IllegalStateException("PREMARKET request 寫出失敗，拒絕留下只有 task、沒有 file bridge request 的狀態");
            }

            String msg = String.format(
                    "txf=%s candidates=%d candidateSourceDate=%s candidateSourcePolicy=%s",
                    txfSummary, candidates.size(), candidateSourceDate, candidateSourcePolicy);
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

    private String buildPayload(FuturesQuote txf, String candidateQuotes,
                                LocalDate candidateSourceDate, String candidateSourcePolicy) {
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
        if (candidateSourceDate != null) {
            sb.append(",\"candidateSourceDate\":\"").append(candidateSourceDate).append("\"");
        }
        if (candidateSourcePolicy != null) {
            sb.append(",\"candidateSourcePolicy\":\"").append(candidateSourcePolicy).append("\"");
        }
        sb.append("}");
        return sb.toString();
    }

    /**
     * 2026-05-13 fix：候選股來源解析（package-private 供測試使用）。
     * <p>
     * 盤後流程會把 next-day universe 以 tradingDate=today 寫入 candidate_stock，因此
     * 08:10 必須優先抓 today；若無，才退回 latest（current）；最後才退回 yesterday。
     * 順序：TODAY → CURRENT_LATEST → YESTERDAY。
     * </p>
     */
    CandidateSourceResolution resolveCandidateSource(LocalDate today, LocalDate yesterday) {
        List<CandidateResponse> todayList = candidateScanService.getCandidatesByDate(today, 10);
        if (!todayList.isEmpty()) {
            return new CandidateSourceResolution(todayList, today, "TODAY");
        }
        List<CandidateResponse> current = candidateScanService.getCurrentCandidates(10);
        if (!current.isEmpty()) {
            LocalDate latest = current.get(0).tradingDate();
            log.info("[PremarketDataPrepJob] today={} 無候選，fallback 到最新交易日 {} 共 {} 檔",
                    today, latest, current.size());
            return new CandidateSourceResolution(current, latest, "CURRENT_LATEST");
        }
        List<CandidateResponse> yest = candidateScanService.getCandidatesByDate(yesterday, 10);
        if (!yest.isEmpty()) {
            log.info("[PremarketDataPrepJob] today={} 與 current 皆無候選，fallback 到 yesterday={} 共 {} 檔",
                    today, yesterday, yest.size());
            return new CandidateSourceResolution(yest, yesterday, "YESTERDAY");
        }
        return new CandidateSourceResolution(List.of(), null, "EMPTY");
    }

    /** 候選股來源解析結果（package-private 供測試）。 */
    record CandidateSourceResolution(
            List<CandidateResponse> candidates,
            LocalDate sourceDate,
            String policy
    ) {}
}
