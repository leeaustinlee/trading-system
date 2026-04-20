package com.austin.trading.scheduler;

import com.austin.trading.dto.response.CandidateResponse;
import com.austin.trading.dto.response.MarketCurrentResponse;
import com.austin.trading.notify.LineTemplateService;
import com.austin.trading.service.AiTaskService;
import com.austin.trading.service.CandidateScanService;
import com.austin.trading.service.DailyOrchestrationService;
import com.austin.trading.service.MarketDataService;
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
import java.util.stream.Collectors;

/**
 * 18:30 明日計畫排程（T86DataPrepJob 18:10 完成後執行）。
 * <p>
 * 整合今日候選股（含 T86 法人資料已更新至 payload）及市場狀態，
 * 建立明日盤前計畫並發送 LINE 通知。
 * </p>
 */
@Component
@ConditionalOnProperty(prefix = "trading.scheduler.tomorrow-plan", name = "enabled", havingValue = "true")
public class TomorrowPlan1800Job {

    private static final Logger log = LoggerFactory.getLogger(TomorrowPlan1800Job.class);

    private final MarketDataService   marketDataService;
    private final CandidateScanService candidateScanService;
    private final LineTemplateService  lineTemplateService;
    private final SchedulerLogService  schedulerLogService;
    private final DailyOrchestrationService orchestrationService;
    private final AiTaskService aiTaskService;

    public TomorrowPlan1800Job(
            MarketDataService marketDataService,
            CandidateScanService candidateScanService,
            LineTemplateService lineTemplateService,
            SchedulerLogService schedulerLogService,
            DailyOrchestrationService orchestrationService,
            AiTaskService aiTaskService
    ) {
        this.marketDataService   = marketDataService;
        this.candidateScanService = candidateScanService;
        this.lineTemplateService  = lineTemplateService;
        this.schedulerLogService  = schedulerLogService;
        this.orchestrationService = orchestrationService;
        this.aiTaskService        = aiTaskService;
    }

    @Scheduled(cron = "${trading.scheduler.tomorrow-plan-cron:0 30 18 * * MON-FRI}",
               zone  = "${trading.timezone:Asia/Taipei}")
    public void run() {
        LocalDateTime triggerTime = LocalDateTime.now();
        String jobName = "TomorrowPlan1800Job";
        LocalDate today = LocalDate.now();
        OrchestrationStep step = OrchestrationStep.TOMORROW_PLAN;

        if (!orchestrationService.markRunning(today, step)) {
            log.info("[{}] Step {} already DONE today, skip.", jobName, step);
            return;
        }
        try {
            MarketCurrentResponse market = marketDataService.getCurrentMarket().orElse(null);
            List<CandidateResponse> candidates = candidateScanService.getCurrentCandidates(10);

            String message = buildMessage(today, market, candidates);
            lineTemplateService.notifyTomorrowPlan(message, today);

            // 補發：T86_TOMORROW / POSTMARKET 任一 AI 研究 md
            String aiMd = aiTaskService.findLatestMarkdown(today, "T86_TOMORROW", "POSTMARKET");
            if (aiMd != null && aiMd.length() > 100) {
                String summary = aiMd.length() > 3500
                        ? aiMd.substring(0, 3500) + "\n...(內容過長已截斷)"
                        : aiMd;
                lineTemplateService.notifySystemAlert("📎 18:00 明日研究摘要", summary);
            }

            log.info("[TomorrowPlan1800Job] candidates={}", candidates.size());
            String msg = "candidates=" + candidates.size();
            schedulerLogService.success(jobName, triggerTime, LocalDateTime.now(), msg);
            orchestrationService.markDone(today, step, msg);

        } catch (Exception e) {
            orchestrationService.markFailed(today, step, e.getMessage());
            schedulerLogService.failed(jobName, triggerTime, LocalDateTime.now(), e.getMessage());
            throw e;
        }
    }

    // ── 私有方法 ─────────────────────────────────────────────────────────────────

    private String buildMessage(
            LocalDate today,
            MarketCurrentResponse market,
            List<CandidateResponse> candidates
    ) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n📅 【18:30 明日計畫】").append(today).append("\n");
        sb.append("━━━━━━━━━━━━━━\n");

        // 今日市場結算
        if (market != null) {
            sb.append("📊 今日行情：")
              .append(market.marketGrade()).append(" ─ ")
              .append(market.marketPhase()).append("\n\n");
        }

        // 明日候選名單（含 T86 更新後的法人資訊）
        sb.append("🔵 明日觀察候選（").append(candidates.size()).append(" 檔）\n");
        if (candidates.isEmpty()) {
            sb.append("  （無候選資料，請確認盤後掃描）\n");
        } else {
            for (CandidateResponse c : candidates) {
                sb.append("  ▶ ").append(c.symbol());
                if (c.stockName() != null) sb.append(" ").append(c.stockName());
                if (c.entryPriceZone() != null) sb.append("  區：").append(c.entryPriceZone());
                if (c.valuationMode() != null) sb.append("  估值：").append(c.valuationMode());
                sb.append("\n");
            }
        }

        sb.append("\n⚠️ 以上為今日收盤候選，含最新三大法人資料\n");
        sb.append("08:30 盤前通知將依美股 / ADR 再次篩選\n\n");
        sb.append("來源：Codex");
        return sb.toString();
    }
}
