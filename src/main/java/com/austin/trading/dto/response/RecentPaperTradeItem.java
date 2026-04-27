package com.austin.trading.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Subagent D: 最近平倉的 paper_trade 列(供 mobile #pnl 頁底部 list 使用)。
 *
 * <p>欄位最小化:不含 mfe / mae / payload,僅夠前端列出單行 + 連結到
 * {@code /api/paper/{id}/full-trace} (Subagent C 提供)。</p>
 */
public record RecentPaperTradeItem(
        Long id,
        String symbol,
        String stockName,
        String entryGrade,
        LocalDate entryDate,
        LocalDate exitDate,
        BigDecimal entryPrice,
        BigDecimal exitPrice,
        BigDecimal pnlPct,
        BigDecimal pnlAmount,
        Integer holdingDays,
        String exitReason,
        String themeTag
) {}
