package com.austin.trading.dto.internal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** Shadow-only Top-N ranking result DTO skeleton. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record RankingTopNShadowResultDto(
        Long id,
        LocalDate tradingDate,
        String runId,
        Long snapshotId,
        String symbol,
        String stockName,
        String themeTag,
        String bucket,
        Boolean currentSelected,
        Boolean wouldSelectTop5,
        Boolean wouldSelectTop10,
        Boolean wouldSelectTop20,
        Integer rankingRank,
        BigDecimal rankingScore,
        String rankingStatus,
        String rankingReason,
        Long candidateId,
        Long sourceTraceId,
        BigDecimal actualReturn1d,
        BigDecimal actualReturn5d,
        BigDecimal actualReturn10d,
        BigDecimal maxDrawdown10d,
        Boolean missedByTop3,
        String scoreBreakdownJson,
        String traceSource,
        String traceStatus,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
