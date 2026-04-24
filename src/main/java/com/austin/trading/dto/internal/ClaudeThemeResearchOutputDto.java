package com.austin.trading.dto.internal;

import com.austin.trading.domain.enums.ThemeRole;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * v2 Theme Engine PR3：Claude 題材研究輸出 DTO（對應 {@code claude-theme-research.json}）。
 *
 * <h3>契約</h3>
 * <ul>
 *   <li>Claude 只補「語意欄位」：{@code theme_fit_score / theme_role / theme_doubt /
 *       theme_rotation_risk / stock_specific_catalyst / risk_notes}</li>
 *   <li>Claude 若填 {@code theme_strength}，{@link com.austin.trading.service.ThemeContextMergeService}
 *       會 <strong>忽略並記 warning</strong>（trace key=
 *       {@code IGNORED_CLAUDE_THEME_STRENGTH_OVERRIDE}）。theme_strength 權威永遠是 Codex snapshot。</li>
 *   <li>一筆 {@link SymbolResearch} 必須同時有 {@code symbol} 與 {@code theme_tag} 才能被 merge；
 *       任一缺失或與 snapshot 對不齊時該筆會被丟到 rejectedClaudeEntries（不影響整包）。</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ClaudeThemeResearchOutputDto(
        OffsetDateTime generatedAt,
        List<SymbolResearch> symbols
) {
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SymbolResearch(
            String symbol,
            String themeTag,
            /** enum string "LEADER"/"FOLLOWER"/"LAGGARD"/"UNKNOWN"；parse 不合法 → UNKNOWN。 */
            String themeRole,
            BigDecimal themeFitScore,
            BigDecimal themeDoubt,
            BigDecimal themeRotationRisk,
            String stockSpecificCatalyst,
            List<String> riskNotes,
            /**
             * ⚠️ 即使 Claude 填了本欄位，merge service 必定忽略並記 warning。
             * 留在 DTO 只為了能 deserialize 不 fail；決策層請勿讀取。
             */
            BigDecimal themeStrength
    ) {
        public ThemeRole themeRoleEnum() { return ThemeRole.parseOrUnknown(themeRole); }

        /** 是否有最低 alignment key（symbol + theme_tag），merge 階段用來過濾無效 entry。 */
        public boolean hasAlignmentKeys() {
            return symbol != null && !symbol.isBlank()
                    && themeTag != null && !themeTag.isBlank();
        }
    }
}
