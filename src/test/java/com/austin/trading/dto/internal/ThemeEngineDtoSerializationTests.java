package com.austin.trading.dto.internal;

import com.austin.trading.domain.enums.CrowdingRisk;
import com.austin.trading.domain.enums.DecisionDiffType;
import com.austin.trading.domain.enums.RotationSignal;
import com.austin.trading.domain.enums.ThemeRole;
import com.austin.trading.domain.enums.TrendStage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v2 Theme Engine PR1：4 個新 DTO 的 JSON 序列化 / 反序列化測試。
 *
 * <p>Mapper 設定採 snake_case 命名策略（對齊 spec §2.2 JSON schema）+ JavaTimeModule。</p>
 */
class ThemeEngineDtoSerializationTests {

    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    // ═══════════════════════════════════════════════════════════════
    // ThemeSnapshotV2Dto
    // ═══════════════════════════════════════════════════════════════

    @Test
    void snapshotV2_serializeDeserialize_roundTrip() throws Exception {
        ThemeSnapshotV2Dto snap = new ThemeSnapshotV2Dto(
                OffsetDateTime.of(2026, 4, 24, 9, 30, 0, 0, ZoneOffset.ofHours(8)),
                new ThemeSnapshotV2Dto.MarketRegime("BULL_TREND", new BigDecimal("1.0"), true),
                List.of(new ThemeSnapshotV2Dto.Theme(
                        "AI_SERVER",
                        new BigDecimal("7.33"),
                        "MID",
                        "IN",
                        new BigDecimal("7.03"),
                        new BigDecimal("7.05"),
                        "MID",
                        List.of("GAINER_BOARD", "VOLUME_BOARD"),
                        new BigDecimal("0.82"),
                        List.of(new ThemeSnapshotV2Dto.Evidence("NEWS", "大廠擴產", null)),
                        List.of(new ThemeSnapshotV2Dto.ThemeCandidate(
                                "2454", "LEADER", new BigDecimal("0.9")))
                ))
        );

        String json = mapper.writeValueAsString(snap);
        assertThat(json).contains("\"generated_at\"").contains("\"market_regime\"")
                .contains("\"theme_tag\":\"AI_SERVER\"")
                .contains("\"role_hint\":\"LEADER\"");

        ThemeSnapshotV2Dto parsed = mapper.readValue(json, ThemeSnapshotV2Dto.class);
        assertThat(parsed.generatedAt()).isEqualTo(snap.generatedAt());
        assertThat(parsed.marketRegime().regimeType()).isEqualTo("BULL_TREND");
        assertThat(parsed.themes()).hasSize(1);

        ThemeSnapshotV2Dto.Theme t = parsed.themes().get(0);
        assertThat(t.trendStageEnum()).isEqualTo(TrendStage.MID);
        assertThat(t.rotationSignalEnum()).isEqualTo(RotationSignal.IN);
        assertThat(t.crowdingRiskEnum()).isEqualTo(CrowdingRisk.MID);
        assertThat(t.candidates().get(0).roleHintEnum()).isEqualTo(ThemeRole.LEADER);
    }

    @Test
    void snapshotV2_unknownFields_areIgnored() throws Exception {
        String json = "{\"generated_at\":\"2026-04-24T09:30:00+08:00\","
                + "\"market_regime\":{\"regime_type\":\"BULL_TREND\",\"risk_multiplier\":1.0,\"trade_allowed\":true},"
                + "\"themes\":[],"
                + "\"future_field\":\"ignore-me\"}";
        ThemeSnapshotV2Dto parsed = mapper.readValue(json, ThemeSnapshotV2Dto.class);
        assertThat(parsed.themes()).isEmpty();
    }

