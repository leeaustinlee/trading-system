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

@Service
public class LineTemplateService {

    public static final String SOURCE = "Trading System";
    private static final Logger log = LoggerFactory.getLogger(LineTemplateService.class);

    private final LineSender lineSender;
    private final NotificationService notificationService;

    public LineTemplateService(LineSender lineSender, NotificationService notificationService) {
        this.lineSender = lineSender;
        this.notificationService = notificationService;
    }

    public void notifyPremarket(String marketSummary, String topCandidates, LocalDate date) {
        sendAndLog("PREMARKET_0830", "盤前分析 " + date,
                LineMessageBuilder.buildPremarket(marketSummary, topCandidates, date), null);
    }

    public void notifyFinalDecision(FinalDecisionResponse decision, LocalDate date) {
        sendAndLog("FINAL_DECISION_0930", "09:30 今日操作 " + date,
                LineMessageBuilder.buildFinalDecision(decision, date), null);
    }

    public void notifyHourlyGate(HourlyGateDecisionResponse decision, LocalTime time) {
        if (!decision.shouldNotify()) {
            log.debug("[LineTemplateService] HourlyGate shouldNotify=false, skip LINE.");
            return;
        }
        sendAndLog("HOURLY_GATE", "盤中每小時監控 " + time,
                LineMessageBuilder.buildHourlyGate(decision, time), Duration.ofMinutes(30));
    }

    public void notifyMonitor(MonitorDecisionResponse decision, LocalTime time) {
        if (!decision.shouldNotify()) return;
        String msg = LineMessageBuilder.buildMonitor(decision, time);
        if (msg != null) {
            sendAndLog("MONITOR_5M", "5分鐘事件監控 " + decision.triggerEvent(),
                    msg, Duration.ofMinutes(15));
        }
    }

    public void notifyReview1400(String reviewSummary, LocalDate date) {
        sendAndLog("REVIEW_1400", "今日操作檢討 " + date,
                LineMessageBuilder.buildReview1400(reviewSummary, date), null);
    }

    public void notifyMidday(String message, LocalDate date) {
        sendAndLog("MIDDAY_1100", "盤中分析 " + date, ensureSource(message), null);
    }

    public void notifyTomorrowPlan(String message, LocalDate date) {
        sendAndLog("TOMORROW_PLAN_1800", "明日計畫 " + date, ensureSource(message), null);
    }

    public void notifyPostmarket(String candidates, LocalDate date) {
        sendAndLog("POSTMARKET_1530", "盤後選股 " + date,
                LineMessageBuilder.buildPostmarket(candidates, date), null);
    }

    public void notifySystemAlert(String title, String message) {
        sendAndLog("SYSTEM_ALERT", title, ensureSource(message), Duration.ofMinutes(30));
    }

    public void notifyPositionAlert(String symbol, String status, String reason,
                                     Double currentPrice, Double entryPrice, Double pnlPct) {
        if (currentPrice == null) {
            log.warn("[LineTemplateService] Skip position alert because currentPrice is null: symbol={}, status={}",
                    symbol, status);
            return;
        }
        String message = LineMessageBuilder.buildPositionAlert(symbol, status, reason, currentPrice, entryPrice, pnlPct);
        sendAndLog("POSITION_ALERT", "持倉警報 " + symbol + " " + status, message, Duration.ofMinutes(120));
    }

    private void sendAndLog(String type, String title, String message, Duration cooldown) {
        if (cooldown != null) {
            LocalDateTime after = LocalDateTime.now().minus(cooldown);
            boolean duplicate = notificationService.existsRecent(type, title, after);
            log.debug("[LineTemplateService] cooldown check type={} title={} after={} duplicate={}",
                    type, title, after, duplicate);
            if (duplicate) {
                log.info("[LineTemplateService] Skip duplicate LINE type={} title={} cooldown={}m",
                        type, title, cooldown.toMinutes());
                return;
            }
        }
        lineSender.send(message);
        notificationService.create(new NotificationCreateRequest(
                LocalDateTime.now(),
                type,
                SOURCE,
                title,
                message,
                null
        ));
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
