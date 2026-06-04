package com.austin.trading.domain.enums;

public enum StructureExitState {
    HEALTHY_PULLBACK,
    INTACT,
    MA5_BREAK_ONLY,
    MA10_TEST,
    MA20_BREAK,
    LOWER_LOW_BREAKDOWN,
    VOLUME_PANIC,
    RELATIVE_STRENGTH_BROKEN,
    BROKEN,
    PANIC_BREAK,
    DATA_GAP
}
