package com.austin.trading.controller;

import com.austin.trading.entity.SchedulerExecutionLogEntity;
import com.austin.trading.repository.SchedulerExecutionLogRepository;
import com.austin.trading.service.SchedulerLogService;
import com.austin.trading.service.StrategyRecommendationService;
import com.austin.trading.service.TradeReviewService;
import com.austin.trading.workflow.HourlyGateWorkflowService;
import com.austin.trading.workflow.IntradayDecisionWorkflowService;
import com.austin.trading.workflow.PostmarketWorkflowService;
import com.austin.trading.workflow.PremarketWorkflowService;
import com.austin.trading.workflow.WatchlistWorkflowService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Scheduler 管理 API：Job 清單 + 執行歷史 + 手動觸發。
 *
 * <p>用於避免「錯過」某個排程 Job 時可手動補跑。</p>
 */
@RestController
@RequestMapping("/api/scheduler")
public class SchedulerController {

    private final SchedulerExecutionLogRepository logRepository;
    private final SchedulerLogService schedulerLogService;

    private final PremarketWorkflowService premarketWorkflow;
    private final IntradayDecisionWorkflowService intradayDecisionWorkflow;
    private final HourlyGateWorkflowService hourlyGateWorkflow;
    private final PostmarketWorkflowService postmarketWorkflow;
    private final WatchlistWorkflowService watchlistWorkflow;
    private final TradeReviewService tradeReviewService;
    private final StrategyRecommendationService strategyRecommendationService;

    // ── scheduler enabled flags (從 application.yml 注入) ───────────────
    @Value("${trading.scheduler.premarket-notify.enabled:false}")        boolean premarketNotifyEnabled;
    @Value("${trading.scheduler.premarket-data-prep.enabled:false}")     boolean premarketDataPrepEnabled;
    @Value("${trading.scheduler.open-data-prep.enabled:false}")          boolean openDataPrepEnabled;
    @Value("${trading.scheduler.final-decision.enabled:false}")          boolean finalDecisionEnabled;
    @Value("${trading.scheduler.hourly-gate.enabled:false}")             boolean hourlyGateEnabled;
    @Value("${trading.scheduler.five-minute-monitor.enabled:false}")     boolean fiveMinuteMonitorEnabled;
    @Value("${trading.scheduler.midday-review.enabled:false}")           boolean middayReviewEnabled;
    @Value("${trading.scheduler.aftermarket-review.enabled:false}")      boolean aftermarketReviewEnabled;
    @Value("${trading.scheduler.postmarket-data-prep.enabled:false}")    boolean postmarketDataPrepEnabled;
    @Value("${trading.scheduler.postmarket-analysis.enabled:false}")     boolean postmarketAnalysisEnabled;
    @Value("${trading.scheduler.watchlist-refresh.enabled:false}")       boolean watchlistRefreshEnabled;
    @Value("${trading.scheduler.t86-data-prep.enabled:false}")           boolean t86DataPrepEnabled;
    @Value("${trading.scheduler.tomorrow-plan.enabled:false}")           boolean tomorrowPlanEnabled;
    @Value("${trading.scheduler.external-probe-health.enabled:false}")   boolean externalProbeHealthEnabled;
    @Value("${trading.scheduler.weekly-trade-review.enabled:false}")     boolean weeklyTradeReviewEnabled;

    @Value("${trading.scheduler.premarket-notify-cron:}")        String premarketNotifyCron;
    @Value("${trading.scheduler.premarket-data-prep-cron:}")     String premarketDataPrepCron;
    @Value("${trading.scheduler.open-data-prep-cron:}")          String openDataPrepCron;
    @Value("${trading.scheduler.final-decision-cron:}")          String finalDecisionCron;
    @Value("${trading.scheduler.hourly-gate-cron:}")             String hourlyGateCron;
    @Value("${trading.scheduler.five-minute-monitor-cron:}")     String fiveMinuteMonitorCron;
    @Value("${trading.scheduler.midday-review-cron:}")           String middayReviewCron;
    @Value("${trading.scheduler.aftermarket-review-cron:}")      String aftermarketReviewCron;
    @Value("${trading.scheduler.postmarket-data-prep-cron:}")    String postmarketDataPrepCron;
    @Value("${trading.scheduler.postmarket-analysis-cron:}")     String postmarketAnalysisCron;
    @Value("${trading.scheduler.watchlist-refresh-cron:}")       String watchlistRefreshCron;
    @Value("${trading.scheduler.t86-data-prep-cron:}")           String t86DataPrepCron;
    @Value("${trading.scheduler.tomorrow-plan-cron:}")           String tomorrowPlanCron;
    @Value("${trading.scheduler.external-probe-health-cron:}")   String externalProbeHealthCron;
    @Value("${trading.scheduler.weekly-trade-review-cron:}")     String weeklyTradeReviewCron;

