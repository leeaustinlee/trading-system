package com.austin.trading.engine;

import com.austin.trading.dto.request.MonitorEvaluateRequest;
import com.austin.trading.dto.response.MonitorDecisionResponse;
import com.austin.trading.service.ScoreConfigService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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

    // ── v2.16 TRADING_DISABLED kill switch ──────────────────────────────────

    @Test
    void killSwitch_disabled_returnsOff() {
        ScoreConfigService cfg = mock(ScoreConfigService.class);
        when(cfg.getBoolean(eq("trading.status.allow_trade"), anyBoolean())).thenReturn(false);
        MonitorDecisionEngine eng = new MonitorDecisionEngine(cfg);

        // 即使 A 級 + critical event，也應 OFF
        MonitorDecisionResponse r = eng.evaluate(new MonitorEvaluateRequest(
                "A", "ENTER", "主升發動期", "ACTIVE", "NONE",
                LocalTime.of(9, 30), true, true, true, "NONE", "EARLY"));
        assertEquals("OFF", r.monitorMode());
        assertFalse(r.shouldNotify());
        assertTrue(r.summaryForLog() != null && r.summaryForLog().contains("TRADING_DISABLED"),
                "summary 應含 TRADING_DISABLED：" + r.summaryForLog());
    }

    // ── v2.15 swing-friendly cooldown ─────────────────────────────────────

    /** Helper：建立啟用 swing-cooldown flag 的引擎（kill switch 預設 true）。 */
    private MonitorDecisionEngine swingEnabled() {
        ScoreConfigService cfg = mock(ScoreConfigService.class);
        when(cfg.getBoolean(eq("monitor.swing-cooldown.enabled"), anyBoolean())).thenReturn(true);
        when(cfg.getBoolean(eq("trading.status.allow_trade"), anyBoolean())).thenReturn(true);
        when(cfg.getDecimal(eq("monitor.swing-cooldown.b_grade_distance_pct"), any()))
                .thenReturn(new BigDecimal("0.01"));
        return new MonitorDecisionEngine(cfg);
    }

    /** Helper：建立 disable 的引擎（驗證 default 行為；kill switch 預設 true）。 */
    private MonitorDecisionEngine swingDisabled() {
        ScoreConfigService cfg = mock(ScoreConfigService.class);
        when(cfg.getBoolean(eq("monitor.swing-cooldown.enabled"), anyBoolean())).thenReturn(false);
        when(cfg.getBoolean(eq("trading.status.allow_trade"), anyBoolean())).thenReturn(true);
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

    // ── v2.16 B 級接近進場下緣補進 ────────────────────────────────────────

    /** 11:30 + B 級 + currentPrice 在 entryZoneLowerBound 1% 內 → 升級 ACTIVE + summary 標 SELECT_BUY_NOW。 */
    @Test
    void swingCooldown_bGradeNearEntryLower_upgradesToActive() {
        MonitorDecisionResponse r = swingEnabled().evaluate(new MonitorEvaluateRequest(
                "B", "WAIT", "高檔震盪期", "WATCH", "NONE",
                LocalTime.of(11, 30), false, true, false, "NONE", "LATE",
                new BigDecimal("100.5"), new BigDecimal("100.0")));
        // (100.5 - 100)/100 = 0.005 < 1% → ACTIVE
        assertEquals("ACTIVE", r.monitorMode());
        assertTrue(r.summaryForLog().contains("SELECT_BUY_NOW"),
                "summary 應含 SELECT_BUY_NOW：" + r.summaryForLog());
    }

    /** 11:30 + B 級 + currentPrice 距離下緣 > 1% → 仍 WATCH（不升級）。 */
    @Test
    void swingCooldown_bGradeFarFromEntry_staysWatch() {
        MonitorDecisionResponse r = swingEnabled().evaluate(new MonitorEvaluateRequest(
                "B", "WAIT", "高檔震盪期", "WATCH", "NONE",
                LocalTime.of(11, 30), false, true, false, "NONE", "LATE",
                new BigDecimal("105.0"), new BigDecimal("100.0")));
        // (105 - 100)/100 = 0.05 → 5% > 1% threshold → 不升級
        assertEquals("WATCH", r.monitorMode());
        assertFalse(r.summaryForLog().contains("SELECT_BUY_NOW"));
    }

    /** 11:30 + A 級 + currentPrice 接近下緣 → A 級不走 B 級補進邏輯，保持 WATCH（與 v2.15 一致）。 */
    @Test
    void swingCooldown_aGradeUnchangedRegardlessOfDistance() {
        MonitorDecisionResponse r = swingEnabled().evaluate(new MonitorEvaluateRequest(
                "A", "WAIT", "高檔震盪期", "WATCH", "NONE",
                LocalTime.of(11, 30), false, true, false, "NONE", "LATE",
                new BigDecimal("100.5"), new BigDecimal("100.0")));
        // A 級在這個 path（空手）跑舊 LATE→OFF 路徑後 swing-cooldown 救回 → WATCH（不會 ACTIVE）
        assertEquals("WATCH", r.monitorMode());
    }

    /** 11:30 + B 級但 currentPrice 已跌破下緣 → 不算「接近」，保持 WATCH。 */
    @Test
    void swingCooldown_bGradeBelowEntryLower_staysWatch() {
        MonitorDecisionResponse r = swingEnabled().evaluate(new MonitorEvaluateRequest(
                "B", "WAIT", "高檔震盪期", "WATCH", "NONE",
                LocalTime.of(11, 30), false, true, false, "NONE", "LATE",
                new BigDecimal("99.5"), new BigDecimal("100.0")));
        // currentPrice < lower → 不算接近
        assertEquals("WATCH", r.monitorMode());
    }
}
