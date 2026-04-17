package com.austin.trading.notify;

import com.austin.trading.dto.request.NotificationCreateRequest;
import com.austin.trading.dto.response.FinalDecisionResponse;
import com.austin.trading.dto.response.HourlyGateDecisionResponse;
import com.austin.trading.dto.response.MonitorDecisionResponse;
import com.austin.trading.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 高階通知門面。
 * 負責：生成 LINE 訊息文字 → 發送 LINE → 寫入 notification_log。
 * 呼叫方只需傳入決策結果，不需處理格式與發送細節。
 */
@Service
public class LineTemplateService {

    private static final Logger log = LoggerFactory.getLogger(LineTemplateService.class);

    private final LineSender lineSender;
    private final NotificationService notificationService;

    public LineTemplateService(LineSender lineSender, NotificationService notificationService) {
        this.lineSender = lineSender;
        this.notificationService = notificationService;
    }

    public void notifyPremarket(String marketSummary, String topCandidates, LocalDate date) {
        String msg = LineMessageBuilder.buildPremarket(marketSummary, topCandidates, date);
        sendAndLog("PREMARKET_0830", "08:30 盤前通知", msg);
    }

    public void notifyFinalDecision(FinalDecisionResponse decision, LocalDate date) {
        String msg = LineMessageBuilder.buildFinalDecision(decision, date);
        sendAndLog("FINAL_DECISION_0930", "09:30 最終決策", msg);
    }

    public void notifyHourlyGate(HourlyGateDecisionResponse decision, LocalTime time) {
        if (!decision.shouldNotify()) {
            log.debug("[LineTemplateService] HourlyGate shouldNotify=false, skip LINE.");
            return;
        }
        String msg = LineMessageBuilder.buildHourlyGate(decision, time);
        sendAndLog("HOURLY_GATE", "整點行情閘 " + time, msg);
    }

    public void notifyMonitor(MonitorDecisionResponse decision, LocalTime time) {
        if (!decision.shouldNotify()) return;
        String msg = LineMessageBuilder.buildMonitor(decision, time);
        if (msg != null) {
            sendAndLog("MONITOR_5M", "盤中監控 " + time, msg);
        }
    }

    public void notifyReview1400(String reviewSummary, LocalDate date) {
        String msg = LineMessageBuilder.buildReview1400(reviewSummary, date);
        sendAndLog("REVIEW_1400", "14:00 交易檢討", msg);
    }

    public void notifyMidday(String message, LocalDate date) {
        sendAndLog("MIDDAY_1100", "11:00 盤中戰情 " + date, message);
    }

    public void notifyTomorrowPlan(String message, LocalDate date) {
        sendAndLog("TOMORROW_PLAN_1830", "18:30 明日計畫 " + date, message);
    }

    public void notifyPostmarket(String candidates, LocalDate date) {
        String msg = LineMessageBuilder.buildPostmarket(candidates, date);
        sendAndLog("POSTMARKET_1530", "15:30 盤後候選", msg);
    }

    public void notifySystemAlert(String title, String message) {
        sendAndLog("SYSTEM_ALERT", title, message);
    }

    // ── 私有方法 ───────────────────────────────────────────────────────────────

    private void sendAndLog(String type, String title, String message) {
        lineSender.send(message);
        notificationService.create(new NotificationCreateRequest(
                LocalDateTime.now(),
                type,
                "Codex",
                title,
                message,
                null
        ));
    }
}
