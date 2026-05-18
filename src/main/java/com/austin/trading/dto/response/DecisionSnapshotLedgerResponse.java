package com.austin.trading.dto.response;

import com.austin.trading.entity.DecisionSnapshotLedgerEntity;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record DecisionSnapshotLedgerResponse(
        Long id,
        Long finalDecisionId,
        LocalDate tradingDate,
        String sourceTaskType,
        String preferTaskType,
        Long aiTaskId,
        String aiStatus,
        String aiReadinessMode,
        String fallbackReason,
        String finalDecisionCode,
        String selectedSymbolsJson,
        String rejectedSymbolsJson,
        String watchSymbolsJson,
        String mergedSymbolsJson,
        String candidateUniverseJson,
        String candidateScoresJson,
        String marketContextJson,
        String gateTraceJson,
        String decisionTraceJson,
        String responsePayloadJson,
        LocalDateTime createdAt
) {
    public static DecisionSnapshotLedgerResponse from(DecisionSnapshotLedgerEntity entity) {
        return new DecisionSnapshotLedgerResponse(
                entity.getId(),
                entity.getFinalDecisionId(),
                entity.getTradingDate(),
                entity.getSourceTaskType(),
                entity.getPreferTaskType(),
                entity.getAiTaskId(),
                entity.getAiStatus(),
                entity.getAiReadinessMode(),
                entity.getFallbackReason(),
                entity.getFinalDecisionCode(),
                entity.getSelectedSymbolsJson(),
                entity.getRejectedSymbolsJson(),
                entity.getWatchSymbolsJson(),
                entity.getMergedSymbolsJson(),
                entity.getCandidateUniverseJson(),
                entity.getCandidateScoresJson(),
                entity.getMarketContextJson(),
                entity.getGateTraceJson(),
                entity.getDecisionTraceJson(),
                entity.getResponsePayloadJson(),
                entity.getCreatedAt()
        );
    }
}
