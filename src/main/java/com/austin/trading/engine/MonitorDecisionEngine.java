package com.austin.trading.engine;

import com.austin.trading.dto.request.MonitorEvaluateRequest;
import com.austin.trading.dto.response.MonitorDecisionResponse;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.util.Locale;

@Component
public class MonitorDecisionEngine {

    public MonitorDecisionResponse evaluate(MonitorEvaluateRequest request) {
        String marketGrade = normalize(request.marketGrade());
        String decision = normalize(request.decision());
        // v2.4：以 evaluationTime 為準重新計算 timeDecay，不盲信 request 的 stale 輸入
        //       09:05 必定 EARLY，避免昨日 LATE 跨日污染
        String timeDecay = resolveTimeDecay(request.evaluationTime());
        String decisionLock = normalizeOrDefault(request.decisionLock(), "NONE");

        String monitorMode;
        if ("LOCKED".equals(decisionLock) && !request.hasPosition()) {
            monitorMode = "OFF";
        } else if ("C".equals(marketGrade) || "REST".equals(decision)) {
            monitorMode = "OFF";
        } else if ("A".equals(marketGrade) && (request.hasPosition() || request.hasCriticalEvent())) {
            monitorMode = "ACTIVE";
        } else if (request.hasPosition() || request.hasCandidate()) {
            monitorMode = "WATCH";
        } else {
            monitorMode = "OFF";
        }

        if ("LATE".equals(timeDecay) && !request.hasPosition() && !"A".equals(marketGrade)) {
            monitorMode = "OFF";
        }

        String triggerEvent = resolveTriggerEvent(request, marketGrade, monitorMode);
        boolean shouldNotify = shouldNotify(request, monitorMode, triggerEvent);

        String marketPhase = request.marketPhase() == null || request.marketPhase().isBlank()
                ? defaultPhase(marketGrade)
                : request.marketPhase();

        String reason = switch (monitorMode) {
            case "ACTIVE" -> "市場與持倉條件允許積極監控。";
            case "WATCH" -> "維持觀察，等待更明確進場或管理訊號。";
            default -> "市場或時間條件不利，關閉五分鐘監控。";
        };

        String nextFocus = "OFF".equals(monitorMode)
                ? "下一輪確認是否出現市場升級或新主流。"
                : "下一輪關注突破/停損/停利關鍵事件。";

        String summary = "grade=" + marketGrade
                + " mode=" + monitorMode
                + " decision=" + decision
                + " trigger=" + triggerEvent
                + " notify=" + shouldNotify
                + " lock=" + decisionLock
                + " stage=" + timeDecay;

        return new MonitorDecisionResponse(
                marketGrade,
                marketPhase,
                decision,
                monitorMode,
                shouldNotify,
                triggerEvent,
                reason,
                nextFocus,
                decisionLock,
                10,
                normalizeOrDefault(request.previousEventType(), "NONE"),
                timeDecay,
                summary
        );
    }

    private boolean shouldNotify(MonitorEvaluateRequest request, String monitorMode, String triggerEvent) {
        if ("OFF".equals(monitorMode)) {
            return false;
        }
        return !normalizeOrDefault(request.previousMonitorMode(), monitorMode).equals(monitorMode)
                || !"NONE".equals(triggerEvent);
    }

    private String resolveTriggerEvent(MonitorEvaluateRequest request, String marketGrade, String monitorMode) {
        String prevMode = normalizeOrDefault(request.previousMonitorMode(), monitorMode);
        if (!prevMode.equals(monitorMode) && "ACTIVE".equals(monitorMode)) {
            return "ENTRY_READY";
        }
        if (!prevMode.equals(monitorMode) && "OFF".equals(monitorMode)) {
            return "MARKET_DOWNGRADE";
        }
        if (request.hasCriticalEvent() && "ACTIVE".equals(monitorMode)) {
            return "POSITION_MANAGE";
        }
        if ("A".equals(marketGrade) && "WATCH".equals(prevMode) && "ACTIVE".equals(monitorMode)) {
            return "MARKET_UPGRADE";
        }
        return "NONE";
    }

    private String resolveTimeDecay(LocalTime time) {
        LocalTime now = time == null ? LocalTime.now() : time;
        if (now.isBefore(LocalTime.of(10, 0))) {
            return "EARLY";
        }
        if (now.isBefore(LocalTime.of(10, 30))) {
            return "MID";
        }
        return "LATE";
    }

    private String defaultPhase(String marketGrade) {
        return switch (marketGrade) {
            case "A" -> "主升發動期";
            case "B" -> "高檔震盪期";
            default -> "出貨 / 鈍化期";
        };
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeOrDefault(String value, String fallback) {
        String normalized = normalize(value);
        return normalized.isBlank() ? normalize(fallback) : normalized;
    }
}
