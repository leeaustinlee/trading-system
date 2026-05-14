package com.austin.trading.engine;

import java.util.List;

public record PositionHealthResult(
        int healthScore,
        String structureStatus,
        String volumeStatus,
        String relativeStrengthStatus,
        String chipStatus,
        ExitTier exitTier,
        List<String> reasons,
        List<String> dataGaps
) {
    public enum ExitTier {
        HOLD,
        SOFT_WARNING,
        REDUCE,
        EXIT_CONFIRM_REQUIRED,
        HARD_EXIT
    }
}
