package com.austin.trading.dto.internal;

import com.austin.trading.domain.enums.DecisionDiffType;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/**
 * v2 Theme Engine Shadow Mode 單筆差異結果（對應 {@code theme-shadow-mode-spec.md §2}）。
 *
 * <p>PR1 為 DTO；PR4/PR5 會接入 FinalDecisionService 雙路徑執行與落庫。</p>
 *
 * <p>user 請求的「ShadowDecisionTraceDto」即此 DTO（名稱對齊 Codex plan）。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ThemeShadowDecisionDto(
        LocalDate tradingDate,
        String symbol,
        String marketRegime,

        // ── 雙路徑分數 ──────────────────────────────────────────
        BigDecimal legacyFinalScore,
        BigDecimal themeFinalScore,
        /** theme_final_score − legacy_final_score。 */
        BigDecimal scoreDiff,

        // ── 雙路徑決策 ──────────────────────────────────────────
        String legacyDecision,     // ENTER / WAIT / REST
        String themeDecision,
        String themeVetoReason,
        DecisionDiffType decisionDiffType,

        // ── Trace（完整 gate 結果；PR4 會填）───────────────────
        Map<String, Object> legacyTrace,
        Map<String, Object> themeTrace
) {
}
