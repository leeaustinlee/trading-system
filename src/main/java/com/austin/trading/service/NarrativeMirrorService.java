package com.austin.trading.service;

import com.austin.trading.dto.request.KolStructuredResultRequest;
import com.austin.trading.entity.KolThemeSignalDailySnapshotEntity;
import com.austin.trading.entity.KolThemeSignalEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Additive bridge from the existing KOL MVP pipeline into the broader
 * narrative_* tables introduced by V44. This service is observability-only:
 * it never feeds FinalDecision, PriceGate, ChasedHigh, veto, market grade,
 * BUY/SELL/ENTER, or capital paths.
 */
@Service
public class NarrativeMirrorService {
    private static final Logger log = LoggerFactory.getLogger(NarrativeMirrorService.class);
    private static final String VERSION = "kol-mvp-narrative-mirror-v1";

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public NarrativeMirrorService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public void mirrorRawSignal(KolThemeSignalEntity signal) {
        if (signal == null || signal.getContentHash() == null) return;
        try {
            jdbc.update("""
                    INSERT INTO narrative_signal
                    (trading_date, source_key, source_type, source_name, episode_id, episode_title,
                     source_url, published_at, content_hash, raw_content, ai_summary,
                     extraction_status, source_language, status, payload_json)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE
                        trading_date = VALUES(trading_date),
                        source_key = VALUES(source_key),
                        source_type = VALUES(source_type),
                        episode_title = VALUES(episode_title),
                        raw_content = VALUES(raw_content),
                        extraction_status = VALUES(extraction_status),
                        payload_json = VALUES(payload_json)
                    """,
                    signal.getTradingDate(),
                    signal.getSourceKey(),
                    defaulted(signal.getSourceType(), "PODCAST"),
                    "Gooaye 股癌",
                    signal.getSourceKey(),
                    signal.getSourceTitle(),
                    null,
                    null,
                    signal.getContentHash(),
                    signal.getRawContent(),
                    null,
                    defaulted(signal.getSignalStatus(), "RAW"),
                    "zh-TW",
                    "ACTIVE",
                    payload(Map.of(
                            "weakSignalOnly", true,
                            "productionDecisionAllowed", false,
                            "allowedUses", List.of("dashboard context", "candidate narrativeContext", "shadow report"),
                            "blockedUses", List.of("BUY_SIGNAL", "FINAL_DECISION_OVERRIDE", "PRICE_GATE_OVERRIDE", "CHASED_HIGH_OVERRIDE", "VETO_OVERRIDE", "MARKET_GRADE_OVERRIDE", "AUTO_TRADING"),
                            "mirrorVersion", VERSION,
                            "kolSignalId", signal.getId()
                    )));
        } catch (Exception e) {
            log.warn("Narrative raw mirror skipped for kol signal {}: {}", signal.getId(), e.getMessage());
        }
    }

