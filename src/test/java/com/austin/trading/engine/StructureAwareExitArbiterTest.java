package com.austin.trading.engine;

import com.austin.trading.domain.enums.PriceExitState;
import com.austin.trading.domain.enums.StructuralExitTier;
import com.austin.trading.domain.enums.StructureExitState;
import com.austin.trading.domain.enums.ThemeExitState;
import com.austin.trading.entity.PositionEntity;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class StructureAwareExitArbiterTest {

    private final StructureAwareExitArbiter arbiter = new StructureAwareExitArbiter(
            new ThemeExitLayer(), new StructureExitLayer(), new PriceExitLayer());

    @Test
    void priceOnlyStopDoesNotDirectlyExitWhenThemeAndStructureIntact() {
        StructureAwareExitDecision decision = arbiter.evaluate(input()
                .currentPrice(new BigDecimal("98"))
                .hardStopPrice(new BigDecimal("90"))
                .trailingStopPrice(new BigDecimal("100"))
                .themeStage("EXPANDING")
                .mainstreamTheme(true)
                .ma5(new BigDecimal("99"))
                .ma10(new BigDecimal("95"))
                .ma20(new BigDecimal("92"))
                .previousLow(new BigDecimal("94"))
                .relativeStrengthStatus("OUTPERFORM")
                .build());

        assertEquals(StructuralExitTier.OBSERVE_1D, decision.tier());
        assertEquals(PriceExitState.TRAILING_STOP_TOUCH, decision.priceState());
        assertNotEquals(StructuralExitTier.EXIT_REVIEW, decision.tier());
        assertTrue(decision.manualConfirmRequired());
        assertFalse(decision.autoSellEnabled());
    }

    @Test
    void hardStopIsPreservedEvenWhenThemeActive() {
        StructureAwareExitDecision decision = arbiter.evaluate(input()
                .currentPrice(new BigDecimal("88"))
                .hardStopPrice(new BigDecimal("90"))
                .trailingStopPrice(new BigDecimal("100"))
                .themeStage("EXPANDING")
                .mainstreamTheme(true)
                .ma10(new BigDecimal("95"))
                .ma20(new BigDecimal("92"))
                .previousLow(new BigDecimal("94"))
                .relativeStrengthStatus("OUTPERFORM")
                .build());

        assertEquals(StructuralExitTier.HARD_EXIT_ALERT, decision.tier());
        assertTrue(decision.riskBlock());
        assertEquals(PriceExitState.HARD_STOP_BREACH, decision.priceState());
    }

    @Test
    void themeActiveHealthyPullbackHoldsThesisWithoutPriceTrigger() {
        StructureAwareExitDecision decision = arbiter.evaluate(input()
                .currentPrice(new BigDecimal("105"))
                .hardStopPrice(new BigDecimal("90"))
                .trailingStopPrice(new BigDecimal("100"))
                .themeStage("MAINSTREAM")
                .mainstreamTheme(true)
                .ma5(new BigDecimal("104"))
                .ma10(new BigDecimal("100"))
                .ma20(new BigDecimal("95"))
                .previousLow(new BigDecimal("96"))
                .volumeStatus("NORMAL")
                .relativeStrengthStatus("OUTPERFORM")
                .build());

        assertEquals(StructuralExitTier.HOLD_THESIS, decision.tier());
        assertEquals(ThemeExitState.MAINSTREAM_STABLE, decision.themeState());
    }

    @Test
    void themeBrokenStructureBrokenAndPriceStopBecomesExitReview() {
        StructureAwareExitDecision decision = arbiter.evaluate(input()
                .currentPrice(new BigDecimal("94"))
                .hardStopPrice(new BigDecimal("90"))
                .trailingStopPrice(new BigDecimal("100"))
                .themeStage("DECAY")
                .mainstreamTheme(false)
                .ma10(new BigDecimal("100"))
                .ma20(new BigDecimal("98"))
                .previousLow(new BigDecimal("96"))
                .volumeStatus("VOLUME_BREAKDOWN")
                .relativeStrengthStatus("UNDERPERFORM")
                .chipStatus("BEARISH")
                .build());

        assertEquals(StructuralExitTier.EXIT_REVIEW, decision.tier());
        assertEquals(ThemeExitState.BROKEN, decision.themeState());
        assertEquals(StructureExitState.BROKEN, decision.structureState());
    }

    @Test
    void dataGapDoesNotFakeHold() {
        StructureAwareExitDecision decision = arbiter.evaluate(input()
                .currentPrice(new BigDecimal("100"))
                .hardStopPrice(new BigDecimal("90"))
                .themeStage(null)
                .ma10(null)
                .ma20(null)
                .previousLow(null)
                .build());

        assertEquals(StructuralExitTier.DATA_GAP, decision.tier());
        assertFalse(decision.dataGaps().isEmpty());
    }

    @Test
    void themeBrokenNeverReturnsHoldThesisEvenWithoutPriceTrigger() {
        StructureAwareExitDecision decision = arbiter.evaluate(input()
                .currentPrice(new BigDecimal("105"))
                .hardStopPrice(new BigDecimal("90"))
                .trailingStopPrice(new BigDecimal("95"))
                .dynamicStopPrice(new BigDecimal("95"))
                .themeStage("DECAY")
                .mainstreamTheme(false)
                .ma5(new BigDecimal("104"))
                .ma10(new BigDecimal("100"))
                .ma20(new BigDecimal("95"))
                .previousLow(new BigDecimal("96"))
                .volumeStatus("NORMAL")
                .relativeStrengthStatus("INLINE")
                .build());

        assertEquals(ThemeExitState.BROKEN, decision.themeState());
        assertEquals(StructureExitState.HEALTHY_PULLBACK, decision.structureState());
        assertEquals(PriceExitState.NO_TRIGGER, decision.priceState());
        assertEquals(StructuralExitTier.REDUCE_REVIEW, decision.tier());
    }

    @Test
    void structureBrokenStatesNeverReturnHoldThesisEvenWithoutPriceTrigger() {
        for (String scenario : new String[]{"MA20", "LOWER_LOW", "BROKEN"}) {
            ExitArbiterInput.Builder builder = input()
                    .themeStage("EXPANDING")
                    .mainstreamTheme(true)
                    .currentPrice(new BigDecimal("94"))
                    .hardStopPrice(new BigDecimal("80"))
                    .trailingStopPrice(new BigDecimal("90"))
                    .dynamicStopPrice(new BigDecimal("90"))
                    .ma10(new BigDecimal("92"))
                    .ma20(new BigDecimal("96"))
                    .previousLow(new BigDecimal("90"))
                    .volumeStatus("NORMAL")
                    .relativeStrengthStatus("INLINE")
                    .chipStatus("UNKNOWN");
            if ("LOWER_LOW".equals(scenario)) {
                builder.ma20(new BigDecimal("80")).previousLow(new BigDecimal("96"));
            } else if ("BROKEN".equals(scenario)) {
                builder.ma20(new BigDecimal("96")).volumeStatus("VOLUME_BREAKDOWN");
            }

            StructureAwareExitDecision decision = arbiter.evaluate(builder.build());

            assertTrue(decision.structureState() == StructureExitState.MA20_BREAK
                            || decision.structureState() == StructureExitState.LOWER_LOW_BREAKDOWN
                            || decision.structureState() == StructureExitState.BROKEN,
                    "unexpected structure state for " + scenario + ": " + decision.structureState());
            assertNotEquals(StructuralExitTier.HOLD_THESIS, decision.tier(), scenario);
            assertTrue(decision.tier() == StructuralExitTier.OBSERVE_1D
                    || decision.tier() == StructuralExitTier.REDUCE_REVIEW, scenario + " -> " + decision.tier());
        }
    }

    @Test
    void exhaustiveMatrixHasNoThemeBrokenOrStructureBrokenHoldPath() {
        String[] themes = {"EXPANDING", "MAINSTREAM", "COOLING", "DECAY"};
        for (String theme : themes) {
            for (ExitArbiterInput input : new ExitArbiterInput[]{
                    healthyNoTrigger(theme), healthyPriceTrigger(theme), ma20NoTrigger(theme), ma20PriceTrigger(theme), lowerLowNoTrigger(theme), lowerLowPriceTrigger(theme)
            }) {
                StructureAwareExitDecision decision = arbiter.evaluate(input);
                if (decision.themeState() == ThemeExitState.BROKEN || isBrokenStructure(decision.structureState())) {
                    assertNotEquals(StructuralExitTier.HOLD_THESIS, decision.tier(),
                            "forbidden HOLD path theme=" + decision.themeState()
                                    + " structure=" + decision.structureState()
                                    + " price=" + decision.priceState());
                }
            }
        }
    }

    private boolean isBrokenStructure(StructureExitState s) {
        return s == StructureExitState.MA20_BREAK
                || s == StructureExitState.LOWER_LOW_BREAKDOWN
                || s == StructureExitState.BROKEN;
    }

    private ExitArbiterInput healthyNoTrigger(String theme) {
        return input().themeStage(theme).mainstreamTheme(!"DECAY".equals(theme))
                .currentPrice(new BigDecimal("105")).hardStopPrice(new BigDecimal("80"))
                .trailingStopPrice(new BigDecimal("90")).dynamicStopPrice(new BigDecimal("90"))
                .ma5(new BigDecimal("104")).ma10(new BigDecimal("100")).ma20(new BigDecimal("95")).previousLow(new BigDecimal("96"))
                .build();
    }

    private ExitArbiterInput healthyPriceTrigger(String theme) {
        return input().themeStage(theme).mainstreamTheme(!"DECAY".equals(theme))
                .currentPrice(new BigDecimal("98")).hardStopPrice(new BigDecimal("80"))
                .trailingStopPrice(new BigDecimal("100")).dynamicStopPrice(new BigDecimal("100"))
                .ma5(new BigDecimal("97")).ma10(new BigDecimal("95")).ma20(new BigDecimal("90")).previousLow(new BigDecimal("91"))
                .build();
    }

    private ExitArbiterInput ma20NoTrigger(String theme) {
        return input().themeStage(theme).mainstreamTheme(!"DECAY".equals(theme))
                .currentPrice(new BigDecimal("94")).hardStopPrice(new BigDecimal("80"))
                .trailingStopPrice(new BigDecimal("90")).dynamicStopPrice(new BigDecimal("90"))
                .ma10(new BigDecimal("92")).ma20(new BigDecimal("96")).previousLow(new BigDecimal("90"))
                .build();
    }

    private ExitArbiterInput ma20PriceTrigger(String theme) {
        return input().themeStage(theme).mainstreamTheme(!"DECAY".equals(theme))
                .currentPrice(new BigDecimal("94")).hardStopPrice(new BigDecimal("80"))
                .trailingStopPrice(new BigDecimal("100")).dynamicStopPrice(new BigDecimal("100"))
                .ma10(new BigDecimal("92")).ma20(new BigDecimal("96")).previousLow(new BigDecimal("90"))
                .build();
    }

    private ExitArbiterInput lowerLowNoTrigger(String theme) {
        return input().themeStage(theme).mainstreamTheme(!"DECAY".equals(theme))
                .currentPrice(new BigDecimal("94")).hardStopPrice(new BigDecimal("80"))
                .trailingStopPrice(new BigDecimal("90")).dynamicStopPrice(new BigDecimal("90"))
                .ma10(new BigDecimal("92")).ma20(new BigDecimal("80")).previousLow(new BigDecimal("96"))
                .build();
    }

    private ExitArbiterInput lowerLowPriceTrigger(String theme) {
        return input().themeStage(theme).mainstreamTheme(!"DECAY".equals(theme))
                .currentPrice(new BigDecimal("94")).hardStopPrice(new BigDecimal("80"))
                .trailingStopPrice(new BigDecimal("100")).dynamicStopPrice(new BigDecimal("100"))
                .ma10(new BigDecimal("92")).ma20(new BigDecimal("80")).previousLow(new BigDecimal("96"))
                .build();
    }

    private ExitArbiterInput.Builder input() {
        PositionEntity pos = new PositionEntity();
        pos.setSymbol("2330");
        pos.setAvgCost(new BigDecimal("100"));
        pos.setStopLossPrice(new BigDecimal("90"));
        pos.setTrailingStopPrice(new BigDecimal("100"));
        return ExitArbiterInput.builder()
                .position(pos)
                .tradeRefType("POSITION")
                .tradeRefId(1L)
                .sourceDecision(new PositionDecisionEngine.PositionDecisionResult(
                        PositionDecisionEngine.PositionStatus.EXIT, "source EXIT", null,
                        PositionDecisionEngine.TrailingAction.NONE))
                .entryPrice(new BigDecimal("100"))
                .currentPrice(new BigDecimal("100"))
                .hardStopPrice(new BigDecimal("90"))
                .trailingStopPrice(new BigDecimal("100"))
                .dynamicStopPrice(new BigDecimal("100"))
                .volumeStatus("NORMAL")
                .relativeStrengthStatus("INLINE")
                .chipStatus("UNKNOWN");
    }
}
