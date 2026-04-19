package com.austin.trading.controller;

import com.austin.trading.entity.SchedulerExecutionLogEntity;
import com.austin.trading.repository.SchedulerExecutionLogRepository;
import com.austin.trading.service.DailyOrchestrationService;
import com.austin.trading.service.OrchestrationStep;
import com.austin.trading.service.SchedulerLogService;
import com.austin.trading.service.StrategyRecommendationService;
import com.austin.trading.service.TradeReviewService;
import com.austin.trading.scheduler.AftermarketReview1400Job;
import com.austin.trading.scheduler.MiddayReviewJob;
import com.austin.trading.scheduler.OpenDataPrepJob;
import com.austin.trading.scheduler.PostmarketDataPrepJob;
import com.austin.trading.scheduler.T86DataPrepJob;
import com.austin.trading.scheduler.TomorrowPlan1800Job;
import com.austin.trading.workflow.HourlyGateWorkflowService;
import com.austin.trading.workflow.IntradayDecisionWorkflowService;
import com.austin.trading.workflow.PostmarketWorkflowService;
import com.austin.trading.workflow.PremarketWorkflowService;
import com.austin.trading.workflow.WatchlistWorkflowService;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final DailyOrchestrationService orchestrationService;

    private final PremarketWorkflowService premarketWorkflow;
    private final IntradayDecisionWorkflowService intradayDecisionWorkflow;
    private final HourlyGateWorkflowService hourlyGateWorkflow;
    private final PostmarketWorkflowService postmarketWorkflow;
    private final WatchlistWorkflowService watchlistWorkflow;
    private final TradeReviewService tradeReviewService;
    private final StrategyRecommendationService strategyRecommendationService;

    // 直接呼叫 Job 的手動觸發（@Autowired(required=false) 因 @ConditionalOnProperty enabled=false 時 bean 不存在）
    @Autowired(required = false) private OpenDataPrepJob openDataPrepJob;
    @Autowired(required = false) private MiddayReviewJob middayReviewJob;
    @Autowired(required = false) private AftermarketReview1400Job aftermarketReview1400Job;
    @Autowired(required = false) private PostmarketDataPrepJob postmarketDataPrepJob;
    @Autowired(required = false) private T86DataPrepJob t86DataPrepJob;
    @Autowired(required = false) private TomorrowPlan1800Job tomorrowPlan1800Job;

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
    @Value("${trading.scheduler.daily-health-check.enabled:false}")      boolean dailyHealthCheckEnabled;

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
    @Value("${trading.scheduler.daily-health-check-cron:}")      String dailyHealthCheckCron;

    public SchedulerController(
            SchedulerExecutionLogRepository logRepository,
            SchedulerLogService schedulerLogService,
            DailyOrchestrationService orchestrationService,
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
        this.orchestrationService = orchestrationService;
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
        jobs.add(job("OpenDataPrepJob",        "開盤資料準備",  openDataPrepCron,        openDataPrepEnabled,        "open-data-prep"));
        jobs.add(job("FinalDecision0930Job",   "09:30 最終決策", finalDecisionCron,       finalDecisionEnabled,       "final-decision"));
        jobs.add(job("HourlyIntradayGateJob",  "整點行情閘",    hourlyGateCron,          hourlyGateEnabled,          "hourly-gate"));
        jobs.add(job("FiveMinuteMonitorJob",   "5分鐘監控+持倉", fiveMinuteMonitorCron,   fiveMinuteMonitorEnabled,   null));
        jobs.add(job("MiddayReviewJob",        "11:00 盤中戰情", middayReviewCron,        middayReviewEnabled,        "midday-review"));
        jobs.add(job("AftermarketReview1400Job","14:00 交易檢討", aftermarketReviewCron,   aftermarketReviewEnabled,   "aftermarket-review"));
        jobs.add(job("PostmarketDataPrepJob",  "盤後資料準備",  postmarketDataPrepCron,  postmarketDataPrepEnabled,  "postmarket-data-prep"));
        jobs.add(job("PostmarketAnalysis1530Job","15:30 盤後分析",postmarketAnalysisCron,  postmarketAnalysisEnabled,  "postmarket"));
        jobs.add(job("WatchlistRefreshJob",    "15:35 觀察名單刷新",watchlistRefreshCron, watchlistRefreshEnabled,    "watchlist-refresh"));
        jobs.add(job("T86DataPrepJob",         "18:10 法人籌碼", t86DataPrepCron,         t86DataPrepEnabled,         "t86-data-prep"));
        jobs.add(job("TomorrowPlan1800Job",    "18:30 明日計畫", tomorrowPlanCron,        tomorrowPlanEnabled,        "tomorrow-plan"));
        jobs.add(job("ExternalProbeHealthJob", "外部服務健康", externalProbeHealthCron, externalProbeHealthEnabled, null));
        jobs.add(job("WeeklyTradeReviewJob",   "週五交易檢討+建議",weeklyTradeReviewCron, weeklyTradeReviewEnabled,   "weekly-review"));
        jobs.add(job("DailyHealthCheckJob",    "每日健康檢查",  dailyHealthCheckCron,    dailyHealthCheckEnabled,    null));
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
                                      @RequestParam(required = false) String date,
                                      @RequestParam(required = false, defaultValue = "false") boolean force) {
        LocalDate d = date != null && !date.isBlank() ? LocalDate.parse(date) : LocalDate.now();
        LocalDateTime trigger = LocalDateTime.now();
        String jobName = triggerKeyToJobName(triggerKey) + " (MANUAL)";

        // 1. 解析對應的 orchestration step（若該 trigger 能對應）
        OrchestrationStep step = OrchestrationStep.fromKey(triggerKey).orElse(null);

        // 2. 走跟 scheduler 一致的 markRunning 流程，但手動可透過 force 覆寫 DONE
        if (step != null) {
            boolean acquired = force
                    ? orchestrationService.forceMarkRunning(d, step)
                    : orchestrationService.markRunning(d, step);
            if (!acquired) {
                return ResponseEntity.ok(Map.of(
                        "ok", true,
                        "triggerKey", triggerKey,
                        "date", d.toString(),
                        "skipped", true,
                        "reason", "already DONE today (use ?force=true to override)"));
            }
        }

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
                // 新增 6 個直接呼叫 Job.run() 的 trigger
                case "open-data-prep"    -> { requireJob(openDataPrepJob, "open-data-prep");    openDataPrepJob.run();    yield "open-data-prep done"; }
                case "midday-review"     -> { requireJob(middayReviewJob, "midday-review");     middayReviewJob.run();    yield "midday-review done"; }
                case "aftermarket-review"-> { requireJob(aftermarketReview1400Job, "aftermarket-review"); aftermarketReview1400Job.run(); yield "aftermarket-review done"; }
                case "postmarket-data-prep" -> { requireJob(postmarketDataPrepJob, "postmarket-data-prep"); postmarketDataPrepJob.run(); yield "postmarket-data-prep done"; }
                case "t86-data-prep"     -> { requireJob(t86DataPrepJob, "t86-data-prep");      t86DataPrepJob.run();     yield "t86-data-prep done"; }
                case "tomorrow-plan"     -> { requireJob(tomorrowPlan1800Job, "tomorrow-plan"); tomorrowPlan1800Job.run(); yield "tomorrow-plan done"; }
                default -> throw new IllegalArgumentException("Unknown triggerKey: " + triggerKey);
            };
            schedulerLogService.success(jobName, trigger, LocalDateTime.now(), result.toString());
            if (step != null) {
                orchestrationService.markDone(d, step, "manual: " + result);
            }
            return ResponseEntity.ok(Map.of("ok", true, "triggerKey", triggerKey, "date", d.toString(),
                    "forced", force, "result", result));
        } catch (Exception e) {
            schedulerLogService.failed(jobName, trigger, LocalDateTime.now(), e.getMessage());
            if (step != null) {
                orchestrationService.markFailed(d, step, "manual: " + e.getMessage());
            }
            return ResponseEntity.internalServerError().body(Map.of(
                    "ok", false, "triggerKey", triggerKey, "error", e.getMessage()));
        }
    }

    private String triggerKeyToJobName(String triggerKey) {
        return switch (triggerKey) {
            case "premarket"           -> "PremarketNotifyJob";
            case "premarket-data-prep" -> "PremarketDataPrepJob";
            case "open-data-prep"      -> "OpenDataPrepJob";
            case "final-decision"      -> "FinalDecision0930Job";
            case "hourly-gate"         -> "HourlyIntradayGateJob";
            case "midday-review"       -> "MiddayReviewJob";
            case "aftermarket-review"  -> "AftermarketReview1400Job";
            case "postmarket-data-prep"-> "PostmarketDataPrepJob";
            case "postmarket"          -> "PostmarketAnalysis1530Job";
            case "watchlist-refresh"   -> "WatchlistRefreshJob";
            case "t86-data-prep"       -> "T86DataPrepJob";
            case "tomorrow-plan"       -> "TomorrowPlan1800Job";
            case "weekly-review"       -> "WeeklyTradeReviewJob";
            default                    -> triggerKey;
        };
    }

    /** 若某個 Job bean 因 enabled=false 沒被建立，回傳清楚的 400 而非 NPE */
    private void requireJob(Object jobBean, String triggerKey) {
        if (jobBean == null) {
            throw new IllegalStateException("Job for '" + triggerKey
                    + "' is not available — set trading.scheduler." + triggerKey + ".enabled=true and restart");
        }
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
