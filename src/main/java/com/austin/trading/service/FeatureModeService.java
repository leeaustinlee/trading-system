package com.austin.trading.service;

import com.austin.trading.domain.enums.FeatureRuntimeMode;
import com.austin.trading.dto.response.FeatureModeResponse;
import com.austin.trading.dto.response.FeatureModeSummaryResponse;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Read-only catalog for effective engine rollout mode.
 *
 * <p>W1-4 truth layer: centralizes LIVE / SHADOW / OBSERVATION /
 * TRACE_ONLY / OFF visibility without changing any production decision path.</p>
 */
@Service
public class FeatureModeService {

    private final ScoreConfigService scoreConfigService;

    public FeatureModeService(ScoreConfigService scoreConfigService) {
        this.scoreConfigService = scoreConfigService;
    }

    public List<FeatureModeResponse> list() {
        LocalDateTime now = LocalDateTime.now();
        return definitions().stream()
                .map(def -> toResponse(def, now))
                .toList();
    }

    public Optional<FeatureModeResponse> find(String featureKey) {
        if (featureKey == null || featureKey.isBlank()) return Optional.empty();
        String normalized = normalize(featureKey);
        LocalDateTime now = LocalDateTime.now();
        return definitions().stream()
                .filter(def -> normalize(def.featureKey()).equals(normalized))
                .findFirst()
                .map(def -> toResponse(def, now));
    }

    public FeatureModeSummaryResponse summary() {
        List<FeatureModeResponse> features = list();
        Map<FeatureRuntimeMode, Long> counts = new EnumMap<>(FeatureRuntimeMode.class);
        for (FeatureRuntimeMode mode : FeatureRuntimeMode.values()) {
            counts.put(mode, 0L);
        }
        for (FeatureModeResponse feature : features) {
            counts.merge(feature.mode(), 1L, Long::sum);
        }
        long liveImpact = features.stream()
                .filter(f -> f.mode() == FeatureRuntimeMode.LIVE && f.canAffectLiveDecision())
                .count();
        return new FeatureModeSummaryResponse(
                LocalDateTime.now(),
                features.size(),
                counts,
                liveImpact,
                "Feature Mode API is read-only observability; it does not enable BUY/SELL or mutate FinalDecision.");
    }

    private FeatureModeResponse toResponse(FeatureDefinition def, LocalDateTime now) {
        String value = readConfig(def.primaryConfigKey(), def.defaultConfigValue());
        boolean enabled = parseBoolean(value);
        Map<String, String> supportingValues = new LinkedHashMap<>();
        for (String key : def.supportingConfigKeys()) {
            supportingValues.put(key, readConfig(key, ""));
        }
        FeatureRuntimeMode mode = def.modeResolver().apply(new FeatureContext(enabled, value, supportingValues));
        return new FeatureModeResponse(
                def.featureKey(),
                def.displayName(),
                def.category(),
                mode,
                enabled,
                def.primaryConfigKey(),
                value,
                def.defaultConfigValue(),
                def.supportingConfigKeys(),
                supportingValues,
                def.canAffectLiveDecision(),
                def.decisionImpact(),
                def.safetyNote(),
                now);
    }

    private String readConfig(String key, String defaultValue) {
        if (key == null || key.isBlank()) return defaultValue == null ? "" : defaultValue;
        return scoreConfigService.getString(key, defaultValue == null ? "" : defaultValue);
    }

    private static boolean parseBoolean(String value) {
        return "true".equalsIgnoreCase(value == null ? "" : value.trim());
    }

    private static String normalize(String s) {
        return s.toLowerCase(Locale.ROOT)
                .replace("_", "")
                .replace("-", "")
                .trim();
    }