    public void mirrorStructuredResult(KolThemeSignalEntity signal, List<KolStructuredResultRequest.ThemeItem> themes) {
        if (signal == null || signal.getContentHash() == null || themes == null) return;
        try {
            mirrorRawSignal(signal);
            Long narrativeId = findNarrativeId(signal.getContentHash());
            if (narrativeId == null) return;
            jdbc.update("DELETE FROM theme_signal WHERE narrative_signal_id = ?", narrativeId);
            for (KolStructuredResultRequest.ThemeItem theme : themes) {
                if (theme == null || theme.themeTag() == null || theme.themeTag().isBlank()) continue;
                List<String> symbols = theme.stocks() == null ? List.of() : theme.stocks().stream()
                        .filter(s -> s.symbol() != null && !s.symbol().isBlank())
                        .map(s -> s.symbol().trim())
                        .toList();
                BigDecimal confidence = defaultDecimal(theme.confidence(), "0.500");
                jdbc.update("""
                        INSERT INTO theme_signal
                        (narrative_signal_id, trading_date, theme_tag, theme_alias, direction, tone,
                         narrative_direction, lifecycle_hint, conviction_score, freshness_score,
                         novelty_score, uncertainty_score, crowding_score, evidence_level,
                         evidence_weight, confidence, reason_summary, risk_summary, payload_json)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                        narrativeId,
                        signal.getTradingDate(),
                        theme.themeTag().trim(),
                        alias(theme.themeTag()),
                        normalizeDirection(theme.direction()),
                        "MIXED",
                        normalizeDirection(theme.direction()),
                        payloadValue(theme.payload(), "lifecycleHint", null),
                        confidence,
                        payloadDecimal(theme.payload(), "freshnessScore", "0.700"),
                        payloadDecimal(theme.payload(), "noveltyScore", "0.500"),
                        payloadDecimal(theme.payload(), "uncertaintyScore", "0.350"),
                        payloadDecimal(theme.payload(), "crowdingScore", "0.450"),
                        "REASONED_ANALYSIS",
                        confidence,
                        confidence,
                        truncate(theme.summary(), 1100),
                        String.join("; ", List.of("weak signal only", "narrative cannot override hard gates")),
                        payload(Map.of(
                                "weakSignalOnly", true,
                                "productionDecisionAllowed", false,
                                "mentionedSymbols", symbols,
                                "evidenceCount", theme.evidence() == null ? 0 : theme.evidence().size(),
                                "mirrorVersion", VERSION
                        )));
            }
            jdbc.update("UPDATE narrative_signal SET extraction_status = 'STRUCTURED', updated_at = CURRENT_TIMESTAMP WHERE id = ?", narrativeId);
        } catch (Exception e) {
            log.warn("Narrative structured mirror skipped for kol signal {}: {}", signal.getId(), e.getMessage());
        }
    }

    public void clearDailySnapshots(LocalDate date) {
        if (date == null) return;
        try {
            jdbc.update("DELETE FROM narrative_theme_daily_snapshot WHERE trading_date = ?", date);
            jdbc.update("DELETE FROM theme_lifecycle_snapshot WHERE trading_date = ?", date);
        } catch (Exception e) {
            log.warn("Narrative daily mirror clear skipped for {}: {}", date, e.getMessage());
        }
    }

    public void mirrorDailySnapshot(KolThemeSignalDailySnapshotEntity snapshot) {
        if (snapshot == null || snapshot.getTradingDate() == null || snapshot.getThemeTag() == null) return;
        try {
            BigDecimal attention = tenPoint(snapshot.getPositiveScore().max(snapshot.getNegativeScore()));
            BigDecimal crowding = crowdingScore(snapshot.getCrowdingRisk());
            String lifecycle = lifecycle(attention, crowding, snapshot.getSourceCount(), snapshot.getEvidenceCount());
            BigDecimal boost = defaultDecimal(snapshot.getNetShadowBoost(), "0.0000");
            BigDecimal penalty = crowding.compareTo(new BigDecimal("8.0")) >= 0 ? new BigDecimal("-0.1000") : BigDecimal.ZERO;
            jdbc.update("""
                    INSERT INTO theme_lifecycle_snapshot
                    (trading_date, theme_tag, lifecycle_stage, previous_stage, mention_frequency_score,
                     source_density_score, media_spread_score, spread_velocity_score, price_extension_score,
                     turnover_heat_score, retail_crowding_score, institutional_participation_score,
                     lifecycle_confidence, recommended_effect, weight_factor, sizing_factor,
                     entry_aggressiveness, stop_strategy, risk_flags_json, evidence_json, payload_json)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE
                        lifecycle_stage = VALUES(lifecycle_stage),
                        mention_frequency_score = VALUES(mention_frequency_score),
                        source_density_score = VALUES(source_density_score),
                        retail_crowding_score = VALUES(retail_crowding_score),
                        lifecycle_confidence = VALUES(lifecycle_confidence),
                        recommended_effect = VALUES(recommended_effect),
                        risk_flags_json = VALUES(risk_flags_json),
                        evidence_json = VALUES(evidence_json),
                        payload_json = VALUES(payload_json)
                    """,
                    snapshot.getTradingDate(), snapshot.getThemeTag(), lifecycle, null,
                    attention, sourceDensity(snapshot.getSourceCount()), BigDecimal.ONE,
                    BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO, crowding,
                    BigDecimal.ZERO, confidenceFor(lifecycle), "CONTEXT_ONLY", BigDecimal.ONE,
                    BigDecimal.ONE, "NORMAL", "NORMAL",
                    payload(Map.of("crowdingRisk", snapshot.getCrowdingRisk(), "weakSignalOnly", true)),
                    payload(Map.of("sourceCount", safeInt(snapshot.getSourceCount()), "evidenceCount", safeInt(snapshot.getEvidenceCount()))),
                    payload(Map.of("mirrorVersion", VERSION, "productionDecisionAllowed", false)));

            jdbc.update("""
                    INSERT INTO narrative_theme_daily_snapshot
                    (trading_date, theme_tag, attention_score, conviction_score, freshness_score,
                     spread_velocity_score, cross_source_confirmation_score, rotation_score, crowding_score,
                     lifecycle_stage, lifecycle_factor, theme_strength_shadow, theme_boost_shadow,
                     theme_penalty_shadow, source_count, independent_source_count, evidence_count,
                     mentioned_stock_count, top_sources_json, top_symbols_json, evidence_json, risk_flags_json,
                     aggregation_version, payload_json)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE
                        attention_score = VALUES(attention_score),
                        conviction_score = VALUES(conviction_score),
                        freshness_score = VALUES(freshness_score),
                        crowding_score = VALUES(crowding_score),
                        lifecycle_stage = VALUES(lifecycle_stage),
                        theme_strength_shadow = VALUES(theme_strength_shadow),
                        theme_boost_shadow = VALUES(theme_boost_shadow),
                        theme_penalty_shadow = VALUES(theme_penalty_shadow),
                        source_count = VALUES(source_count),
                        evidence_count = VALUES(evidence_count),
                        top_sources_json = VALUES(top_sources_json),
                        risk_flags_json = VALUES(risk_flags_json),
                        payload_json = VALUES(payload_json)
                    """,
                    snapshot.getTradingDate(), snapshot.getThemeTag(), attention, attention,
                    new BigDecimal("1.000"), BigDecimal.ONE, sourceDensity(snapshot.getSourceCount()),
                    BigDecimal.ONE, crowding, lifecycle, BigDecimal.ONE, boost.add(penalty), boost,
                    penalty, safeInt(snapshot.getSourceCount()), safeInt(snapshot.getSourceCount()),
                    safeInt(snapshot.getEvidenceCount()), 0,
                    defaultJson(snapshot.getTopSourcesJson(), "[]"), "[]", "[]",
                    payload(Map.of("crowdingRisk", snapshot.getCrowdingRisk(), "weakSignalOnly", true)),
                    VERSION,
                    payload(Map.of("direction", snapshot.getDirection(), "guardrail", KolSignalContextService.WEAK_SIGNAL_GUARDRAIL)));
        } catch (Exception e) {
            log.warn("Narrative daily mirror skipped for {} {}: {}", snapshot.getTradingDate(), snapshot.getThemeTag(), e.getMessage());
        }
    }

    private Long findNarrativeId(String contentHash) {
        List<Long> ids = jdbc.query("SELECT id FROM narrative_signal WHERE content_hash = ? LIMIT 1",
                (rs, rowNum) -> rs.getLong(1), contentHash);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private String payload(Object value) {
        try { return objectMapper.writeValueAsString(value); } catch (Exception e) { return "{}"; }
    }

    private String defaultJson(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String defaulted(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String normalizeDirection(String value) {
        if (value == null || value.isBlank()) return "UNKNOWN";
        return value.trim().toUpperCase();
    }

    private String alias(String theme) {
        if (theme == null) return null;
        if (theme.contains("被動")) return "MLCC / 鋁電容 / 電容 / 電阻 / 電感";
        if (theme.toUpperCase().contains("POWER") || theme.contains("電源")) return "AI power / BBU / PMIC / server power";
        if (theme.contains("散熱")) return "thermal / heat sink / TIM";
        if (theme.contains("光")) return "optical communication / CPO / silicon photonics";
        return null;
    }

    private Object payloadValue(Map<String, Object> payload, String key, Object fallback) {
        return payload == null ? fallback : payload.getOrDefault(key, fallback);
    }

    private BigDecimal payloadDecimal(Map<String, Object> payload, String key, String fallback) {
        Object value = payloadValue(payload, key, fallback);
        try { return new BigDecimal(String.valueOf(value)); } catch (Exception e) { return new BigDecimal(fallback); }
    }

    private BigDecimal defaultDecimal(BigDecimal value, String fallback) {
        return value == null ? new BigDecimal(fallback) : value;
    }

    private String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }

    private BigDecimal tenPoint(BigDecimal score0to1) {
        return (score0to1 == null ? BigDecimal.ZERO : score0to1).multiply(BigDecimal.TEN).setScale(3, RoundingMode.HALF_UP);
    }

    private BigDecimal crowdingScore(String risk) {
        String r = risk == null ? "LOW" : risk.trim().toUpperCase();
        return switch (r) {
            case "HIGH" -> new BigDecimal("8.700");
            case "MEDIUM" -> new BigDecimal("5.200");
            default -> new BigDecimal("3.100");
        };
    }

    private BigDecimal sourceDensity(Integer sourceCount) {
        return new BigDecimal(Math.min(10, safeInt(sourceCount) * 2)).setScale(3, RoundingMode.HALF_UP);
    }

    private String lifecycle(BigDecimal attention, BigDecimal crowding, Integer sourceCount, Integer evidenceCount) {
        if (crowding.compareTo(new BigDecimal("8.0")) >= 0) return "CROWDED";
        if (attention.compareTo(new BigDecimal("8.0")) >= 0 && safeInt(sourceCount) >= 5) return "EXPANDING";
        if (attention.compareTo(new BigDecimal("6.5")) >= 0 && crowding.compareTo(new BigDecimal("6.5")) < 0) return "EMERGING";
        if (attention.compareTo(new BigDecimal("4.0")) >= 0) return "EARLY";
        return "NOISE";
    }

    private BigDecimal confidenceFor(String lifecycle) {
        return switch (lifecycle) {
            case "EXPANDING", "CROWDED" -> new BigDecimal("0.800");
            case "EMERGING" -> new BigDecimal("0.700");
            case "EARLY" -> new BigDecimal("0.550");
            default -> new BigDecimal("0.300");
        };
    }

    private int safeInt(Integer value) {
        return value == null ? 0 : value;
    }
}
