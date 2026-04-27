package com.austin.trading.scheduler;

import com.austin.trading.entity.AiTaskEntity;
import com.austin.trading.notify.NotificationFacade;
import com.austin.trading.repository.AiTaskRepository;
import com.austin.trading.service.ScoreConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;

/**
 * 每日 08:15 PREMARKET 健康警報。
 *
 * <p>用途:在 08:10 {@code PremarketDataPrepJob} 跑完 5 分鐘後,確認:
 * 今日 ai_task 應該已存在 task_type=PREMARKET 的 row。
 * 若沒有 → 服務在 08:10 不在線(沒有開機自啟動),立即發 Telegram/LINE 警報。</p>
 *
 * <p>設計理念:Austin 早上 08:15 沒收到警報 = 服務正常;收到警報 = 立刻知道
 * 服務出事,不用等到 09:30 才發現。</p>
 *
 * <p>cron: {@code 0 15 8 * * MON-FRI}(週末不發,避免假警報)。</p>
 */
@Component
@ConditionalOnProperty(prefix = "trading.scheduler.premarket-health-alert",
        name = "enabled", havingValue = "true", matchIfMissing = true)
public class PremarketHealthAlertJob {

    private static final Logger log = LoggerFactory.getLogger(PremarketHealthAlertJob.class);

    private final AiTaskRepository aiTaskRepository;
    private final NotificationFacade notificationFacade;
    private final ScoreConfigService scoreConfig;

    public PremarketHealthAlertJob(AiTaskRepository aiTaskRepository,
                                    NotificationFacade notificationFacade,
                                    ScoreConfigService scoreConfig) {
        this.aiTaskRepository = aiTaskRepository;
        this.notificationFacade = notificationFacade;
        this.scoreConfig = scoreConfig;
    }

    @Scheduled(cron = "${trading.scheduler.premarket-health-alert-cron:0 15 8 * * MON-FRI}",
               zone = "${trading.timezone:Asia/Taipei}")
    public void run() {
        LocalDate today = LocalDate.now();
        if (today.getDayOfWeek() == DayOfWeek.SATURDAY || today.getDayOfWeek() == DayOfWeek.SUNDAY) {
            log.debug("[PremarketHealthAlert] weekend, skip");
            return;
        }
        try {
            check(today);
        } catch (Exception e) {
            log.error("[PremarketHealthAlert] check failed: {}", e.getMessage(), e);
        }
    }

    /** 對外暴露給測試用。回傳 alert 內容,健康時為 null。 */
    public String check(LocalDate today) {
        // PREMARKET 在 ai_task 表是 targetSymbol IS NULL 的 batch task
        AiTaskEntity premarket = aiTaskRepository
                .findByTradingDateAndTaskTypeAndTargetSymbolIsNull(today, "PREMARKET")
                .orElse(null);

        if (premarket != null) {
            log.info("[PremarketHealthAlert] OK — PREMARKET task id={} status={}",
                    premarket.getId(), premarket.getStatus());
            return null;
        }

        String alert = "PREMARKET task 未建立 (08:10 PremarketDataPrepJob 應該已跑完)。"
                + "可能原因:\n"
                + "  • 服務 08:10 不在線(尚未設開機自啟動)\n"
                + "  • PremarketDataPrepJob 失敗\n"
                + "  • DB 連線異常\n"
                + "請檢查:`ps -ef | grep TradingApplication`、log、scheduler_execution_log。";

        log.warn("[PremarketHealthAlert] ALERT: {}", alert);

        boolean lineEnabled = scoreConfig.getBoolean("scheduling.line_notify_enabled", false);
        if (lineEnabled) {
            try {
                notificationFacade.notifySystemAlert(
                        "PREMARKET task 未建立 — 服務可能離線", alert);
            } catch (Exception e) {
                log.warn("[PremarketHealthAlert] notification failed: {}", e.getMessage());
            }
        } else {
            log.warn("[PremarketHealthAlert] line_notify_enabled=false,僅寫 log 不發通知");
        }
        return alert;
    }
}
