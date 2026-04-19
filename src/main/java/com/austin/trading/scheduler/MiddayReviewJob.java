package com.austin.trading.scheduler;

import com.austin.trading.dto.response.MarketCurrentResponse;
import com.austin.trading.dto.response.PositionResponse;
import com.austin.trading.dto.response.TradingStateResponse;
import com.austin.trading.notify.LineTemplateService;
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
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * 11:00 盤中戰情更新排程。
 * <p>
 * 讀取當前市場狀態、持倉，透過 LINE 發送盤中更新。
 * 若無持倉且行情等級 C，自動附加「今日建議休息」。
 * </p>
 */
@Component
@ConditionalOnProperty(prefix = "trading.scheduler.midday-review", name = "enabled", havingValue = "true")
public class MiddayReviewJob {

    private static final Logger log = LoggerFactory.getLogger(MiddayReviewJob.class);

    private final MarketDataService   marketDataService;
    private final TradingStateService tradingStateService;
    private final PositionService     positionService;
    private final LineTemplateService lineTemplateService;
    private final SchedulerLogService schedulerLogService;
    private final DailyOrchestrationService orchestrationService;

    public MiddayReviewJob(
            MarketDataService marketDataService,
            TradingStateService tradingStateService,
            PositionService positionService,
            LineTemplateService lineTemplateService,
            SchedulerLogService schedulerLogService,
            DailyOrchestrationService orchestrationService
    ) {
        this.marketDataService   = marketDataService;
        this.tradingStateService = tradingStateService;
        this.positionService     = positionService;
        this.lineTemplateService = lineTemplateService;
        this.schedulerLogService = schedulerLogService;
        this.orchestrationService = orchestrationService;
    }

    @Scheduled(cron = "${trading.scheduler.midday-review-cron:0 0 11 * * MON-FRI}",
               zone  = "${trading.timezone:Asia/Taipei}")
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
            TradingStateResponse  state  = tradingStateService.getCurrentState().orElse(null);
            List<PositionResponse> openPositions = positionService.getOpenPositions(20);

            String marketSummary = buildMarketSummary(market, state);
            String positionSummary = buildPositionSummary(openPositions);
            String advice = buildAdvice(market, openPositions.size());

            String message = buildMessage(today, marketSummary, positionSummary, advice);
            lineTemplateService.notifyMidday(message, today);

            String logMsg = String.format("grade=%s positions=%d",
                    market != null ? market.marketGrade() : "N/A",
                    openPositions.size());
            log.info("[MiddayReviewJob] {}", logMsg);
            schedulerLogService.success(jobName, triggerTime, LocalDateTime.now(), logMsg);
            orchestrationService.markDone(today, step, logMsg);

        } catch (Exception e) {
            orchestrationService.markFailed(today, step, e.getMessage());
            schedulerLogService.failed(jobName, triggerTime, LocalDateTime.now(), e.getMessage());
            throw e;
        }
    }

    // ── 私有方法 ─────────────────────────────────────────────────────────────────

    private String buildMarketSummary(MarketCurrentResponse market, TradingStateResponse state) {
        if (market == null) return "（市場資料尚未更新）";
        StringBuilder sb = new StringBuilder();
        sb.append("行情等級 ").append(market.marketGrade());
        sb.append("，階段：").append(market.marketPhase());
        if (state != null) {
            sb.append("\n監控：").append(state.monitorMode());
            sb.append("，閘：").append(state.hourlyGate());
            sb.append("，鎖定：").append(state.decisionLock());
        }
        return sb.toString();
    }

    private String buildPositionSummary(List<PositionResponse> positions) {
        if (positions.isEmpty()) return "（目前無持倉）";
        return positions.stream()
                .map(p -> String.format(Locale.ROOT, "%s %s × %.0f 股 成本 %.2f",
                        p.symbol(), p.side(), p.qty().doubleValue(), p.avgCost().doubleValue()))
                .collect(Collectors.joining("\n"));
    }

    private String buildAdvice(MarketCurrentResponse market, int positionCount) {
        if (market == null) return "等待市場資料。";
        String grade = market.marketGrade();
        if ("C".equals(grade)) {
            return "行情等級 C，建議今日休息，避免新進場。";
        }
        if ("A".equals(grade) && positionCount == 0) {
            return "A 級行情，可留意突破型機會，等候 09:30 計畫內標的。";
        }
        if (positionCount > 0) {
            return "持倉期間密切監控關鍵停利/停損價位。";
        }
        return "B 級震盪，謹慎操作，倉位勿超過資金 30%。";
    }

    private String buildMessage(LocalDate date, String market, String position, String advice) {
        return "\n📊 【11:00 盤中戰情】" + date + "\n" +
               "━━━━━━━━━━━━━━\n" +
               "📈 市場\n" + market + "\n\n" +
               "📋 持倉\n" + position + "\n\n" +
               "💡 " + advice + "\n\n" +
               "來源：Codex";
    }
}
