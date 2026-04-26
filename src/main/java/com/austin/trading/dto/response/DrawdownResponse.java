package com.austin.trading.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * v2.14 GET /api/pnl/drawdown 回傳。
 *
 * <p>從每日 net pnl 累加成 equity 曲線，於 lookback 視窗內計算 rolling peak → trough
 * 的最大回撤；同時提供「目前距最近 peak 的回撤」。</p>
 *
 * <p>百分比基準：以 baseline (current totalAssets) 或最高 peak 中較大者為分母，
 * 避免 equity 從 0 起算時 % 失真。</p>
 *
 * <p>所有 % 為負值（最大回撤本來就 ≤ 0；無回撤時為 0）。</p>
 */
public record DrawdownResponse(
        int windowDays,
        BigDecimal maxDrawdownPct,
        LocalDate peakAt,
        LocalDate troughAt,
        BigDecimal currentDrawdownPct,
        int sampleDays,
        BigDecimal baseline
) {}
