package com.austin.trading.notify;

import com.austin.trading.dto.request.NotificationCreateRequest;
import com.austin.trading.dto.response.FinalDecisionResponse;
import com.austin.trading.dto.response.HourlyGateDecisionResponse;
import com.austin.trading.dto.response.MonitorDecisionResponse;
import com.austin.trading.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/**
 * Telegram 業務模板層（與 {@link LineTemplateService} 平行）。
 *
 * <p>對外提供 8 個 {@code notifyXxx} 方法，與 LINE 完全對齊。內部：</p>
 * <ol>
 *   <li>用 {@link LineMessageBuilder} 產出純文字訊息（reuse 既有格式）</li>
 *   <li>HTML escape 後包成 Telegram HTML（標題加 {@code <b>}）</li>
 *   <li>cooldown 共用 {@link NotificationService}，但 type prefix 加 {@code TG_} 與 LINE 獨立</li>
 *   <li>呼叫 {@link TelegramSender} 送出，失敗 graceful return</li>
 * </ol>
 */
@Service
public class TelegramTemplateService {

    public static final String SOURCE = "Trading System (TG)";
    public static final String TYPE_PREFIX = "TG_";
    private static final Logger log = LoggerFactory.getLogger(TelegramTemplateService.class);

    private final TelegramSender telegramSender;
    private final NotificationService notificationService;

    public TelegramTemplateService(TelegramSender telegramSender,
                                    NotificationService notificationService) {
        this.telegramSender = telegramSender;
        this.notificationService = notificationService;
    }

    public void notifyPremarket(String marketSummary, String topCandidates, LocalDate date) {
        String body = LineMessageBuilder.buildPremarket(marketSummary, topCandidates, date);
        sendAndLog("PREMARKET_0830", "盤前分析 " + date,
                wrapHtml("📊 盤前分析", body), null);
    }

    public void notifyFinalDecision(FinalDecisionResponse decision, LocalDate date) {
        String body = LineMessageBuilder.buildFinalDecision(decision, date);
        sendAndLog("FINAL_DECISION_0930", "09:30 今日操作 " + date,
                wrapHtml("🎯 09:30 今日操作", body), null);
    }

    public void notifyHourlyGate(HourlyGateDecisionResponse decision, LocalTime time) {
        if (!decision.shouldNotify()) return;
        String body = LineMessageBuilder.buildHourlyGate(decision, time);
        sendAndLog("HOURLY_GATE", "盤中每小時監控 " + time,
                wrapHtml("⏰ 盤中每小時監控", body), Duration.ofMinutes(30));
    }

    public void notifyMonitor(MonitorDecisionResponse decision, LocalTime time) {
        if (!decision.shouldNotify()) return;
        String body = LineMessageBuilder.buildMonitor(decision, time);
        if (body == null) return;
        sendAndLog("MONITOR_5M", "5分鐘事件監控 " + decision.triggerEvent(),
                wrapHtml("👀 5 分鐘事件監控", body), Duration.ofMinutes(15));
    }

    public void notifyReview1400(String reviewSummary, LocalDate date) {
        String body = LineMessageBuilder.buildReview1400(reviewSummary, date);
        sendAndLog("REVIEW_1400", "今日操作檢討 " + date,
                wrapHtml("📝 今日操作檢討", body), null);
    }

    public void notifyMidday(String message, LocalDate date) {
        sendAndLog("MIDDAY_1100", "盤中分析 " + date,
                wrapHtml("📊 盤中分析", ensureSource(message)), null);
    }

    public void notifyTomorrowPlan(String message, LocalDate date) {
        sendAndLog("TOMORROW_PLAN_1800", "明日計畫 " + date,
                wrapHtml("📅 明日計畫", ensureSource(message)), null);
    }

    public void notifyPostmarket(String candidates, LocalDate date) {
        String body = LineMessageBuilder.buildPostmarket(candidates, date);
        sendAndLog("POSTMARKET_1530", "盤後選股 " + date,
                wrapHtml("📊 盤後選股", body), null);
    }

    public void notifyAiTaskFinal(String taskType, String message, LocalDate date) {
        sendAndLog("AI_TASK_FINAL_" + taskType, taskType + " final " + date,
                wrapHtml("🤖 AI 研究：" + taskType, ensureSource(message)), null);
    }

