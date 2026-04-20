package com.austin.trading.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 分段出清請求：指定這次要賣出的張數與價格。
 * <ul>
 *   <li>qty：本次出清數量（單位與 position.qty 一致，通常為股數）</li>
 *   <li>closePrice：本次出場價</li>
 *   <li>exitReason：出場原因（TAKE_PROFIT_1 / TAKE_PROFIT_2 / TRAILING / STOP_LOSS / MANUAL）</li>
 * </ul>
 * 若 qty >= 剩餘持倉，等同全部出清。
 */
public record PositionPartialCloseRequest(
        @NotNull @DecimalMin("0.0001") BigDecimal qty,
        @NotNull @DecimalMin("0.0001") BigDecimal closePrice,
        LocalDateTime closedAt,
        String exitReason,
        String note
) {}
