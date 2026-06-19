package com.austin.trading.domain.enums;

/**
 * Read-only shadow action for theme admission evaluation.
 *
 * <p>These values are trace metadata only and must not be used to mutate
 * production candidate/watchlist/buy/sell/risk decisions.</p>
 */
public enum ThemeAdmissionShadowAction {
    WOULD_ADMIT_CANDIDATE,
    WOULD_ADMIT_WATCHLIST,
    WOULD_CREATE_PULLBACK_PLAN,
    SHADOW_ONLY,
    REJECT,

    /** Legacy skeleton values retained for backward compatibility with older shadow rows. */
    ADMIT,
    WATCH,
    HOLD,
    NO_DATA
}
