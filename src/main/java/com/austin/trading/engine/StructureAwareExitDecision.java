package com.austin.trading.engine;

import com.austin.trading.domain.enums.PriceExitState;
import com.austin.trading.domain.enums.StructuralExitTier;
import com.austin.trading.domain.enums.StructureExitState;
import com.austin.trading.domain.enums.ThemeExitState;

import java.util.List;
import java.util.Map;

public record StructureAwareExitDecision(
        StructuralExitTier tier,
        String reason,
        ThemeExitState themeState,
        StructureExitState structureState,
        PriceExitState priceState,
        boolean riskBlock,
        boolean manualConfirmRequired,
        boolean autoSellEnabled,
        List<String> signals,
        List<String> dataGaps,
        Map<String, Object> layerVotes
) {}
