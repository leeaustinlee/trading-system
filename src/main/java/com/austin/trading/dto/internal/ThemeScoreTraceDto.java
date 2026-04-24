package com.austin.trading.dto.internal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;

/**
 * v2 Theme Engine：題材強度計算的可追溯 trace（對應 spec §3.6）。
 *
 * <p>用途：每題材計算 {@code themeStrength} 時同步產生一份 trace，PR1 為 DTO；
 * PR2 由 {@code ThemeStrengthServiceV2}（未實作）寫入 {@code theme_snapshot.payload_json}
 * 或單獨持久化。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ThemeScoreTraceDto(
        String themeTag,
        SubScores subScores,
        Weights weights,
        BigDecimal themeStrength,
        String trendStage,
        String rotationSignal,
        BigDecimal sustainabilityScore,
        BigDecimal freshnessScore,
        String crowdingRisk
) {
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SubScores(
            BigDecimal priceBreadthScore,
            BigDecimal volumeExpansionScore,
            BigDecimal moneyFlowScore,
            BigDecimal newsCatalystScore,
            BigDecimal persistenceScore,
            BigDecimal rotationScore
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Weights(
            BigDecimal priceBreadth,
            BigDecimal volumeExpansion,
            BigDecimal moneyFlow,
            BigDecimal newsCatalyst,
            BigDecimal persistence,
            BigDecimal rotation
    ) {
        /** Spec §3.2 的預設權重（0.25/0.20/0.20/0.10/0.15/0.10）。 */
        public static Weights spec() {
            return new Weights(
                    new BigDecimal("0.25"),
                    new BigDecimal("0.20"),
                    new BigDecimal("0.20"),
                    new BigDecimal("0.10"),
                    new BigDecimal("0.15"),
                    new BigDecimal("0.10")
            );
        }
    }
}
