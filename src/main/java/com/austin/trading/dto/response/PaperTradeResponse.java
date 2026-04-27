package com.austin.trading.dto.response;

import com.austin.trading.entity.PaperTradeEntity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

public record PaperTradeResponse(
        Long id,
        String tradeId,
        LocalDate entryDate,
        LocalTime entryTime,
        String symbol,
        String stockName,
        BigDecimal entryPrice,
        BigDecimal stopLossPrice,
        BigDecimal target1Price,
        BigDecimal target2Price,
        Integer maxHoldingDays,
        String source,
        String strategyType,
        String themeTag,
        Long finalDecisionId,
        Long aiTaskId,
        BigDecimal finalRankScore,
        BigDecimal themeHeatScore,
        BigDecimal expectationScore,
        LocalDate exitDate,
        LocalTime exitTime,
        BigDecimal exitPrice,
        String exitReason,
        BigDecimal pnlPct,
        BigDecimal pnlAmount,
        Integer holdingDays,
        BigDecimal mfePct,
        BigDecimal maePct,
        String status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static PaperTradeResponse from(PaperTradeEntity p) {
        return new PaperTradeResponse(
                p.getId(), p.getTradeId(),
                p.getEntryDate(), p.getEntryTime(),
                p.getSymbol(), p.getStockName(),
                p.getEntryPrice(), p.getStopLossPrice(),
                p.getTarget1Price(), p.getTarget2Price(),
                p.getMaxHoldingDays(),
                p.getSource(), p.getStrategyType(), p.getThemeTag(),
                p.getFinalDecisionId(), p.getAiTaskId(),
                p.getFinalRankScore(), p.getThemeHeatScore(), p.getExpectationScore(),
                p.getExitDate(), p.getExitTime(), p.getExitPrice(), p.getExitReason(),
                p.getPnlPct(), p.getPnlAmount(), p.getHoldingDays(),
                p.getMfePct(), p.getMaePct(),
                p.getStatus(),
                p.getCreatedAt(), p.getUpdatedAt()
        );
    }
}
