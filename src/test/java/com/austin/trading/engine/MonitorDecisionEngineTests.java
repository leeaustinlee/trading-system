package com.austin.trading.engine;

import com.austin.trading.dto.request.MonitorEvaluateRequest;
import com.austin.trading.dto.response.MonitorDecisionResponse;
import com.austin.trading.service.ScoreConfigService;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MonitorDecisionEngineTests {

    private final MonitorDecisionEngine engine = new MonitorDecisionEngine();

    @Test
    void shouldTurnOffWhenLockedAndNoPosition() {
        MonitorDecisionResponse result = engine.evaluate(new MonitorEvaluateRequest(
                "B",
                "WATCH",
                "高檔震盪期",
                "WATCH",
                "NONE",
                LocalTime.of(11, 0),
                false,
                true,
                false,
                "LOCKED",
                "MID"
        ));

        assertEquals("OFF", result.monitorMode());
        assertFalse(result.shouldNotify());
    }

    @Test
    void shouldBeActiveWhenGradeAWithCriticalEvent() {
        MonitorDecisionResponse result = engine.evaluate(new MonitorEvaluateRequest(
                "A",
                "ENTER",
                "主升發動期",
                "WATCH",
                "NONE",
                LocalTime.of(9, 30),
                false,
                true,
                true,
                "NONE",
                "EARLY"
        ));

        assertEquals("ACTIVE", result.monitorMode());
        assertTrue(result.shouldNotify());
    }

    // ── v2.15 swing-friendly cooldown ─────────────────────────────────────

    /** Helper：建立啟用 swing-cooldown flag 的引擎。 */
    private MonitorDecisionEngine swingEnabled() {
        ScoreConfigService cfg = mock(ScoreConfigService.class);
        when(cfg.getBoolean(eq("monitor.swing-cooldown.enabled"), anyBoolean())).thenReturn(true);
        return new MonitorDecisionEngine(cfg);
    }

    /** Helper：建立 disable 的引擎（驗證 default 行為）。 */
    private MonitorDecisionEngine swingDisabled() {
        ScoreConfigService cfg = mock(ScoreConfigService.class);
        when(cfg.getBoolean(eq("monitor.swing-cooldown.enabled"), anyBoolean())).thenReturn(false);
        return new MonitorDecisionEngine(cfg);
    }

    /** 11:30 + B 級 + LATE + 空手：swing-cooldown OFF → 仍然 OFF（舊行為）。 */
    @Test
    void swingCooldown_disabled_keepsLegacyOffAt1130() {
        MonitorDecisionResponse r = swingDisabled().evaluate(new MonitorEvaluateRequest(
                "B", "WAIT", "高檔震盪期", "WATCH", "NONE",
                LocalTime.of(11, 30), false, true, false, "NONE", "LATE"));
        assertEquals("OFF", r.monitorMode());
    }

    /** 11:30 + B 級 + LATE + 空手：swing-cooldown ON → 救回 WATCH。 */
    @Test
    void swingCooldown_enabled_savesBgradeToWatchAt1130() {
        MonitorDecisionResponse r = swingEnabled().evaluate(new MonitorEvaluateRequest(
                "B", "WAIT", "高檔震盪期", "WATCH", "NONE",
                LocalTime.of(11, 30), false, true, false, "NONE", "LATE"));
        assertEquals("WATCH", r.monitorMode());
    }

    /** 11:30 + A 級：本來就 ACTIVE，flag 不影響。 */
    @Test
    void swingCooldown_aGradeIntactAt1130() {
        MonitorDecisionResponse r = swingEnabled().evaluate(new MonitorEvaluateRequest(
                "A", "ENTER", "主升發動期", "WATCH", "NONE",
                LocalTime.of(11, 30), true, true, true, "NONE", "LATE"));
        assertEquals("ACTIVE", r.monitorMode());
    }

    /** 14:00（13:00 後）+ B 級 + 空手：swing band 已過 → 仍 OFF。 */
    @Test
    void swingCooldown_after1300_revertsToOff() {
        MonitorDecisionResponse r = swingEnabled().evaluate(new MonitorEvaluateRequest(
                "B", "WAIT", "高檔震盪期", "WATCH", "NONE",
                LocalTime.of(14, 0), false, true, false, "NONE", "LATE"));
        assertEquals("OFF", r.monitorMode());
    }

    /** 11:30 + LOCKED：swing-cooldown 不該救起來，仍 OFF。 */
    @Test
    void swingCooldown_respectsDecisionLock() {
        MonitorDecisionResponse r = swingEnabled().evaluate(new MonitorEvaluateRequest(
                "B", "WAIT", "高檔震盪期", "WATCH", "NONE",
                LocalTime.of(11, 30), false, true, false, "LOCKED", "LATE"));
        assertEquals("OFF", r.monitorMode());
    }

    /** 11:30 + C 級：C 級永遠 OFF，flag 不該救。 */
    @Test
    void swingCooldown_cGradeStaysOff() {
        MonitorDecisionResponse r = swingEnabled().evaluate(new MonitorEvaluateRequest(
                "C", "REST", "出貨期", "OFF", "NONE",
                LocalTime.of(11, 30), false, false, false, "NONE", "LATE"));
        assertEquals("OFF", r.monitorMode());
    }

    /** 10:30 + B 級：尚未進入 swing band（11:00 才開始）→ 預期照舊（依其他規則）。 */
    @Test
    void swingCooldown_outsideBandUsesLegacy() {
        MonitorDecisionResponse r = swingEnabled().evaluate(new MonitorEvaluateRequest(
                "B", "WAIT", "高檔震盪期", "WATCH", "NONE",
                LocalTime.of(10, 30), false, true, false, "NONE", "LATE"));
        // 10:30 在 swing band 外 + LATE + B + 空手 → legacy OFF
        assertEquals("OFF", r.monitorMode());
    }
}
