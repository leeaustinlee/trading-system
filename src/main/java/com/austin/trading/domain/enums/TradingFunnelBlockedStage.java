package com.austin.trading.domain.enums;

/**
 * First funnel stage that blocked a symbol in shadow trace reporting.
 *
 * <p>Read-only observability metadata; not a production trading control.</p>
 */
public enum TradingFunnelBlockedStage {
    NONE,
    SIGNAL,
    CANDIDATE,
    WATCHLIST,
    RANKING,
    SETUP,
    RISK,
    PORTFOLIO,
    BUY,
    EXIT,
    OUTCOME,
    UNKNOWN
}
