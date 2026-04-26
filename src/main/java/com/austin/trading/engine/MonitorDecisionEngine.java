package com.austin.trading.engine;

import com.austin.trading.dto.request.MonitorEvaluateRequest;
import com.austin.trading.dto.response.MonitorDecisionResponse;
import com.austin.trading.service.ScoreConfigService;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.util.Locale;

@Component
public class MonitorDecisionEngine {

    /** v2.15：swing-friendly cooldown 時間視窗。 */
    private static final LocalTime SWING_BAND_START = LocalTime.of(11, 0);
    private static final LocalTime SWING_BAND_END   = LocalTime.of(13, 0);

    private final ScoreConfigService scoreConfig;

    /** Spring DI 用：注入 ScoreConfigService 讀 feature flag。 */
    @org.springframework.beans.factory.annotation.Autowired
    public MonitorDecisionEngine(ScoreConfigService scoreConfig) {
        this.scoreConfig = scoreConfig;
    }

    /** 向下相容 ctor（無 ScoreConfigService）：feature flag 視為 false，跑舊邏輯。 */
    public MonitorDecisionEngine() {
        this.scoreConfig = null;
    }

    public MonitorDecisionResponse evaluate(MonitorEvaluateRequest request) {
        // v2.16：全域 kill switch — false 時 monitor 直接 OFF，不繼續評估
        if (scoreConfig != null && !scoreConfig.getBoolean("trading.status.allow_trade", true)) {
            String marketGrade = normalize(request.marketGrade());
            String decision = normalize(request.decision());
            String marketPhase = request.marketPhase() == null || request.marketPhase().isBlank()
                    ? defaultPhase(marketGrade) : request.marketPhase();
            return new MonitorDecisionResponse(
                    marketGrade, marketPhase, decision, "OFF",
                    false, "MARKET_DOWNGRADE",
                    "TRADING_DISABLED：kill switch 啟動，五分鐘監控暫停。",
                    "下一輪確認 trading.status.allow_trade 是否恢復 true。",
                    normalizeOrDefault(request.decisionLock(), "NONE"), 10,
                    normalizeOrDefault(request.previousEventType(), "NONE"),
                    normalizeOrDefault(request.timeDecayStage(), "EARLY"),
                    "TRADING_DISABLED kill_switch=true"
            );
        }

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

        // v2.15：swing-friendly cooldown — 11:00–13:00 期間，B 級非空手且非 LOCKED，從 OFF 救回 WATCH
        // v2.16 強化：B 級若 currentPrice 在 entryZoneLowerBound 的 b_grade_distance_pct 內 → 升級 SELECT_BUY_NOW
        boolean bgradeBuyNow = false;
        if (isSwingCooldownEnabled() && "OFF".equals(monitorMode)) {
            LocalTime t = request.evaluationTime() == null ? LocalTime.now() : request.evaluationTime();
            boolean inSwingBand = !t.isBefore(SWING_BAND_START) && t.isBefore(SWING_BAND_END);
            boolean swingFriendlyGrade = "A".equals(marketGrade) || "B".equals(marketGrade);
            boolean notHardLocked = !"LOCKED".equals(decisionLock);
            if (inSwingBand && swingFriendlyGrade && notHardLocked
                    && !"C".equals(marketGrade) && !"REST".equals(decision)) {
                // B 級檢查接近進場下緣：(currentPrice - lower) / lower ≤ b_grade_distance_pct
                if ("B".equals(marketGrade) && isCloseToEntryLower(request)) {
                    monitorMode = "ACTIVE";  // upgrade 到積極監控
                    bgradeBuyNow = true;     // signal triggerEvent / summary 帶 SELECT_BUY_NOW 語意
                } else {
                    monitorMode = "WATCH";
                }
            }
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
                + " stage=" + timeDecay
                + (bgradeBuyNow ? " event=SELECT_BUY_NOW(b_grade_near_entry)" : "");

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

    /** v2.15：feature flag — DEFAULT false 先 shadow。 */
    private boolean isSwingCooldownEnabled() {
        if (scoreConfig == null) return false;
        return scoreConfig.getBoolean("monitor.swing-cooldown.enabled", false);
    }

    /**
     * v2.16：判斷 currentPrice 是否在 entryZoneLowerBound 之上、且距離 ≤ b_grade_distance_pct。
     * 兩者皆 null 視為不近（保守，回 false）。
     */
    private boolean isCloseToEntryLower(MonitorEvaluateRequest req) {
        java.math.BigDecimal price = req.currentPrice();
        java.math.BigDecimal lower = req.entryZoneLowerBound();
        if (price == null || lower == null || lower.signum() <= 0) return false;
        if (price.compareTo(lower) < 0) return false; // 已跌破下緣，不算「接近」
        double pct = price.subtract(lower).doubleValue() / lower.doubleValue();
        double threshold = scoreConfig == null
                ? 0.01
                : scoreConfig.getDecimal("monitor.swing-cooldown.b_grade_distance_pct",
                        new java.math.BigDecimal("0.01")).doubleValue();
        return pct <= threshold;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeOrDefault(String value, String fallback) {
        String normalized = normalize(value);
        return normalized.isBlank() ? normalize(fallback) : normalized;
    }
}
