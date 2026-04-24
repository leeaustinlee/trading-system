package com.austin.trading.service;

import com.austin.trading.domain.enums.CrowdingRisk;
import com.austin.trading.domain.enums.RotationSignal;
import com.austin.trading.domain.enums.TrendStage;
import com.austin.trading.dto.internal.ThemeSnapshotV2Dto;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * v2 Theme Engine PR2：輕量 schema 驗證（不引入 JSON Schema 套件，手動檢查必要欄位與值域）。
 *
 * <p>設計原則：</p>
 * <ul>
 *   <li>回傳 {@link Result} 而非拋例外 — 呼叫方可依 validation flag + fallback flag 決定後續行為</li>
 *   <li>PR2 僅做 per-theme 結構驗證；evidence_sources 合法值比對、範圍（0-10, 0-1）、enum 合法性</li>
 *   <li>parse 時 Jackson 已寬鬆處理未知 enum（落到 UNKNOWN），這裡只驗證原始 JSON 字串是否在 spec 允許集合內</li>
 *   <li>Full schema 參考 {@code src/main/resources/schema/theme-snapshot-v2.schema.json}</li>
 * </ul>
 */
@Service
public class ThemeSnapshotValidationService {

    private static final Set<String> ALLOWED_TREND_STAGES =
            Set.of("EARLY", "MID", "LATE");

    private static final Set<String> ALLOWED_ROTATION_SIGNALS =
            Set.of("IN", "OUT", "NONE");

    private static final Set<String> ALLOWED_CROWDING_RISKS =
            Set.of("LOW", "MID", "HIGH");

    private static final Set<String> ALLOWED_EVIDENCE_SOURCES =
            Set.of("GAINER_BOARD", "VOLUME_BOARD", "NEWS", "INSTITUTIONAL_FLOW", "INDUSTRY_EVENT");

    private static final Set<String> ALLOWED_ROLE_HINTS =
            Set.of("LEADER", "FOLLOWER", "LAGGARD", "UNKNOWN");

    public Result validate(ThemeSnapshotV2Dto snapshot) {
        List<String> errors = new ArrayList<>();
        if (snapshot == null) {
            errors.add("snapshot=null");
            return new Result(false, errors);
        }

        if (snapshot.generatedAt() == null) {
            errors.add("generated_at missing");
        }

        if (snapshot.marketRegime() == null) {
            errors.add("market_regime missing");
        } else {
            if (isBlank(snapshot.marketRegime().regimeType())) {
                errors.add("market_regime.regime_type missing");
            }
            if (snapshot.marketRegime().riskMultiplier() == null) {
                errors.add("market_regime.risk_multiplier missing");
            }
            if (snapshot.marketRegime().tradeAllowed() == null) {
                errors.add("market_regime.trade_allowed missing");
            }
        }

        if (snapshot.themes() == null) {
            errors.add("themes array missing");
        } else {
            for (int i = 0; i < snapshot.themes().size(); i++) {
                validateTheme(snapshot.themes().get(i), i, errors);
            }
        }

        return new Result(errors.isEmpty(), Collections.unmodifiableList(errors));
    }

    private void validateTheme(ThemeSnapshotV2Dto.Theme theme, int idx, List<String> errors) {
        String prefix = "themes[" + idx + "].";
        if (theme == null) {
            errors.add(prefix + "null");
            return;
        }
        if (isBlank(theme.themeTag())) {
            errors.add(prefix + "theme_tag missing");
        }
        validateRangeBd(theme.themeStrength(), 0, 10, prefix + "theme_strength", errors);
        validateEnum(theme.trendStage(), ALLOWED_TREND_STAGES, prefix + "trend_stage", errors);
        validateEnum(theme.rotationSignal(), ALLOWED_ROTATION_SIGNALS, prefix + "rotation_signal", errors);
        validateRangeBd(theme.sustainabilityScore(), 0, 10, prefix + "sustainability_score", errors);
        validateRangeBd(theme.freshnessScore(), 0, 10, prefix + "freshness_score", errors);
        validateEnum(theme.crowdingRisk(), ALLOWED_CROWDING_RISKS, prefix + "crowding_risk", errors);
        validateRangeBd(theme.confidence(), 0, 1, prefix + "confidence", errors);

        if (theme.evidenceSources() == null || theme.evidenceSources().isEmpty()) {
            errors.add(prefix + "evidence_sources must be non-empty");
        } else {
            for (int j = 0; j < theme.evidenceSources().size(); j++) {
                String source = theme.evidenceSources().get(j);
                if (!ALLOWED_EVIDENCE_SOURCES.contains(source)) {
                    errors.add(prefix + "evidence_sources[" + j + "]=" + source + " (not in allowed set)");
                }
            }
        }

        // Cross-check：驗證後，enum getter 不應回 UNKNOWN — 若回 UNKNOWN，代表原始值不在合法集合。
        // 已由 validateEnum 擋掉，此處額外一層防護。
        if (theme.trendStageEnum() == TrendStage.UNKNOWN) {
            errors.add(prefix + "trend_stage unparseable");
        }
        if (theme.rotationSignalEnum() == RotationSignal.UNKNOWN) {
            errors.add(prefix + "rotation_signal unparseable");
        }
        if (theme.crowdingRiskEnum() == CrowdingRisk.UNKNOWN) {
            errors.add(prefix + "crowding_risk unparseable");
        }

        // candidates optional; 若存在每筆驗 symbol + role_hint + confidence
        if (theme.candidates() != null) {
            for (int c = 0; c < theme.candidates().size(); c++) {
                ThemeSnapshotV2Dto.ThemeCandidate cand = theme.candidates().get(c);
                String cp = prefix + "candidates[" + c + "].";
                if (cand == null) { errors.add(cp + "null"); continue; }
                if (isBlank(cand.symbol())) errors.add(cp + "symbol missing");
                if (cand.roleHint() != null && !ALLOWED_ROLE_HINTS.contains(cand.roleHint())) {
                    errors.add(cp + "role_hint=" + cand.roleHint() + " (not in allowed set)");
                }
                validateRangeBd(cand.confidence(), 0, 1, cp + "confidence", errors);
            }
        }
    }

    private void validateEnum(String value, Set<String> allowed, String field, List<String> errors) {
        if (value == null || value.isBlank()) {
            errors.add(field + " missing");
            return;
        }
        if (!allowed.contains(value)) {
            errors.add(field + "=" + value + " (not in " + allowed + ")");
        }
    }

    private void validateRangeBd(BigDecimal v, double min, double max, String field, List<String> errors) {
        if (v == null) {
            errors.add(field + " missing");
            return;
        }
        double d = v.doubleValue();
        if (d < min || d > max) {
            errors.add(field + "=" + v + " out of [" + min + "," + max + "]");
        }
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    public record Result(boolean valid, List<String> errors) {
        public String summary() {
            if (valid) return "OK";
            return "INVALID: " + String.join("; ", errors);
        }
    }
}
