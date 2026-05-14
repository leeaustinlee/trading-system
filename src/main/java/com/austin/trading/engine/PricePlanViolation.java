package com.austin.trading.engine;

public enum PricePlanViolation {
    MISSING_ENTRY,
    MISSING_STOP,
    MISSING_TP1,
    MISSING_TP2,
    STALE_PRICE_PLAN,
    STOP_NOT_BELOW_ENTRY,
    TP1_NOT_ABOVE_ENTRY,
    TP2_NOT_ABOVE_TP1,
    RR_NEGATIVE,
    RR_BELOW_THRESHOLD,
    TP1_GAIN_TOO_SMALL,
    STOP_TOO_CLOSE,
    STOP_TOO_FAR
}
