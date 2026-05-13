package com.austin.trading.engine;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KolThemeSignalEngineTests {

    private final KolThemeSignalEngine engine = new KolThemeSignalEngine();

    @Test
    void evidenceWeights_matchSpec() {
        assertThat(engine.evidenceWeight("DIRECT_CLAIM")).isEqualByComparingTo("1.0");
        assertThat(engine.evidenceWeight("REASONED_ANALYSIS")).isEqualByComparingTo("0.7");
        assertThat(engine.evidenceWeight("SECOND_HAND")).isEqualByComparingTo("0.4");
        assertThat(engine.evidenceWeight("UNKNOWN")).isEqualByComparingTo("0.2");
        assertThat(engine.evidenceWeight("RUMOR")).isEqualByComparingTo("0.1");
        assertThat(engine.evidenceWeight("PAID_PROMOTION")).isZero();
        assertThat(engine.evidenceWeight("SUSPECTED_PUMP")).isZero();
    }

    @Test
    void positiveBoost_isCappedAtPointTwo() {
        var result = engine.calculate("AI", "POSITIVE", List.of(
                input("a", "DIRECT_CLAIM"),
                input("b", "DIRECT_CLAIM")
        ));

        assertThat(result.netShadowBoost()).isLessThanOrEqualTo(new BigDecimal("0.2000"));
        assertThat(result.netShadowBoost()).isEqualByComparingTo("0.2000");
    }

    @Test
    void negativeBoost_isCappedAtMinusPointThree() {
        var result = engine.calculate("AI", "NEGATIVE", List.of(
                input("a", "DIRECT_CLAIM"),
                input("b", "DIRECT_CLAIM")
        ));

        assertThat(result.netShadowBoost()).isGreaterThanOrEqualTo(new BigDecimal("-0.3000"));
        assertThat(result.netShadowBoost()).isEqualByComparingTo("-0.3000");
    }

    @Test
    void highCrowdingRisk_reducesPositiveBoost() {
        var low = engine.calculate("AI", "POSITIVE", List.of(input("a", "DIRECT_CLAIM")));
        var high = engine.calculate("AI", "POSITIVE", List.of(
                input("a", "DIRECT_CLAIM"),
                input("b", "DIRECT_CLAIM"),
                input("c", "DIRECT_CLAIM"),
                input("d", "DIRECT_CLAIM"),
                input("e", "DIRECT_CLAIM")
        ));

        assertThat(high.crowdingRisk()).isEqualTo("HIGH");
        assertThat(high.netShadowBoost()).isLessThan(low.netShadowBoost());
    }

    @Test
    void sameSourceAdditionalEvidence_isDiscounted() {
        var sameSource = engine.calculate("AI", "POSITIVE", List.of(
                input("a", "DIRECT_CLAIM"),
                input("a", "DIRECT_CLAIM"),
                input("a", "DIRECT_CLAIM")
        ));
        var distinctSources = engine.calculate("AI", "POSITIVE", List.of(
                input("a", "DIRECT_CLAIM"),
                input("b", "DIRECT_CLAIM"),
                input("c", "DIRECT_CLAIM")
        ));

        assertThat(sameSource.sourceCount()).isEqualTo(1);
        assertThat(distinctSources.sourceCount()).isEqualTo(3);
        assertThat(sameSource.positiveScore()).isLessThanOrEqualTo(distinctSources.positiveScore());
    }

    private KolThemeSignalEngine.EvidenceInput input(String source, String type) {
        return new KolThemeSignalEngine.EvidenceInput(source, type, "POSITIVE", BigDecimal.ONE, BigDecimal.ONE);
    }
}
