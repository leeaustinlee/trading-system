package com.austin.trading.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDate;
import java.util.Map;

public record BuildOperationResponse(
        String buildType,
        LocalDate tradingDate,
        String sourcePhase,
        int builtCount,
        int deletedCount,
        int insertedCount,
        int updatedCount,
        int skippedCount,
        boolean rebuild,
        Object safetyBoundary,
        boolean replayOnly,
        boolean researchOnly,
        boolean doesNotAffectFinalDecision,
        boolean doesNotAffectBuySellEnter,
        boolean doesNotWriteCandidateStock,
        boolean doesNotWriteProductionScore,
        boolean noAutoPromotion,
        Long traceId,
        String status,
        Map<String, Object> payload,
        int preservedManualCount,
        int mergedManualCount,
        int deletedSystemCount,
        boolean manualReviewPreserved
) {
    @JsonProperty("shadowOnly")
    public boolean shadowOnly() { return true; }

    @JsonProperty("observabilityOnly")
    public boolean observabilityOnly() { return true; }

    @JsonProperty("reviewOnly")
    public boolean reviewOnly() { return true; }

    @JsonProperty("advisoryOnly")
    public boolean advisoryOnly() { return true; }

    @JsonProperty("analyticsOnly")
    public boolean analyticsOnly() { return true; }

    @JsonProperty("candidatePoolShadowIsNotTradable")
    public boolean candidatePoolShadowIsNotTradable() { return true; }

    @JsonProperty("metrics")
    public Object metrics() { return payload == null ? Map.of() : payload.getOrDefault("metrics", Map.of()); }

    @JsonProperty("stages")
    public Object stages() { return payload == null ? Map.of() : payload.getOrDefault("stages", Map.of()); }

    @JsonProperty("items")
    public Object items() { return payload == null ? java.util.List.of() : payload.getOrDefault("items", java.util.List.of()); }


    public Builder toBuilder() {
        return builder(buildType, tradingDate)
                .sourcePhase(sourcePhase)
                .builtCount(builtCount)
                .deletedCount(deletedCount)
                .insertedCount(insertedCount)
                .updatedCount(updatedCount)
                .skippedCount(skippedCount)
                .rebuild(rebuild)
                .safetyBoundary(safetyBoundary)
                .traceId(traceId)
                .status(status)
                .payload(payload)
                .preservedManualCount(preservedManualCount)
                .mergedManualCount(mergedManualCount)
                .deletedSystemCount(deletedSystemCount)
                .manualReviewPreserved(manualReviewPreserved);
    }

    public static Builder builder(String buildType, LocalDate tradingDate) {
        return new Builder(buildType, tradingDate);
    }

    public static final class Builder {
        private final String buildType;
        private final LocalDate tradingDate;
        private String sourcePhase;
        private int builtCount;
        private int deletedCount;
        private int insertedCount;
        private int updatedCount;
        private int skippedCount;
        private boolean rebuild = true;
        private Object safetyBoundary;
        private Long traceId;
        private String status = "SUCCESS";
        private Map<String, Object> payload = Map.of();
        private int preservedManualCount;
        private int mergedManualCount;
        private int deletedSystemCount;
        private boolean manualReviewPreserved = false;

        private Builder(String buildType, LocalDate tradingDate) {
            this.buildType = buildType;
            this.tradingDate = tradingDate;
        }

        public Builder sourcePhase(String sourcePhase) { this.sourcePhase = sourcePhase; return this; }
        public Builder builtCount(int builtCount) { this.builtCount = builtCount; this.insertedCount = builtCount; return this; }
        public Builder deletedCount(int deletedCount) { this.deletedCount = deletedCount; return this; }
        public Builder insertedCount(int insertedCount) { this.insertedCount = insertedCount; return this; }
        public Builder updatedCount(int updatedCount) { this.updatedCount = updatedCount; return this; }
        public Builder skippedCount(int skippedCount) { this.skippedCount = skippedCount; return this; }
        public Builder rebuild(boolean rebuild) { this.rebuild = rebuild; return this; }
        public Builder safetyBoundary(Object safetyBoundary) { this.safetyBoundary = safetyBoundary; return this; }
        public Builder traceId(Long traceId) { this.traceId = traceId; return this; }
        public Builder status(String status) { this.status = status; return this; }
        public Builder payload(Map<String, Object> payload) { this.payload = payload == null ? Map.of() : payload; return this; }
        public Builder preservedManualCount(int preservedManualCount) { this.preservedManualCount = preservedManualCount; return this; }
        public Builder mergedManualCount(int mergedManualCount) { this.mergedManualCount = mergedManualCount; return this; }
        public Builder deletedSystemCount(int deletedSystemCount) { this.deletedSystemCount = deletedSystemCount; return this; }
        public Builder manualReviewPreserved(boolean manualReviewPreserved) { this.manualReviewPreserved = manualReviewPreserved; return this; }

        public BuildOperationResponse build() {
            return new BuildOperationResponse(buildType, tradingDate, sourcePhase, builtCount, deletedCount, insertedCount,
                    updatedCount, skippedCount, rebuild, safetyBoundary,
                    true, true, true, true, true, true, true, traceId, status, payload,
                    preservedManualCount, mergedManualCount, deletedSystemCount, manualReviewPreserved);
        }
    }
}
