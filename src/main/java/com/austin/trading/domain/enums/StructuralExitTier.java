package com.austin.trading.domain.enums;

/**
 * Shadow/manual-confirm-only structural exit tier.
 * Does not trigger automatic sell/order actions.
 */
public enum StructuralExitTier {
    HARD_EXIT_ALERT,
    EXIT_REVIEW,
    REDUCE_REVIEW,
    OBSERVE_1D,
    HOLD_THESIS,
    /** Backward-compatible alias for older health-v2 structural engine users. */
    HOLD,
    DATA_GAP
}
