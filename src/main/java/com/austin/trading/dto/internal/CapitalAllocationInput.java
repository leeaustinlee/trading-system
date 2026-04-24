package com.austin.trading.dto.internal;

import com.austin.trading.domain.enums.AllocationMode;

import java.math.BigDecimal;
import java.util.Map;

/**
 * v2.11 CapitalAllocationEngine 的 input bundle（MVP）。
 *
 * <h3>欄位來源</h3>
 * <ul>
 *   <li>{@code availableCash / accountEquity}：由 {@code CapitalService.getSummary()} 推導</li>
 *   <li>{@code currentPortfolioExposure}：SUM(qty×avgCost for OPEN positions)</li>
 *   <li>{@code currentThemeExposure}：{@code ThemeExposureService.compute()} 的對應題材市值</li>
 *   <li>{@code marketExposureLimit / themeExposureLimit / singlePositionLimit / riskPctPerTrade}：
 *       由 service 依 {@link AllocationMode} 與 {@code marketRegime} 從 config 解析後塞進來</li>
 * </ul>
 *
 * <p>Engine 本身 mode/regime 解讀是 pass-through — 所有 % / 百分比都以絕對小數（e.g. 0.006）傳入。</p>
 */
public record CapitalAllocationInput(
        String symbol,
        String theme,
        String bucket,
        BigDecimal finalRankScore,
        BigDecimal entryPrice,
        BigDecimal currentPrice,
        BigDecimal stopLoss,
        BigDecimal targetPrice,
        BigDecimal availableCash,
        BigDecimal accountEquity,
        BigDecimal currentPortfolioExposure,
        BigDecimal currentThemeExposure,
        BigDecimal marketExposureLimit,
        BigDecimal themeExposureLimit,
        BigDecimal singlePositionLimit,
        BigDecimal riskPctPerTrade,
        BigDecimal cashReservePct,
        BigDecimal minTradeAmount,
        Integer    lotSize,
        String     marketRegime,
        AllocationMode mode,
        AllocationIntent intent,
        Map<String, Object> trace
) {
    public enum AllocationIntent { OPEN, ADD, REDUCE, SWITCH }
}