    public SchedulerController(
            SchedulerExecutionLogRepository logRepository,
            SchedulerLogService schedulerLogService,
            PremarketWorkflowService premarketWorkflow,
            IntradayDecisionWorkflowService intradayDecisionWorkflow,
            HourlyGateWorkflowService hourlyGateWorkflow,
            PostmarketWorkflowService postmarketWorkflow,
            WatchlistWorkflowService watchlistWorkflow,
            TradeReviewService tradeReviewService,
            StrategyRecommendationService strategyRecommendationService
    ) {
        this.logRepository = logRepository;
        this.schedulerLogService = schedulerLogService;
        this.premarketWorkflow = premarketWorkflow;
        this.intradayDecisionWorkflow = intradayDecisionWorkflow;
        this.hourlyGateWorkflow = hourlyGateWorkflow;
        this.postmarketWorkflow = postmarketWorkflow;
        this.watchlistWorkflow = watchlistWorkflow;
        this.tradeReviewService = tradeReviewService;
        this.strategyRecommendationService = strategyRecommendationService;
    }

    // ── Job 清單 ──────────────────────────────────────────────────────

    @GetMapping("/jobs")
    public List<Map<String, Object>> listJobs() {
        List<Map<String, Object>> jobs = new ArrayList<>();
        jobs.add(job("PremarketDataPrepJob",   "盤前資料準備",  premarketDataPrepCron,   premarketDataPrepEnabled,   "premarket-data-prep"));
        jobs.add(job("PremarketNotifyJob",     "盤前通知",      premarketNotifyCron,     premarketNotifyEnabled,     "premarket"));
        jobs.add(job("OpenDataPrepJob",        "開盤資料準備",  openDataPrepCron,        openDataPrepEnabled,        null));
        jobs.add(job("FinalDecision0930Job",   "09:30 最終決策", finalDecisionCron,       finalDecisionEnabled,       "final-decision"));
        jobs.add(job("HourlyIntradayGateJob",  "整點行情閘",    hourlyGateCron,          hourlyGateEnabled,          "hourly-gate"));
        jobs.add(job("FiveMinuteMonitorJob",   "5分鐘監控+持倉", fiveMinuteMonitorCron,   fiveMinuteMonitorEnabled,   null));
        jobs.add(job("MiddayReviewJob",        "11:00 盤中戰情", middayReviewCron,        middayReviewEnabled,        null));
        jobs.add(job("AftermarketReview1400Job","14:00 交易檢討", aftermarketReviewCron,   aftermarketReviewEnabled,   null));
        jobs.add(job("PostmarketDataPrepJob",  "盤後資料準備",  postmarketDataPrepCron,  postmarketDataPrepEnabled,  null));
        jobs.add(job("PostmarketAnalysis1530Job","15:30 盤後分析",postmarketAnalysisCron,  postmarketAnalysisEnabled,  "postmarket"));
        jobs.add(job("WatchlistRefreshJob",    "15:35 觀察名單刷新",watchlistRefreshCron, watchlistRefreshEnabled,    "watchlist-refresh"));
        jobs.add(job("T86DataPrepJob",         "18:10 法人籌碼", t86DataPrepCron,         t86DataPrepEnabled,         null));
        jobs.add(job("TomorrowPlan1800Job",    "18:30 明日計畫", tomorrowPlanCron,        tomorrowPlanEnabled,        null));
        jobs.add(job("ExternalProbeHealthJob", "外部服務健康", externalProbeHealthCron, externalProbeHealthEnabled, null));
        jobs.add(job("WeeklyTradeReviewJob",   "週五交易檢討+建議",weeklyTradeReviewCron, weeklyTradeReviewEnabled,   "weekly-review"));
        return jobs;
    }

