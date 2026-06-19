package com.austin.trading.dto.internal;

import com.austin.trading.domain.enums.TradingFunnelBlockedStage;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** P0 read-only Trading Funnel Shadow Trace DTO skeleton. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record TradingFunnelTraceDto(
        Long id,
        LocalDate tradingDate,
        String symbol,
        String stockName,
        String themeTag,
        Long signalId,
        String signalSource,
        String signalRole,
        BigDecimal signalStrength,
        BigDecimal signalChangePct,
        Boolean signalNearLimit,
        String signalLimitRisk,
        String candidateStatus,
        String candidateReason,
        Long candidateId,
        String watchlistStatus,
        String watchlistReason,
        Long watchlistId,
        String rankingStatus,
        Integer rankingRank,
        BigDecimal rankingScore,
        String rankingReason,
        Long rankingSnapshotId,
        String setupStatus,
        String setupReason,
        Long setupDecisionId,
        String riskStatus,
        String riskReason,
        Long riskDecisionId,
        String portfolioStatus,
        String portfolioReason,
        Long positionId,
        String buyStatus,
        String buyReason,
        Long buyTradeId,
        String buyTradeRef,
        String exitStatus,
        String exitReason,
        Long exitRefId,
        BigDecimal finalOutcome1d,
        BigDecimal finalOutcome5d,
        BigDecimal finalOutcome10d,
        BigDecimal maxDrawdown10d,
        TradingFunnelBlockedStage blockedStage,
        String blockedReason,
        String traceSource,
        String traceStatus,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
