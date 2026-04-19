package com.austin.trading.service;

import com.austin.trading.entity.DailyOrchestrationStatusEntity;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.lang.reflect.Method;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;

/**
 * 啟動健康檢查。
 * <ul>
 *     <li>將最近 2 天內超過 30 分鐘仍為 RUNNING 的 step 標記為 FAILED（可能是上次 JVM 崩掉留下的殘影）。</li>
 *     <li>若昨天是交易日且仍有 PENDING/FAILED 的 step，log.warn 提示用戶手動補跑。</li>
 * </ul>
 *
 * <p>這是「軟」的提醒 — 不會自動重跑，也不會阻擋啟動，只幫用戶快速發現異常。</p>
 */
@Service
public class StartupHealthCheckService {

    private static final Logger log = LoggerFactory.getLogger(StartupHealthCheckService.class);

    /** 比 markRunning 的 stale 閾值（15 min）更寬鬆，啟動時才掃一次。 */
    private static final int STARTUP_STALE_MINUTES = 30;
    private static final int CHECK_RECENT_DAYS = 2;

    private final DailyOrchestrationService orchestrationService;

    public StartupHealthCheckService(DailyOrchestrationService orchestrationService) {
        this.orchestrationService = orchestrationService;
    }

    @PostConstruct
    public void runAtStartup() {
        try {
            // 1. 修正停滯的 RUNNING
            List<String> fixed = orchestrationService.sweepStaleRunning(STARTUP_STALE_MINUTES, CHECK_RECENT_DAYS);
            if (!fixed.isEmpty()) {
                log.warn("[StartupHealthCheck] 偵測並修正 {} 筆卡死的 RUNNING step: {}",
                        fixed.size(), fixed);
            }

            // 2. 若昨天是交易日（MON-FRI），檢查是否仍有未完成步驟
            LocalDate yesterday = LocalDate.now().minusDays(1);
            if (isTradingDay(yesterday)) {
                DailyOrchestrationStatusEntity y = orchestrationService.getByDate(yesterday);
                if (y == null) {
                    log.warn("[StartupHealthCheck] 昨日 ({}) 為交易日，但無任何 orchestration 紀錄。", yesterday);
                } else {
                    List<String> outstanding = listNonDoneFields(y);
                    if (!outstanding.isEmpty()) {
                        log.warn("[StartupHealthCheck] 昨日 ({}) 仍有 {} 個步驟非 DONE: {}",
                                yesterday, outstanding.size(), outstanding);
                    }
                }
            }
        } catch (Exception e) {
            // 絕對不阻擋啟動：僅 log
            log.warn("[StartupHealthCheck] 啟動檢查失敗: {}", e.getMessage());
        }
    }

    private static boolean isTradingDay(LocalDate date) {
        DayOfWeek dow = date.getDayOfWeek();
        return dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY;
    }

    private List<String> listNonDoneFields(DailyOrchestrationStatusEntity entity) {
        List<String> out = new java.util.ArrayList<>();
        for (OrchestrationStep s : OrchestrationStep.values()) {
            String v = readField(entity, s);
            if (v == null || !DailyOrchestrationService.STATUS_DONE.equalsIgnoreCase(v)) {
                out.add(s.entityField() + "=" + (v == null ? "PENDING" : v));
            }
        }
        return out;
    }

    private static String readField(DailyOrchestrationStatusEntity entity, OrchestrationStep step) {
        try {
            String getter = "get" + Character.toUpperCase(step.entityField().charAt(0))
                    + step.entityField().substring(1);
            Method m = DailyOrchestrationStatusEntity.class.getMethod(getter);
            Object v = m.invoke(entity);
            return v == null ? null : v.toString();
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }
}