    // ── 執行歷史 ──────────────────────────────────────────────────────

    @GetMapping("/logs")
    public List<SchedulerExecutionLogEntity> logs(
            @RequestParam(required = false) String jobName,
            @RequestParam(defaultValue = "50") int limit) {
        int n = Math.max(1, Math.min(limit, 500));
        if (jobName != null && !jobName.isBlank()) {
            return logRepository.findByJobNameOrderByTriggerTimeDesc(jobName, PageRequest.of(0, n));
        }
        return logRepository.findAllByOrderByTriggerTimeDesc(PageRequest.of(0, n));
    }

    // ── 手動觸發 ──────────────────────────────────────────────────────

    @PostMapping("/trigger/{triggerKey}")
    public ResponseEntity<?> trigger(@PathVariable String triggerKey,
                                      @RequestParam(required = false) String date) {
        LocalDate d = date != null && !date.isBlank() ? LocalDate.parse(date) : LocalDate.now();
        LocalDateTime trigger = LocalDateTime.now();
        String jobName = triggerKeyToJobName(triggerKey) + " (MANUAL)";
        try {
            Object result = switch (triggerKey) {
                case "premarket"         -> { premarketWorkflow.execute(d); yield "premarket done"; }
                case "premarket-data-prep" -> { premarketWorkflow.execute(d); yield "premarket-data-prep done"; }
                case "final-decision"    -> { intradayDecisionWorkflow.execute(d); yield "final-decision done"; }
                case "hourly-gate"       -> { hourlyGateWorkflow.execute(d, LocalTime.now()); yield "hourly-gate done"; }
                case "postmarket"        -> { postmarketWorkflow.execute(d); yield "postmarket done"; }
                case "watchlist-refresh" -> { watchlistWorkflow.execute(d); yield "watchlist-refresh done"; }
                case "weekly-review"     -> {
                    int r = tradeReviewService.generateForAllUnreviewed();
                    int s = strategyRecommendationService.generate(null).size();
                    yield "reviewed=" + r + ", recommendations=" + s;
                }
                default -> throw new IllegalArgumentException("Unknown triggerKey: " + triggerKey);
            };
            schedulerLogService.success(jobName, trigger, LocalDateTime.now(), result.toString());
            return ResponseEntity.ok(Map.of("ok", true, "triggerKey", triggerKey, "date", d.toString(), "result", result));
        } catch (Exception e) {
            schedulerLogService.failed(jobName, trigger, LocalDateTime.now(), e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of(
                    "ok", false, "triggerKey", triggerKey, "error", e.getMessage()));
        }
    }

    private String triggerKeyToJobName(String triggerKey) {
        return switch (triggerKey) {
            case "premarket"           -> "PremarketNotifyJob";
            case "premarket-data-prep" -> "PremarketDataPrepJob";
            case "final-decision"      -> "FinalDecision0930Job";
            case "hourly-gate"         -> "HourlyIntradayGateJob";
            case "postmarket"          -> "PostmarketAnalysis1530Job";
            case "watchlist-refresh"   -> "WatchlistRefreshJob";
            case "weekly-review"       -> "WeeklyTradeReviewJob";
            default                    -> triggerKey;
        };
    }

    // ── 私有方法 ──────────────────────────────────────────────────────

    private Map<String, Object> job(String jobName, String display, String cron, boolean enabled, String triggerKey) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jobName", jobName);
        m.put("display", display);
        m.put("cron", cron);
        m.put("enabled", enabled);
        m.put("triggerKey", triggerKey);   // 若有值，可用 POST /api/scheduler/trigger/{triggerKey} 觸發
        logRepository.findTopByJobNameOrderByTriggerTimeDesc(jobName).ifPresent(last -> {
            m.put("lastRunAt", last.getTriggerTime());
            m.put("lastStatus", last.getStatus());
            m.put("lastMessage", last.getMessage());
            m.put("lastDurationMs", last.getDurationMs());
        });
        return m;
    }
}
