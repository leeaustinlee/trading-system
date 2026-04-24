package com.austin.trading.dto.internal;

import com.austin.trading.domain.enums.CrowdingRisk;
import com.austin.trading.domain.enums.RotationSignal;
import com.austin.trading.domain.enums.ThemeRole;
import com.austin.trading.domain.enums.TrendStage;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * v2 Theme Engine 快照頂層 DTO（對應 {@code theme-snapshot.json}，
 * schema 見 {@code theme-engine-implementation-spec.md §2.2}）。
 *
 * <p>本 DTO 為 data-contract 層，PR1 僅定義欄位；PR2 才會實作 parse / validate / fallback。</p>
 *
 * <p>欄位命名以底線 snake_case 對應 JSON（Jackson 由外部 ObjectMapper 透過 naming strategy 處理；
 * 這裡直接用 record field 名稱，snake_case key 的對映由 {@code spring.jackson.property-naming-strategy}
 * 或後續 explicit mapping 在 PR2 決定）。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ThemeSnapshotV2Dto(
        OffsetDateTime generatedAt,
        MarketRegime marketRegime,
        List<Theme> themes
) {
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MarketRegime(
            String regimeType,
            BigDecimal riskMultiplier,
            Boolean tradeAllowed
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Theme(
            String themeTag,
            BigDecimal themeStrength,
            /** "EARLY"/"MID"/"LATE"；用 String 保留原始 JSON 值，enum 轉換見 {@link #trendStageEnum()}。 */
            String trendStage,
            String rotationSignal,
            BigDecimal sustainabilityScore,
            BigDecimal freshnessScore,
            String crowdingRisk,
            List<String> evidenceSources,
            BigDecimal confidence,
            List<Evidence> evidence,
            List<ThemeCandidate> candidates
    ) {
        public TrendStage trendStageEnum() { return TrendStage.parseOrUnknown(trendStage); }
        public RotationSignal rotationSignalEnum() { return RotationSignal.parseOrUnknown(rotationSignal); }
        public CrowdingRisk crowdingRiskEnum() { return CrowdingRisk.parseOrUnknown(crowdingRisk); }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Evidence(
            String source,
            String summary,
            String url
    ) {}

    /**
     * 題材底下的個股 hint（spec §2.2 candidates 陣列）。
     *
     * <p>對應 user 請求的「ThemeCandidateDto」語意：symbol + role_hint + confidence。</p>
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ThemeCandidate(
            String symbol,
            String roleHint,
            BigDecimal confidence
    ) {
        public ThemeRole roleHintEnum() { return ThemeRole.parseOrUnknown(roleHint); }
    }
}
