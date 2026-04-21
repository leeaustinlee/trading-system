package com.austin.trading.engine;

import com.austin.trading.dto.request.HourlyGateEvaluateRequest;
import com.austin.trading.dto.request.MonitorEvaluateRequest;
import com.austin.trading.dto.response.HourlyGateDecisionResponse;
import com.austin.trading.dto.response.MonitorDecisionResponse;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v2.4 驗收：decision_lock 在盤前/早盤不得被 stale state 污染。
 */
class StalePreventionTests {

    private final MonitorDecisionEngine monitorEngine = new MonitorDecisionEngine();
    private final HourlyGateEngine      gateEngine    = new HourlyGateEngine();

    /** 驗收 1：09:05 不得是 LATE（即使 request 帶入 "LATE" 也要被覆寫） */
    @Test
    void monitor_0905_mustBeEarly_evenIfRequestSaysLate() {
        MonitorEvaluateRequest req = new MonitorEvaluateRequest(
                "B", "WATCH", "高檔震盪期",
                "OFF", "NONE",
                LocalTime.of(9, 5),
                false, true, false,
                "NONE",
                "LATE"   // stale 輸入，應被忽略
        );
        MonitorDecisionResponse r = monitorEngine.evaluate(req);
        assertThat(r.timeDecayStage()).isEqualTo("EARLY");
    }

    /** 驗收 1b：10:05 必定 MID */
    @Test
    void monitor_1005_mustBeMid() {
        MonitorEvaluateRequest req = new MonitorEvaluateRequest(
                "B", "WATCH", null, "OFF", "NONE",
                LocalTime.of(10, 5), false, true, false, "NONE", null);
        assertThat(monitorEngine.evaluate(req).timeDecayStage()).isEqualTo("MID");
    }

    /** 驗收 1c：10:35 才可能 LATE */
    @Test
    void monitor_1035_isLate() {
        MonitorEvaluateRequest req = new MonitorEvaluateRequest(
                "B", "WATCH", null, "OFF", "NONE",
                LocalTime.of(10, 35), false, true, false, "NONE", null);
        assertThat(monitorEngine.evaluate(req).timeDecayStage()).isEqualTo("LATE");
    }

    /** 驗收 2：B + 盤前 REST 且有候選 → 不得直接 LOCKED */
    @Test
    void hourlyGate_0905_B_restWithCandidate_shouldNotLock() {
        HourlyGateEvaluateRequest req = new HourlyGateEvaluateRequest(
                "B", "REST",
                "B", "REST", "ON", "NONE", "NONE",
                LocalTime.of(9, 5),
                false, true, false  // hasPosition=false, hasCandidate=true
        );
        HourlyGateDecisionResponse r = gateEngine.evaluate(req);
        assertThat(r.decisionLock()).isNotEqualTo("LOCKED");
        assertThat(r.hourlyGate()).isNotEqualTo("OFF_HARD");
    }

    /** 驗收 2b：B + 早盤 + 無持倉無候選 也不鎖（最多 OFF_SOFT） */
    @Test
    void hourlyGate_early_B_noPositionNoCandidate_shouldNotLock() {
        HourlyGateEvaluateRequest req = new HourlyGateEvaluateRequest(
                "B", "WATCH",
                "B", "WATCH", "ON", "NONE", "NONE",
                LocalTime.of(9, 15),
                false, false, false
        );
        HourlyGateDecisionResponse r = gateEngine.evaluate(req);
        assertThat(r.decisionLock()).isEqualTo("NONE");
    }

    /** 驗收 3：C 市場 → 仍必須 LOCKED */
    @Test
    void hourlyGate_C_marketShouldLock() {
        HourlyGateEvaluateRequest req = new HourlyGateEvaluateRequest(
                "C", "REST",
                "B", "WATCH", "ON", "NONE", "NONE",
                LocalTime.of(9, 30),
                false, false, false
        );
        assertThat(gateEngine.evaluate(req).decisionLock()).isEqualTo("LOCKED");
    }

    /** 驗收 3b：10:35 後非 A 無持倉 → 仍必須 LOCKED (forceHardOffByTime) */
    @Test
    void hourlyGate_lateNonA_noPosition_shouldLock() {
        HourlyGateEvaluateRequest req = new HourlyGateEvaluateRequest(
                "B", "REST",
                "B", "WATCH", "ON", "NONE", "NONE",
                LocalTime.of(10, 45),
                false, true, false
        );
        assertThat(gateEngine.evaluate(req).decisionLock()).isEqualTo("LOCKED");
    }

    /** 驗收 3c：B + REST + 早盤 + 有持倉 → 不鎖（持倉需要監控） */
    @Test
    void hourlyGate_early_withPosition_shouldNotLock() {
        HourlyGateEvaluateRequest req = new HourlyGateEvaluateRequest(
                "B", "REST",
                "B", "REST", "ON", "NONE", "NONE",
                LocalTime.of(9, 5),
                true, true, false  // hasPosition=true
        );
        HourlyGateDecisionResponse r = gateEngine.evaluate(req);
        assertThat(r.decisionLock()).isNotEqualTo("LOCKED");
    }
}
