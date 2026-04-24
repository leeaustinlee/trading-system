package com.austin.trading.dto.internal;

import com.austin.trading.domain.enums.CrowdingRisk;
import com.austin.trading.domain.enums.RotationSignal;
import com.austin.trading.domain.enums.ThemeRole;
import com.austin.trading.domain.enums.TrendStage;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.util.List;

/**
 * v2 Theme Engine：經 Codex snapshot + Claude 題材研究合併後、per-candidate 的題材上下文。
 *
 * <p>對應 user 請求的「CandidateThemeContextDto」語意。PR3 才會把 Claude 側的
 * {@code themeFitScore / themeDoubt / themeRotationRisk / stockSpecificCatalyst / riskNotes}
 * 填進來；PR1 只定義欄位並保留 null。</p>
 *
 * <p>規則：Claude 不得覆寫 {@code themeStrength}（spec §3）— 這個值永遠來自 Codex
 * 的 deterministic formula。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ThemeContextDto(
        // ── 主題歸屬 ─────────────────────────────────────────────
        String symbol,
        String themeTag,

        // ── Codex snapshot-sourced（不可由 Claude 覆寫）────────────
        BigDecimal themeStrength,
        TrendStage trendStage,
        RotationSignal rotationSignal,
        BigDecimal sustainabilityScore,
        BigDecimal freshnessScore,
        CrowdingRisk crowdingRisk,
        BigDecimal confidence,
        List<String> evidenceSources,

        // ── Claude-sourced（PR3 合併，PR1 保留 null）──────────────
        ThemeRole themeRole,
        BigDecimal themeFitScore,
        BigDecimal themeDoubt,
        BigDecimal themeRotationRisk,
        String stockSpecificCatalyst,
        List<String> riskNotes
) {
    /** 判斷是否有完整 Codex 側資料（進入 PR2 gate 的最低要求）。 */
    public boolean hasMinimumCodexFields() {
        return themeTag != null && !themeTag.isBlank()
                && themeStrength != null
                && trendStage != null && trendStage != TrendStage.UNKNOWN
                && rotationSignal != null && rotationSignal != RotationSignal.UNKNOWN;
    }
}
