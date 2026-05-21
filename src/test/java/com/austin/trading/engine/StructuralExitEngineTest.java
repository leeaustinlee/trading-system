package com.austin.trading.engine;

import com.austin.trading.domain.enums.StructuralExitTier;
import com.austin.trading.dto.StructuralExitInput;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class StructuralExitEngineTest {

    private final StructuralExitEngine engine = new StructuralExitEngine();

    @Test
    void priceBreaksTrailingStopButStructureIsIntactShouldOnlyObserve() {
        StructuralExitResult result = engine.evaluate(new StructuralExitInput(
                "1582",
                bd("117.5"),
                bd("117.98"),
                80,
                "NEUTRAL",
                "RISING_VOLUME",
                "OUTPERFORM",
                "BULLISH",
                true));

        assertThat(result.structuralTier()).isIn(StructuralExitTier.HOLD, StructuralExitTier.OBSERVE_1D);
        assertThat(result.manualConfirmRequired()).isTrue();
        assertThat(result.autoSellEnabled()).isFalse();
        assertThat(result.reason()).contains("price_broken", "structure_intact");
    }

    @Test
    void ma10BreakWithVolumeBreakdownAndRsUnderperformShouldExitReview() {
        StructuralExitResult result = engine.evaluate(new StructuralExitInput(
                "2330",
                bd("950"),
                bd("970"),
                35,
                "MA10_BREAK",
                "VOLUME_BREAKDOWN",
                "UNDERPERFORM",
                "BEARISH",
                false));

        assertThat(result.structuralTier()).isEqualTo(StructuralExitTier.EXIT_REVIEW);
        assertThat(result.manualConfirmRequired()).isTrue();
        assertThat(result.autoSellEnabled()).isFalse();
    }

    private BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
