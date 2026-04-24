package com.austin.trading.dto.internal;

import com.austin.trading.domain.enums.PositionAction;
import com.austin.trading.domain.enums.PositionSizeLevel;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * PositionManagementEngine 的輸出（v2.10 MVP）。
 *
 * <p>MVP 僅做建議與 trace，不會自動下單。LINE 通知只有當 {@link PositionAction} 為
 * ADD / REDUCE / EXIT / SWITCH_HINT 時才會發出，HOLD 只記 trace。</p>
 */
public record PositionManagementResult(
        String symbol,
        PositionAction action,
        String reason,
        BigDecimal currentPrice,
        BigDecimal entryPrice,
        BigDecimal unrealizedPct,
        BigDecimal vwapPrice,
        BigDecimal volumeRatio,
        BigDecimal stopLoss,
        BigDecimal trailingStop,
        BigDecimal score,
        PositionSizeLevel positionSizeLevel,
        List<String> signals,
        List<String> warnings,
        Map<String, Object> trace
) {
    public boolean requiresNotification() {
        return action != PositionAction.HOLD;
    }
}
