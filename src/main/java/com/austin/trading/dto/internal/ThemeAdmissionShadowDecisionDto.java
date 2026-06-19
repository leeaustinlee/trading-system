package com.austin.trading.dto.internal;

import com.austin.trading.domain.enums.ThemeAdmissionShadowAction;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** Shadow-only theme admission decision DTO skeleton. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ThemeAdmissionShadowDecisionDto(
        Long id,
        LocalDate tradingDate,
        String symbol,
        String stockName,
        String themeTag,
        Long signalId,
        String signalRole,
        String currentAction,
        String currentReason,
        ThemeAdmissionShadowAction shadowAction,
        String shadowReason,
        Boolean wouldWriteCandidate,
        Boolean wouldWriteWatchlist,
        Boolean wouldCreatePullbackPlan,
        Boolean wouldBypassTopN,
        String blockedByCurrentStage,
        String deltaStage,
        BigDecimal admissionScore,
        BigDecimal themeStrength,
        BigDecimal signalStrength,
        Integer rankInTheme,
        Boolean nearLimit,
        String limitRisk,
        Long sourceTraceId,
        String evidenceJson,
        String traceSource,
        String traceStatus,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
