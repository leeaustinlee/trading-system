package com.austin.trading.notify;

import com.austin.trading.dto.response.FinalDecisionResponse;
import com.austin.trading.dto.response.HourlyGateDecisionResponse;
import com.austin.trading.dto.response.MonitorDecisionResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * 通知統一入口（v2.13）：把通知並聯到 Telegram + LINE。
 *
 * <h3>策略</h3>
 * <ul>
 *   <li>Telegram 為主要管道（手機端 HTML 格式體驗較好）</li>
 *   <li>LINE 保留為 fallback，可由 {@code LINE_ENABLED=false} 單獨關閉</li>
 *   <li>兩邊各自獨立 cooldown 與 send：任一邊失敗 / 關閉 都不影響另一邊</li>
 *   <li>所有業務點原本注入 {@link LineTemplateService}，可改注入 {@link NotificationFacade}
 *       一行替換即可同時發兩邊</li>
 * </ul>
 *
 * <p>主交易流程不會被通知失敗 throw 影響：facade 在每邊呼叫外都包 try/catch。</p>
 */
@Service
public class NotificationFacade {

    private static final Logger log = LoggerFactory.getLogger(NotificationFacade.class);

    private final TelegramTemplateService telegram;
    private final LineTemplateService line;

    public NotificationFacade(TelegramTemplateService telegram, LineTemplateService line) {
        this.telegram = telegram;
        this.line = line;
    }

    public void notifyPremarket(String marketSummary, String topCandidates, LocalDate date) {
        dispatch("premarket",
                () -> telegram.notifyPremarket(marketSummary, topCandidates, date),
                () -> line.notifyPremarket(marketSummary, topCandidates, date));
    }

    public void notifyFinalDecision(FinalDecisionResponse decision, LocalDate date) {
        dispatch("finalDecision",
                () -> telegram.notifyFinalDecision(decision, date),
                () -> line.notifyFinalDecision(decision, date));
    }

    public void notifyHourlyGate(HourlyGateDecisionResponse decision, LocalTime time) {
        dispatch("hourlyGate",
                () -> telegram.notifyHourlyGate(decision, time),
                () -> line.notifyHourlyGate(decision, time));
    }

    public void notifyMonitor(MonitorDecisionResponse decision, LocalTime time) {
        dispatch("monitor",
                () -> telegram.notifyMonitor(decision, time),
                () -> line.notifyMonitor(decision, time));
    }

    public void notifyReview1400(String reviewSummary, LocalDate date) {
        dispatch("review1400",
                () -> telegram.notifyReview1400(reviewSummary, date),
                () -> line.notifyReview1400(reviewSummary, date));
    }

    public void notifyMidday(String message, LocalDate date) {
        dispatch("midday",
                () -> telegram.notifyMidday(message, date),
                () -> line.notifyMidday(message, date));
    }

    public void notifyTomorrowPlan(String message, LocalDate date) {
        dispatch("tomorrowPlan",
                () -> telegram.notifyTomorrowPlan(message, date),
                () -> line.notifyTomorrowPlan(message, date));
    }

    public void notifyPostmarket(String candidates, LocalDate date) {
        dispatch("postmarket",
                () -> telegram.notifyPostmarket(candidates, date),
                () -> line.notifyPostmarket(candidates, date));
    }

    public void notifyAiTaskFinal(String taskType, String message, LocalDate date) {
        dispatch("aiTaskFinal:" + taskType,
                () -> telegram.notifyAiTaskFinal(taskType, message, date),
                () -> line.notifyAiTaskFinal(taskType, message, date));
    }

    public void notifySystemAlert(String title, String message) {
        dispatch("systemAlert:" + title,
                () -> telegram.notifySystemAlert(title, message),
                () -> line.notifySystemAlert(title, message));
    }

    public void notifyPositionAlert(String symbol, String status, String reason,
                                     Double currentPrice, Double entryPrice, Double pnlPct) {
        dispatch("positionAlert:" + symbol,
                () -> telegram.notifyPositionAlert(symbol, status, reason, currentPrice, entryPrice, pnlPct),
                () -> line.notifyPositionAlert(symbol, status, reason, currentPrice, entryPrice, pnlPct));
    }

    public void notifyPositionAction(String symbol, String action, String reason,
                                      Double currentPrice, Double entryPrice, Double pnlPct,
                                      List<String> signals, String switchTo, Double scoreGap) {
        notifyPositionAction(symbol, action, reason, currentPrice, entryPrice, pnlPct,
                signals, switchTo, scoreGap, null, null, null);
    }

    public void notifyPositionAction(String symbol, String action, String reason,
                                      Double currentPrice, Double entryPrice, Double pnlPct,
                                      List<String> signals, String switchTo, Double scoreGap,
                                      Double suggestedAmount, Integer suggestedShares,
                                      Double suggestedReducePct) {
        dispatch("positionAction:" + symbol + ":" + action,
                () -> telegram.notifyPositionAction(symbol, action, reason, currentPrice, entryPrice,
                        pnlPct, signals, switchTo, scoreGap, suggestedAmount, suggestedShares, suggestedReducePct),
                () -> line.notifyPositionAction(symbol, action, reason, currentPrice, entryPrice,
                        pnlPct, signals, switchTo, scoreGap, suggestedAmount, suggestedShares, suggestedReducePct));
    }

    public void notifyBuyAllocation(String symbol, String mode, String bucket,
                                     Double score, Double entryPrice, Double stopLoss,
                                     Double suggestedAmount, Integer suggestedShares,
                                     Double riskPerShare, Double maxLossAmount) {
        dispatch("buyAllocation:" + symbol,
                () -> telegram.notifyBuyAllocation(symbol, mode, bucket, score, entryPrice, stopLoss,
                        suggestedAmount, suggestedShares, riskPerShare, maxLossAmount),
                () -> line.notifyBuyAllocation(symbol, mode, bucket, score, entryPrice, stopLoss,
                        suggestedAmount, suggestedShares, riskPerShare, maxLossAmount));
    }

    /** 兩邊各自包 try/catch；任一邊失敗都不影響主流程或另一邊。 */
    private void dispatch(String label, Runnable telegramAction, Runnable lineAction) {
        try {
            telegramAction.run();
        } catch (Exception e) {
            log.warn("[NotificationFacade] telegram {} failed: {}", label, e.getMessage());
        }
        try {
            lineAction.run();
        } catch (Exception e) {
            log.warn("[NotificationFacade] line {} failed: {}", label, e.getMessage());
        }
    }

}
