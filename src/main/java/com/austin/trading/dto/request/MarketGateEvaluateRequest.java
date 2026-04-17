package com.austin.trading.dto.request;

import jakarta.validation.constraints.NotNull;

import java.time.LocalTime;

public record MarketGateEvaluateRequest(
        @NotNull boolean tsmcTrendUp,
        @NotNull boolean sectorsAligned,
        @NotNull boolean leadersStrong,
        @NotNull boolean nearHighNotBreak,
        @NotNull boolean washoutRebound,
        @NotNull boolean blowoffTopSignal,
        LocalTime evaluationTime
) {
}
