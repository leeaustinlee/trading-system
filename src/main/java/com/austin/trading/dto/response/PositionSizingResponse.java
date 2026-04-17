package com.austin.trading.dto.response;

public record PositionSizingResponse(
        double positionSizeMultiplier,
        double suggestedPositionSize,
        String rationale
) {
}