    private static List<FeatureDefinition> definitions() {
        List<FeatureDefinition> defs = new ArrayList<>();
        defs.add(new FeatureDefinition(
                "ThemeLiveDecision", "Theme live decision override", "THEME",
                "theme.live_decision.enabled", "false",
                List.of("theme.live_decision.wait_override.enabled", "theme.engine.v2.enabled"),
                ctx -> ctx.enabled() ? FeatureRuntimeMode.LIVE : FeatureRuntimeMode.OFF,
                true,
                "When LIVE, ThemeLiveDecisionService may convert legacy ENTER to REST on theme BLOCK. WAIT override is separately flagged.",
                "Default OFF; do not enable without shadow evidence."));
        defs.add(new FeatureDefinition(
                "ThemeShadowMode", "Theme v2 shadow comparison", "THEME",
                "theme.shadow_mode.enabled", "true",
                List.of("theme.gate.trace.enabled", "theme.shadow_report.path", "theme.shadow_report.path_wsl"),
                ctx -> ctx.enabled() ? FeatureRuntimeMode.SHADOW : FeatureRuntimeMode.OFF,
                false,
                "Writes diff/report only; should not alter live decisions.",
                "Safe to keep ON for diagnostics."));
        defs.add(new FeatureDefinition(
                "MomentumDecision", "Momentum candidate hard gate", "MOMENTUM",
                "candidate.momentum_gate.enabled", "false",
                List.of("momentum.basic_conditions_min", "momentum.entry_score_min", "momentum.watch_score_min"),
                ctx -> ctx.enabled() ? FeatureRuntimeMode.LIVE : FeatureRuntimeMode.OFF,
                true,
                "When LIVE, candidate save path rejects non-momentum candidates before they enter downstream evaluation.",
                "Default OFF because upstream payload may miss momentum signals."));
        defs.add(new FeatureDefinition(
                "MomentumObservation", "Momentum forward observation", "MOMENTUM",
                "paper.shadow.enabled", "true",
                List.of("paper.shadow.score_min", "paper.return_backfill.enabled", "candidate.momentum_gate.enabled"),
                ctx -> ctx.enabled() ? FeatureRuntimeMode.OBSERVATION : FeatureRuntimeMode.OFF,
                false,
                "Records forward/paper observation samples; no live order semantics.",
                "Observation data only; not a BUY signal."));
        defs.add(new FeatureDefinition(
                "ChasedHighGate", "Chased high entry gate", "ENTRY",
                "entry.chased-high-gate.enabled", "false",
                List.of("entry.chased-high-gate.threshold", "entry.chased-high-gate.warn_threshold"),
                ctx -> ctx.enabled() ? FeatureRuntimeMode.LIVE : FeatureRuntimeMode.SHADOW,
                true,
                "When LIVE, may block high-chase entries; when false current code still emits shadow traces in FinalDecision tests/trace.",
                "Default SHADOW/OFF-like; review trace before enabling hard gate."));
        defs.add(new FeatureDefinition(
                "ShadowExitRuleEngine", "Exit alert / auto-close safety layer", "EXIT",
                "position.review.auto_close.paper_only", "true",
                List.of("position.review.auto_close.enabled", "position.review.exit_alert.enabled"),
                ctx -> {
                    boolean autoCloseEnabled = parseBoolean(ctx.supportingConfigValues().get("position.review.auto_close.enabled"));
                    if (!autoCloseEnabled) return FeatureRuntimeMode.OFF;
                    return ctx.enabled() ? FeatureRuntimeMode.SHADOW : FeatureRuntimeMode.LIVE;
                },
                true,
                "paper_only=true means auto-close is shadow/paper only; paper_only=false allows real close path if auto_close.enabled is true.",
                "Keep paper_only=true unless human-approved live exit automation is explicitly desired."));
        defs.add(new FeatureDefinition(
                "PaperTrade", "Paper trade recording", "PAPER",
                "trading.paper_mode.enabled", "true",
                List.of("trading.paper-trade.enabled", "paper.auto_exit.enabled", "paper.entry_slippage_pct", "paper.exit_slippage_pct"),
                ctx -> ctx.enabled() ? FeatureRuntimeMode.OBSERVATION : FeatureRuntimeMode.OFF,
                false,
                "Writes simulated trades / paper records; should not send real orders.",
                "Observation only; avoids confusing paper ENTER with live BUY."));
        defs.add(new FeatureDefinition(
                "ReplayBacktest", "Replay/backtest APIs", "BACKTEST",
                "backtest.replay.enabled", "true",
                List.of("paper.return_backfill.enabled"),
                ctx -> ctx.enabled() ? FeatureRuntimeMode.TRACE_ONLY : FeatureRuntimeMode.OFF,
                false,
                "Historical replay/reporting only; no live execution path.",
                "Read/report path only."));
        defs.add(new FeatureDefinition(
                "AIScoreMerge", "AI score merge / default reweight", "AI",
                "final_decision.ai_default_reweight.enabled", "true",
                List.of("scoring.java_weight", "scoring.claude_weight", "scoring.codex_weight"),
                ctx -> ctx.enabled() ? FeatureRuntimeMode.LIVE : FeatureRuntimeMode.OFF,
                true,
                "Changes final score weighting when AI scores are defaults; affects ranking/decision selection.",
                "Live scoring feature; must be visible in decision snapshots."));
        defs.add(new FeatureDefinition(
                "CodexOverlay", "Codex review / readiness overlay", "AI",
                "scoring.enable_codex_review", "true",
                List.of("final_decision.require_codex", "final_decision.require_codex.session_aware", "ai.task.codex.timeout.minutes"),
                ctx -> ctx.enabled() ? FeatureRuntimeMode.LIVE : FeatureRuntimeMode.OFF,
                true,
                "Codex score/readiness participates in scoring or may downgrade decisions when required.",
                "Live AI overlay; stale Codex should be reflected via AI readiness / decision snapshot."));
        return List.copyOf(defs);
    }

    private record FeatureContext(boolean enabled, String primaryConfigValue, Map<String, String> supportingConfigValues) {}

    private record FeatureDefinition(
            String featureKey,
            String displayName,
            String category,
            String primaryConfigKey,
            String defaultConfigValue,
            List<String> supportingConfigKeys,
            Function<FeatureContext, FeatureRuntimeMode> modeResolver,
            boolean canAffectLiveDecision,
            String decisionImpact,
            String safetyNote
    ) {}
}