    public void notifySystemAlert(String title, String message) {
        sendAndLog("SYSTEM_ALERT", title,
                wrapHtml("🚨 系統警報：" + title, ensureSource(message)),
                Duration.ofMinutes(30));
    }

    public void notifyPositionAlert(String symbol, String status, String reason,
                                     Double currentPrice, Double entryPrice, Double pnlPct) {
        if (currentPrice == null) {
            log.warn("[TelegramTemplateService] 跳過 position alert：currentPrice 為 null symbol={} status={}",
                    symbol, status);
            return;
        }
        String html = TelegramMessageBuilder.buildPositionAlert(
                symbol, status, reason, currentPrice, entryPrice, pnlPct);
        sendAndLog("POSITION_ALERT", "持倉警報 " + symbol + " " + status,
                html, Duration.ofMinutes(120));
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
        if (currentPrice == null || "HOLD".equalsIgnoreCase(action)) return;
        String html = TelegramMessageBuilder.buildPositionAction(
                symbol, action, reason, currentPrice, entryPrice, pnlPct, signals, switchTo, scoreGap,
                suggestedAmount, suggestedShares, suggestedReducePct);
        sendAndLog("POSITION_ACTION",
                "持倉 " + action + " " + symbol + " " + reason,
                html, Duration.ofMinutes(30));
    }

    public void notifyBuyAllocation(String symbol, String mode, String bucket,
                                     Double score, Double entryPrice, Double stopLoss,
                                     Double suggestedAmount, Integer suggestedShares,
                                     Double riskPerShare, Double maxLossAmount) {
        if (suggestedAmount == null || suggestedAmount <= 0
                || suggestedShares == null || suggestedShares <= 0) {
            log.debug("[TelegramTemplateService] 跳過 buy allocation：amount/shares 缺");
            return;
        }
        String html = TelegramMessageBuilder.buildBuyAllocation(
                symbol, mode, bucket, score, entryPrice, stopLoss,
                suggestedAmount, suggestedShares, riskPerShare, maxLossAmount);
        sendAndLog("BUY_ALLOCATION",
                "進場資金 " + symbol + " " + mode,
                html, Duration.ofMinutes(60));
    }

    /**
     * 直接發送原始 HTML 訊息（不走 cooldown，由 caller 自行控制）。
     *
     * @param htmlText 已 HTML escape / 已加上 tag 的訊息
     */
    public boolean sendRaw(String htmlText) {
        return telegramSender.send(htmlText);
    }

    // ══════════════════════════════════════════════════════════════════════
    // 內部
    // ══════════════════════════════════════════════════════════════════════

    /** type 加 TG_ prefix 與 LINE 共用 NotificationLog 表但分流 cooldown。 */
    private void sendAndLog(String type, String title, String htmlMessage, Duration cooldown) {
        String tgType = TYPE_PREFIX + type;
        if (cooldown != null) {
            LocalDateTime after = LocalDateTime.now().minus(cooldown);
            if (notificationService.existsRecent(tgType, title, after)) {
                log.info("[TelegramTemplateService] skip duplicate type={} title={} cooldown={}m",
                        tgType, title, cooldown.toMinutes());
                return;
            }
        }
        boolean ok = telegramSender.send(htmlMessage);
        try {
            notificationService.create(new NotificationCreateRequest(
                    LocalDateTime.now(), tgType, SOURCE, title, htmlMessage, null));
        } catch (Exception e) {
            log.warn("[TelegramTemplateService] notification log persist failed: {}", e.getMessage());
        }
        if (!ok) {
            log.debug("[TelegramTemplateService] telegram send returned false (disabled / token missing / HTTP fail)");
        }
    }

    /** 把純文字 body 包成 Telegram HTML：標題加 <b>、body escape。 */
    static String wrapHtml(String headlineEmojiTitle, String plainBody) {
        String esc = TelegramSender.escapeHtml(plainBody == null ? "" : plainBody);
        String head = "<b>" + TelegramSender.escapeHtml(headlineEmojiTitle) + "</b>";
        return head + "\n\n" + esc;
    }

    private String ensureSource(String message) {
        String clean = message == null ? "" : message.trim();
        clean = clean.replace("來源：Codex + Claude", "來源：Trading System");
        clean = clean.replace("來源：Codex", "來源：Trading System");
        clean = clean.replace("來源：Claude", "來源：Trading System");
        if (clean.contains("來源：Trading System")) return clean;
        return clean + "\n來源：Trading System";
    }
}
