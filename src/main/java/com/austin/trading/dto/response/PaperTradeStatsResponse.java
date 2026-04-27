package com.austin.trading.dto.response;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Subagent D: paper trade aggregated statistics response.
 *
 * <p>由 {@link com.austin.trading.service.PaperTradeStatsService#computeStats(int)} 產出,
 * 暴露 {@code GET /api/paper/stats?days=N} 給前端 #pnl 頁的「模擬交易績效」section 使用。</p>
 *
 * <p>Sharpe 採非年化版本 (raw mean / stddev) 並再乘 {@code sqrt(252 / avgHoldDays)} 做粗略
 * 年化轉換。樣本數 < 2 或 stddev=0 時回 BigDecimal.ZERO。</p>
 *
 * <p>maxDrawdownPct: 以 pnl_pct 序列建立累積權益曲線(等權重複利),回最大回撤百分比;
 * 若全程創高則為 0。</p>
 */
public record PaperTradeStatsResponse(
        int totalTrades,
        int winTrades,
        int lossTrades,
        int breakEvenTrades,
        BigDecimal winRate,
        BigDecimal avgWinPct,
        BigDecimal avgLossPct,
        BigDecimal profitFactor,
        BigDecimal totalPnlPct,
        BigDecimal totalPnlAmount,
        BigDecimal maxDrawdownPct,
        BigDecimal sharpeRatio,
        BigDecimal avgHoldDays,
        BigDecimal medianHoldDays,
        Map<String, GroupStats> byGrade,
        Map<String, GroupStats> byTheme,
        Map<String, GroupStats> byRegime,
        Map<String, GroupStats> byExitReason
) {
    public record GroupStats(int n, BigDecimal winRate, BigDecimal avgPnlPct, BigDecimal totalPnlPct) {}
}