    @Test
    void snapshotV2_unknownEnumString_fallsBackToUnknownEnum() throws Exception {
        String json = "{\"generated_at\":\"2026-04-24T09:30:00+08:00\","
                + "\"market_regime\":{\"regime_type\":\"BULL_TREND\",\"risk_multiplier\":1.0,\"trade_allowed\":true},"
                + "\"themes\":[{"
                + "\"theme_tag\":\"X\",\"theme_strength\":5.0,"
                + "\"trend_stage\":\"SUPER_EARLY\",\"rotation_signal\":\"MAYBE\","
                + "\"sustainability_score\":5.0,\"freshness_score\":5.0,"
                + "\"crowding_risk\":\"EXTREME\",\"evidence_sources\":[\"NEWS\"],"
                + "\"confidence\":0.5,\"candidates\":[]"
                + "}]}";
        ThemeSnapshotV2Dto parsed = mapper.readValue(json, ThemeSnapshotV2Dto.class);
        ThemeSnapshotV2Dto.Theme t = parsed.themes().get(0);
        assertThat(t.trendStageEnum()).isEqualTo(TrendStage.UNKNOWN);
        assertThat(t.rotationSignalEnum()).isEqualTo(RotationSignal.UNKNOWN);
        assertThat(t.crowdingRiskEnum()).isEqualTo(CrowdingRisk.UNKNOWN);
    }

    // ═══════════════════════════════════════════════════════════════
    // ThemeContextDto — null / UNKNOWN fallback
    // ═══════════════════════════════════════════════════════════════

    @Test
    void themeContext_hasMinimumCodexFields_trueWhenComplete() {
        ThemeContextDto ctx = new ThemeContextDto(
                "2454", "AI_SERVER",
                new BigDecimal("7.3"),
                TrendStage.MID, RotationSignal.IN,
                new BigDecimal("7.0"), new BigDecimal("7.0"),
                CrowdingRisk.MID, new BigDecimal("0.8"),
                List.of("GAINER_BOARD"),
                null, null, null, null, null, null
        );
        assertThat(ctx.hasMinimumCodexFields()).isTrue();
    }

    @Test
    void themeContext_hasMinimumCodexFields_falseWhenMissingOrUnknown() {
        ThemeContextDto missingTag = new ThemeContextDto(
                "2454", null,
                new BigDecimal("7.3"),
                TrendStage.MID, RotationSignal.IN,
                null, null, null, null, null,
                null, null, null, null, null, null
        );
        assertThat(missingTag.hasMinimumCodexFields()).isFalse();

        ThemeContextDto unknownStage = new ThemeContextDto(
                "2454", "AI", new BigDecimal("7.3"),
                TrendStage.UNKNOWN, RotationSignal.IN,
                null, null, null, null, null,
                null, null, null, null, null, null
        );
        assertThat(unknownStage.hasMinimumCodexFields()).isFalse();

        ThemeContextDto unknownRotation = new ThemeContextDto(
                "2454", "AI", new BigDecimal("7.3"),
                TrendStage.MID, RotationSignal.UNKNOWN,
                null, null, null, null, null,
                null, null, null, null, null, null
        );
        assertThat(unknownRotation.hasMinimumCodexFields()).isFalse();
    }

    @Test
    void themeContext_serializeDeserialize() throws Exception {
        ThemeContextDto ctx = new ThemeContextDto(
                "2454", "AI_SERVER",
                new BigDecimal("7.33"),
                TrendStage.MID, RotationSignal.IN,
                new BigDecimal("7.03"), new BigDecimal("7.05"),
                CrowdingRisk.MID, new BigDecimal("0.82"),
                List.of("NEWS"),
                ThemeRole.LEADER,
                new BigDecimal("0.9"),
                new BigDecimal("0.2"),
                new BigDecimal("0.1"),
                "法說後訂單能見度高",
                List.of("geo-politics")
        );
        String json = mapper.writeValueAsString(ctx);
        assertThat(json).contains("\"theme_tag\":\"AI_SERVER\"")
                .contains("\"theme_role\":\"LEADER\"")
                .contains("\"stock_specific_catalyst\"");

        ThemeContextDto back = mapper.readValue(json, ThemeContextDto.class);
        assertThat(back.themeRole()).isEqualTo(ThemeRole.LEADER);
        assertThat(back.rotationSignal()).isEqualTo(RotationSignal.IN);
        assertThat(back.hasMinimumCodexFields()).isTrue();
    }

    // ═══════════════════════════════════════════════════════════════
    // ThemeScoreTraceDto
    // ═══════════════════════════════════════════════════════════════

