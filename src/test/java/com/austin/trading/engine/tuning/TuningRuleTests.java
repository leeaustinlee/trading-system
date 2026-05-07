package com.austin.trading.engine.tuning;

import com.austin.trading.domain.enums.TuningConfidence;
import com.austin.trading.entity.CandidateForwardTrackingEntity;
import com.austin.trading.entity.MissedRallyTrackingEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TuningRuleTests {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TuningConfidenceCalculator calculator = new TuningConfidenceCalculator();
    private final LocalDate today = LocalDate.of(2026, 5, 7);

    @Test
    void rejectRally_sampleEnoughAndHighMissedRally_generatesGateRelaxRecommendation() {
        var rule = new RejectRallyTuningRule(calculator, objectMapper);
        var recs = rule.evaluate(today, 20, List.of(), missedRows(25, 10, "REJECT", "near_day_high", "BREAKOUT"));
        assertThat(recs).hasSize(1);
        assertThat(recs.get(0).getTargetParameter()).isEqualTo("gate.near_day_high_reject_threshold");
        assertThat(recs.get(0).getStatus().name()).isEqualTo("PENDING");
    }

    @Test
    void rejectRally_sampleTooSmall_doesNotGenerateAggressiveRecommendation() {
        var rule = new RejectRallyTuningRule(calculator, objectMapper);
        assertThat(rule.evaluate(today, 20, List.of(), missedRows(10, 10, "REJECT", "near_day_high", "BREAKOUT"))).isEmpty();
    }

    @Test
    void enterWeakPerformance_generatesThresholdOrPositionRecommendation() {
        var rule = new EnterWeakPerformanceRule(calculator, objectMapper);
        var recs = rule.evaluate(today, 20, candidateRows(25, "ENTER", "BREAKOUT",
                new BigDecimal("-1.0"), new BigDecimal("3.0"), new BigDecimal("-5.0"), 8), List.of());
        assertThat(recs).hasSize(1);
        assertThat(recs.get(0).getTargetParameter()).isIn("scoring.enter_min_score", "risk.max_position_size_pct");
    }

    @Test
    void watchOutperform_generatesEnterSmallRecommendation() {
        var rule = new WatchOutperformRule(calculator, objectMapper);
        List<CandidateForwardTrackingEntity> rows = new ArrayList<>();
        rows.addAll(candidateRows(25, "WATCH", "BREAKOUT", new BigDecimal("6.0"), new BigDecimal("8.0"), new BigDecimal("-2.0"), 20));
        rows.addAll(candidateRows(25, "ENTER", "BREAKOUT", new BigDecimal("1.0"), new BigDecimal("3.0"), new BigDecimal("-2.0"), 8));
        var recs = rule.evaluate(today, 20, rows, List.of());
        assertThat(recs).hasSize(1);
        assertThat(recs.get(0).getRecommendationType().name()).isEqualTo("ENTER_SMALL");
    }

    @Test
    void breakoutMissedRally_highRate_generatesNearHighRecommendation() {
        var rule = new BreakoutMissedRallyRule(calculator, objectMapper);
        var recs = rule.evaluate(today, 20, List.of(), missedRows(25, 10, "REJECT", "near_day_high", "BREAKOUT"));
        assertThat(recs).hasSize(1);
        assertThat(recs.get(0).getTargetParameter()).isEqualTo("breakout.near_high_allowed");
    }

    @Test
    void confidenceCalculator_returnsExpectedLevels() {
        assertThat(calculator.calculate(10, new BigDecimal("20"))).isEqualTo(TuningConfidence.INSUFFICIENT_DATA);
        assertThat(calculator.calculate(45, new BigDecimal("9"))).isEqualTo(TuningConfidence.HIGH);
        assertThat(calculator.calculate(35, new BigDecimal("6"))).isEqualTo(TuningConfidence.MEDIUM);
        assertThat(calculator.calculate(20, new BigDecimal("2"))).isEqualTo(TuningConfidence.LOW);
    }

    private List<MissedRallyTrackingEntity> missedRows(int count, int missed, String decision, String gate, String strategy) {
        List<MissedRallyTrackingEntity> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            MissedRallyTrackingEntity e = new MissedRallyTrackingEntity();
            e.setTradingDate(today.minusDays(i + 6L));
            e.setOriginalDecision(decision);
            e.setPrimaryStrategy(strategy);
            e.setGateName(gate);
            e.setMaxReturnPct(new BigDecimal("8.0"));
            e.setMfePct(new BigDecimal("8.0"));
            e.setMaePct(new BigDecimal("-2.0"));
            e.setCloseReturnPct(new BigDecimal("4.0"));
            e.setMissedRallyFlag(i < missed);
            rows.add(e);
        }
        return rows;
    }

    private List<CandidateForwardTrackingEntity> candidateRows(int count, String decision, String strategy,
            BigDecimal t5Return, BigDecimal mfe, BigDecimal mae, int winners) {
        List<CandidateForwardTrackingEntity> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            CandidateForwardTrackingEntity e = new CandidateForwardTrackingEntity();
            e.setTradingDate(today.minusDays(i + 6L));
            e.setFinalDecision(decision);
            e.setPrimaryStrategy(strategy);
            e.setT5CloseReturnPct(i < winners ? t5Return.abs() : t5Return.abs().negate());
            e.setT3CloseReturnPct(t5Return);
            e.setMfePct(mfe);
            e.setMaePct(mae);
            e.setRelativeReturnPct(t5Return);
            rows.add(e);
        }
        return rows;
    }
}
