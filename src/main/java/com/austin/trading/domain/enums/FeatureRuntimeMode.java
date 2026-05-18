package com.austin.trading.domain.enums;

/**
 * Observable rollout mode for engines/features.
 *
 * <p>This is read-only truth metadata. It must not be used as a trading signal
 * and must not mutate BUY/SELL/FinalDecision behavior.</p>
 */
public enum FeatureRuntimeMode {
    LIVE,
    SHADOW,
    OBSERVATION,
    TRACE_ONLY,
    OFF
}
