package com.austin.trading.engine;

import com.austin.trading.engine.StrategyRecommendationEngine.*;
import com.austin.trading.service.ScoreConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StrategyRecommendationEngineTests {

    private ScoreConfigService config;
    private StrategyRecommendationEngine engine;

    @BeforeEach
    void setUp() {
        config = mock(ScoreConfigService.class);
        when(config.getInt(anyString(), anyInt())).thenAnswer(i -> i.getArgument(1));
        when(config.getDecimal(anyString(), any(BigDecimal.class))).thenAnswer(i -> i.getArgument(1));
        engine = new StrategyRecommendationEngine(config);
    }

    @Test
    void tooFewTrades_shouldReturnInfoOnly() {
        var stats = buildStats(5, Map.of(), Map.of()); // only 5 trades < 10 min
        var recs = engine.analyze(stats);
        assertThat(recs).hasSize(1);
        assertThat(recs.get(0).recommendationType()).isEqualTo("INFO");
        assertThat(recs.get(0).confidenceLevel()).isEqualTo("LOW");
    }

    @Test
    void highChasedRatio_shouldRecommendExtendedFilter() {
        Map<String, Integer> counts = new HashMap<>();
        counts.put("CHASED_TOO_HIGH", 5);  // 5/20 = 25% > 15%
        var stats = buildStats(20, counts, Map.of());
        var recs = engine.analyze(stats);
        assertThat(recs).anyMatch(r -> r.targetKey().contains("extended"));
    }

    @Test
    void consecutiveLossHigh_shouldRecommendTighterCooldown() {
        var stats = new AggregatedStats(
                new BigDecimal("45"), new BigDecimal("1.5"), 20,
                Map.of(), Map.of(), Map.of(),
                new BigDecimal("5.0"), new BigDecimal("-3.0"), new BigDecimal("6.0"),
                5,  // 5 consecutive losses
                Map.of("trading.cooldown.consecutive_loss_max", "3"));
        var recs = engine.analyze(stats);
        assertThat(recs).anyMatch(r ->
                "trading.cooldown.consecutive_loss_max".equals(r.targetKey())
                        && "HIGH".equals(r.confidenceLevel()));
    }

    @Test
    void sampleSizeProtection_lowCountTag_shouldBeLowConfidence() {
        // min_sample = 10, but tag only has 5 entries (< 10)
        Map<String, Integer> counts = new HashMap<>();
        counts.put("HELD_TOO_LONG", 5);  // 5/15 = 33% > 20%, but count < minSample
        var stats = buildStats(15, counts, Map.of());
        var recs = engine.analyze(stats);
        var heldRec = recs.stream()
                .filter(r -> "position.review.max_holding_days".equals(r.targetKey())).findFirst();
        if (heldRec.isPresent()) {
            assertThat(heldRec.get().confidenceLevel()).isEqualTo("LOW");
        }
    }

    // ── Helper ─────────────────────────────────────────────────────────────

    private AggregatedStats buildStats(int total, Map<String, Integer> countByTag,
                                        Map<String, BigDecimal> winRateByTag) {
        return new AggregatedStats(
                new BigDecimal("55"), new BigDecimal("2.0"), total,
                winRateByTag, Map.of(), countByTag,
                new BigDecimal("5.0"), new BigDecimal("-2.0"), new BigDecimal("5.0"),
                2,
                Map.of("position.review.max_holding_days", "15",
                       "position.trailing.first_trail_pct", "5.0",
                       "scoring.grade_ap_min", "8.8"));
    }
}
