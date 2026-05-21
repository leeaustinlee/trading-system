package com.austin.trading.engine;

import com.austin.trading.domain.enums.StructuralExitTier;

import java.util.List;

public record StructuralExitResult(
        StructuralExitTier structuralTier,
        String reason,
        boolean manualConfirmRequired,
        boolean autoSellEnabled,
        List<String> signals
) {
}
