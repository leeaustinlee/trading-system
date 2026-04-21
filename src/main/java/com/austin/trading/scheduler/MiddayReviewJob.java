package com.austin.trading.scheduler;

import com.austin.trading.dto.request.AiTaskCandidateRef;
import com.austin.trading.dto.response.CandidateResponse;
import com.austin.trading.dto.response.MarketCurrentResponse;
import com.austin.trading.dto.response.PositionResponse;
import com.austin.trading.dto.response.TradingStateResponse;
import com.austin.trading.notify.LineTemplateService;
import com.austin.trading.service.AiTaskService;
import com.austin.trading.service.CandidateScanService;
import com.austin.trading.service.ClaudeCodeRequestWriterService;
import com.austin.trading.service.DailyOrchestrationService;
import com.austin.trading.service.MarketDataService;
import com.austin.trading.service.OrchestrationStep;
import com.austin.trading.service.PositionService;
import com.austin.trading.service.SchedulerLogService;
import com.austin.trading.service.TradingStateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@ConditionalOnProperty(prefix = "trading.scheduler.midday-review", name = "enabled", havingValue = "true")
public class MiddayReviewJob {

    private static final Logger log = LoggerFactory.getLogger(MiddayReviewJob.class);

    private final MarketDataService marketDataService;
    private final TradingStateService tradingStateService;
    private final PositionService positionService;
    private final LineTemplateService lineTemplateService;
    private final SchedulerLogService schedulerLogService;
    private final DailyOrchestrationService orchestrationService;
    private final AiTaskService aiTaskService;
    // v2.5 MIDDAY 契約
    private final CandidateScanService candidateScanService;
    private final ClaudeCodeRequestWriterService requestWriterService;

    public MiddayReviewJob(
            MarketDataService marketDataService,
            TradingStateService tradingStateService,
            PositionService positionService,
            LineTemplateService lineTemplateService,
            SchedulerLogService schedulerLogService,
            DailyOrchestrationService orchestrationService,
            AiTaskService aiTaskService,
            CandidateScanService candidateScanService,
            ClaudeCodeRequestWriterService requestWriterService
    ) {
        this.marketDataService = marketDataService;
        this.tradingStateService = tradingStateService;
        this.positionService = positionService;
        this.lineTemplateService = lineTemplateService;
        this.schedulerLogService = schedulerLogService;
        this.orchestrationService = orchestrationService;
        this.aiTaskService = aiTaskService;
        this.candidateScanService = candidateScanService;
        this.requestWriterService = requestWriterService;
    }

    @Scheduled(cron = "${trading.scheduler.midday-review-cron:0 0 11 * * MON-FRI}",
            zone = "${trading.timezone:Asia/Taipei}")
    public void run() {
        LocalDateTime triggerTime = LocalDateTime.now();
        String jobName = "MiddayReviewJob";
        LocalDate today = LocalDate.now();
        OrchestrationStep step = OrchestrationStep.MIDDAY_REVIEW;

        if (!orchestrationService.markRunning(today, step)) {
            log.info("[{}] Step {} already DONE today, skip.", jobName, step);
            return;
        }

        try {
            MarketCurrentResponse market = marketDataService.getCurrentMarket().orElse(null);
            TradingStateResponse state = tradingStateService.getCurrentState().orElse(null);
            List<PositionResponse> openPositions = positionService.getOpenPositions(20);

            // v2.5：先建 MIDDAY task + writeRequest，讓 Claude 有正式 request 可讀
            //   universe = 今日候選 10 檔 + 當前 OPEN 持倉（去重，保留順序）
            MiddayUniverse universe = buildMiddayUniverse(today, openPositions);
            Long middayTaskId = createMiddayTask(today, universe);
            writeMiddayRequest(today, middayTaskId, universe, market, state, openPositions);

            String message = buildMessage(
                    today,
                    buildMarketSummary(market, state),
                    buildPositionSummary(openPositions),
                    buildAdvice(market, openPositions.size())
            );
            lineTemplateService.notifyMidday(message, today);

            String aiMd = aiTaskService.findLatestMarkdown(today, "MIDDAY", "OPENING", "PREMARKET");
            if (aiMd != null && aiMd.length() > 100) {
                String summary = aiMd.length() > 2500
                        ? aiMd.substring(0, 2500) + "\n...(內容過長已截斷，完整內容請看 AI task)"
                        : aiMd;
                lineTemplateService.notifySystemAlert("11:00 AI 研究摘要", summary);
            }

            String logMsg = String.format("grade=%s positions=%d middayTaskId=%s universe=%d",
                    market != null ? market.marketGrade() : "N/A",
                    openPositions.size(),
                    middayTaskId == null ? "N/A" : middayTaskId,
                    universe.symbols().size());
            log.info("[MiddayReviewJob] {}", logMsg);
            schedulerLogService.success(jobName, triggerTime, LocalDateTime.now(), logMsg);
            orchestrationService.markDone(today, step, logMsg);
        } catch (Exception e) {
            orchestrationService.markFailed(today, step, e.getMessage());
            schedulerLogService.failed(jobName, triggerTime, LocalDateTime.now(), e.getMessage());
            throw e;
        }
    }

