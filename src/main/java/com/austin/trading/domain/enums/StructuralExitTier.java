package com.austin.trading.domain.enums;

/**
 * Shadow/manual-confirm-only structural exit tier.
 * Does not trigger automatic sell/order actions.
 */
public enum StructuralExitTier {
    HOLD,
    OBSERVE_1D,
    REDUCE_REVIEW,
    EXIT_REVIEW,
    HARD_EXIT_ALERT,
    DATA_GAP
}