    @Test
    void scoreTrace_specDefaultWeights() {
        ThemeScoreTraceDto.Weights w = ThemeScoreTraceDto.Weights.spec();
        assertThat(w.priceBreadth()).isEqualByComparingTo(new BigDecimal("0.25"));
        assertThat(w.volumeExpansion()).isEqualByComparingTo(new BigDecimal("0.20"));
        assertThat(w.moneyFlow()).isEqualByComparingTo(new BigDecimal("0.20"));
        assertThat(w.newsCatalyst()).isEqualByComparingTo(new BigDecimal("0.10"));
        assertThat(w.persistence()).isEqualByComparingTo(new BigDecimal("0.15"));
        assertThat(w.rotation()).isEqualByComparingTo(new BigDecimal("0.10"));

        // 權重總和應為 1.0
        BigDecimal total = w.priceBreadth().add(w.volumeExpansion()).add(w.moneyFlow())
                .add(w.newsCatalyst()).add(w.persistence()).add(w.rotation());
        assertThat(total).isEqualByComparingTo(BigDecimal.ONE);
    }

    @Test
    void scoreTrace_roundTrip() throws Exception {
        ThemeScoreTraceDto trace = new ThemeScoreTraceDto(
                "AI_SERVER",
                new ThemeScoreTraceDto.SubScores(
                        new BigDecimal("8.2"), new BigDecimal("7.5"),
                        new BigDecimal("6.8"), new BigDecimal("6.0"),
                        new BigDecimal("7.1"), new BigDecimal("7.3")
                ),
                ThemeScoreTraceDto.Weights.spec(),
                new BigDecimal("7.33"),
                "MID", "IN",
                new BigDecimal("7.03"), new BigDecimal("7.05"),
                "MID"
        );
        String json = mapper.writeValueAsString(trace);
        assertThat(json).contains("\"sub_scores\"")
                .contains("\"price_breadth_score\":8.2")
                .contains("\"weights\"")
                .contains("\"theme_strength\":7.33");

        ThemeScoreTraceDto back = mapper.readValue(json, ThemeScoreTraceDto.class);
        assertThat(back.themeStrength()).isEqualByComparingTo(new BigDecimal("7.33"));
    }

    // ═══════════════════════════════════════════════════════════════
    // ThemeShadowDecisionDto
    // ═══════════════════════════════════════════════════════════════

    @Test
    void shadowDecision_roundTrip() throws Exception {
        ThemeShadowDecisionDto shadow = new ThemeShadowDecisionDto(
                LocalDate.of(2026, 4, 24),
                "2454", "BULL_TREND",
                new BigDecimal("8.5"),
                new BigDecimal("6.8"),
                new BigDecimal("-1.7"),
                "ENTER", "WAIT",
                "THEME_ROTATION_OUT",
                DecisionDiffType.LEGACY_BUY_THEME_BLOCK,
                Map.of("G1_MARKET_REGIME", "PASS"),
                Map.of("G3_THEME_ROTATION", "BLOCK")
        );
        String json = mapper.writeValueAsString(shadow);
        assertThat(json).contains("\"decision_diff_type\":\"LEGACY_BUY_THEME_BLOCK\"")
                .contains("\"legacy_final_score\":8.5")
                .contains("\"theme_veto_reason\":\"THEME_ROTATION_OUT\"");

        ThemeShadowDecisionDto back = mapper.readValue(json, ThemeShadowDecisionDto.class);
        assertThat(back.decisionDiffType()).isEqualTo(DecisionDiffType.LEGACY_BUY_THEME_BLOCK);
        assertThat(back.scoreDiff()).isEqualByComparingTo(new BigDecimal("-1.7"));
    }

    @Test
    void shadowDecision_nullFieldsOmittedFromJson() throws Exception {
        ThemeShadowDecisionDto sparse = new ThemeShadowDecisionDto(
                LocalDate.of(2026, 4, 24),
                "2454", null,
                null, null, null,
                "REST", "REST", null,
                DecisionDiffType.SAME_WAIT,
                null, null
        );
        String json = mapper.writeValueAsString(sparse);
        assertThat(json).doesNotContain("market_regime")
                .doesNotContain("legacy_final_score")
                .doesNotContain("theme_veto_reason")
                .contains("\"decision_diff_type\":\"SAME_WAIT\"");
    }
}