    // ── v2.5 MIDDAY 契約 helpers ─────────────────────────────────────────

    /** MIDDAY universe = 今日候選 10 檔 + OPEN 持倉（去重、保留順序，上限 20） */
    private MiddayUniverse buildMiddayUniverse(LocalDate today, List<PositionResponse> openPositions) {
        List<CandidateResponse> candidates;
        try {
            candidates = candidateScanService.getCurrentCandidates(10);
        } catch (Exception e) {
            log.warn("[MiddayReviewJob] getCurrentCandidates 失敗，僅用持倉: {}", e.getMessage());
            candidates = List.of();
        }

        Set<String> ordered = new LinkedHashSet<>();
        List<AiTaskCandidateRef> refs = new ArrayList<>();

        for (CandidateResponse c : candidates) {
            if (c.symbol() == null || c.symbol().isBlank()) continue;
            if (ordered.add(c.symbol().trim())) {
                refs.add(new AiTaskCandidateRef(
                        c.symbol(), c.stockName(), c.themeTag(), c.javaStructureScore()));
            }
        }
        for (PositionResponse p : openPositions) {
            if (p.symbol() == null || p.symbol().isBlank()) continue;
            if (ordered.add(p.symbol().trim())) {
                refs.add(new AiTaskCandidateRef(p.symbol(), p.stockName(), null, null));
            }
            if (ordered.size() >= 20) break;
        }
        return new MiddayUniverse(new ArrayList<>(ordered), refs);
    }

    /** 建立 MIDDAY ai_task；universe 為空時回 null（不建空 task）。 */
    private Long createMiddayTask(LocalDate today, MiddayUniverse universe) {
        if (universe.symbols().isEmpty()) {
            log.info("[MiddayReviewJob] universe 為空，略過建 MIDDAY task");
            return null;
        }
        try {
            var task = aiTaskService.createTask(
                    today, "MIDDAY", null, universe.refs(),
                    "11:00 盤中分析（候選 " + universe.symbols().size()
                            + " 檔含持倉），等 Claude 11:15 / Codex 11:25 接手",
                    "D:/ai/stock/claude-research-request.json"
            );
            return task.getId();
        } catch (Exception e) {
            log.warn("[MiddayReviewJob] createTask 失敗: {}", e.getMessage());
            return null;
        }
    }

    /** 寫 Claude request.json 供 Claude Code Agent 讀取。 */
    private void writeMiddayRequest(LocalDate today, Long taskId, MiddayUniverse universe,
                                     MarketCurrentResponse market,
                                     TradingStateResponse state,
                                     List<PositionResponse> openPositions) {
        try {
            String context = String.format(
                    "{\"source\":\"midday_review\",\"grade\":\"%s\",\"phase\":\"%s\",\"positions\":%d,\"candidates\":%d}",
                    market == null ? "N/A" : market.marketGrade(),
                    market == null ? "N/A" : market.marketPhase(),
                    openPositions.size(),
                    universe.symbols().size()
            );
            requestWriterService.writeRequest(taskId, "MIDDAY", today, universe.symbols(), context);
        } catch (Exception e) {
            log.warn("[MiddayReviewJob] writeRequest 失敗: {}", e.getMessage());
        }
    }

    private record MiddayUniverse(List<String> symbols, List<AiTaskCandidateRef> refs) {}

    // ── 舊 helpers（原 v1 LINE 通知）──────────────────────────────────────

    private String buildMarketSummary(MarketCurrentResponse market, TradingStateResponse state) {
        if (market == null) return "尚未取得市場快照，禁止用本則做進場依據。";
        StringBuilder sb = new StringBuilder();
        sb.append("盤勢：").append(market.marketGrade()).append("｜階段：").append(market.marketPhase());
        if (state != null) {
            sb.append("\n五分鐘監控：").append(state.monitorMode())
                    .append("｜Gate：").append(state.hourlyGate())
                    .append("｜決策鎖：").append(state.decisionLock());
        }
        return sb.toString();
    }

    private String buildPositionSummary(List<PositionResponse> positions) {
        if (positions.isEmpty()) return "目前無開放持倉。";
        return positions.stream()
                .limit(5)
                .map(p -> String.format(Locale.ROOT, "%s %s %.0f 股，成本 %.2f",
                        p.symbol(), p.side(), p.qty().doubleValue(), p.avgCost().doubleValue()))
                .collect(Collectors.joining("\n"));
    }

    private String buildAdvice(MarketCurrentResponse market, int positionCount) {
        if (market == null) return "等市場資料恢復，不新增交易。";
        String grade = market.marketGrade();
        if ("C".equals(grade)) return "盤勢不適合新增交易，只保留持倉風控。";
        if ("A".equals(grade) && positionCount == 0) return "仍需等 09:30/盤中決策條件，不追高。";
        if (positionCount > 0) return "持倉優先看停損、移動停利與減碼條件。";
        return "B 盤只觀察，不主動追價。";
    }

    private String buildMessage(LocalDate date, String market, String position, String advice) {
        return String.join("\n",
                "【盤中分析】" + date,
                "",
                "📊 市場",
                market,
                "",
                "📌 持倉",
                position,
                "",
                "🎯 行動",
                advice,
                "",
                "來源：Trading System");
    }
}
