package com.austin.trading.service;

import com.austin.trading.config.ThemeSnapshotProperties;
import com.austin.trading.domain.enums.DecisionDiffType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/**
 * v2 Theme Engine PR6：Shadow report / Live override 的 LINE 摘要 formatter。
 *
 * <p>預設關閉（{@code theme.line.summary.enabled=false}）。本 service <strong>不實際發送 LINE</strong>
 * （符合 CLAUDE.md 「最終通知只由 Codex 發送」規則），只負責格式化並 log 輸出，由外部決定是否轉發。</p>
 *
 * <p>單獨關掉此旗標即可在保留 override / shadow 功能的情況下抑制 LINE 噪音（rollback plumbing）。</p>
 */
@Service
public class ThemeLineSummaryService {

    private static final Logger log = LoggerFactory.getLogger(ThemeLineSummaryService.class);

    private final ThemeSnapshotProperties properties;

    public ThemeLineSummaryService(ThemeSnapshotProperties properties) {
        this.properties = properties;
    }

    /**
     * 格式化每日 shadow summary（diff 分類 count + 前幾筆衝突）與 PR6 live override 摘要。
     *
     * @param tradingDate                     交易日
     * @param shadowReport                    {@link ThemeShadowReportService.ReportResult}；null 表示未跑
     * @param liveDecisionOverride            {@link ThemeLiveDecisionService.Result}；null 表示未跑 / passthrough
     * @return  若 flag 關閉或無資料可呈現，回 {@code Optional.empty()}；否則回純文字摘要
     */
    public java.util.Optional<String> formatDailySummary(
            LocalDate tradingDate,
            ThemeShadowReportService.ReportResult shadowReport,
            ThemeLiveDecisionService.Result liveDecisionOverride) {
        if (!properties.lineSummaryEnabled()) {
            return java.util.Optional.empty();
        }
        boolean hasShadow = shadowReport != null && shadowReport.active() && shadowReport.totalCandidates() > 0;
        boolean hasOverride = liveDecisionOverride != null && liveDecisionOverride.changed();
        if (!hasShadow && !hasOverride) {
            return java.util.Optional.empty();
        }

        StringBuilder sb = new StringBuilder();
        sb.append("🧭 Theme Engine 摘要 — ").append(tradingDate).append('\n');

        if (hasShadow) {
            sb.append("📊 Shadow Diff（total=").append(shadowReport.totalCandidates()).append("）\n");
            appendCountLine(sb, "  同買", shadowReport.counts().getOrDefault(DecisionDiffType.SAME_BUY, 0));
            appendCountLine(sb, "  同停", shadowReport.counts().getOrDefault(DecisionDiffType.SAME_WAIT, 0));
            appendCountLine(sb, "  L買T擋", shadowReport.counts().getOrDefault(DecisionDiffType.LEGACY_BUY_THEME_BLOCK, 0));
            appendCountLine(sb, "  L停T買", shadowReport.counts().getOrDefault(DecisionDiffType.LEGACY_WAIT_THEME_BUY, 0));
            appendCountLine(sb, "  雙擋", shadowReport.counts().getOrDefault(DecisionDiffType.BOTH_BLOCK, 0));
            appendCountLine(sb, "  需檢視", shadowReport.counts().getOrDefault(DecisionDiffType.CONFLICT_REVIEW_REQUIRED, 0));
            sb.append("  avgDiff: ").append(fmt(shadowReport.avgScoreDiff()))
              .append("  p90|Δ|: ").append(fmt(shadowReport.p90AbsScoreDiff())).append('\n');
        }

        if (hasOverride) {
            sb.append("🛡 Live Override\n");
            sb.append("  ").append(liveDecisionOverride.legacyFinalDecisionCode())
              .append(" → ").append(liveDecisionOverride.finalDecisionCode())
              .append("（removed ").append(liveDecisionOverride.removedSymbols().size()).append("）\n");
            int limit = Math.min(5, liveDecisionOverride.removedSymbols().size());
            for (int i = 0; i < limit; i++) {
                Map<String, Object> r = liveDecisionOverride.removedSymbols().get(i);
                sb.append("  • ").append(r.get("symbol"));
                appendField(sb, r.get("themeTag"), "");
                appendField(sb, r.get("themeStrength"), "S=");
                appendField(sb, r.get("trendStage"), "");
                appendField(sb, r.get("rotationSignal"), "rot=");
                appendField(sb, r.get("crowdingRisk"), "crowd=");
                // vetoReason 最後列（含 gateKey:reason，如 G2_THEME_VETO:THEME_STRENGTH_BELOW_MIN）
                Object veto = r.get("vetoReason");
                if (veto == null) veto = r.get("detail");
                if (veto != null) sb.append("｜").append(veto);
                sb.append('\n');
            }
            if (liveDecisionOverride.removedSymbols().size() > limit) {
                sb.append("  …（還有 ").append(liveDecisionOverride.removedSymbols().size() - limit).append(" 筆）\n");
            }
        }

        sb.append("來源：Java Theme Engine v2（trace）");
        String text = sb.toString();
        log.info("[ThemeLineSummary] formatted summary date={}\n{}", tradingDate, text);
        return java.util.Optional.of(text);
    }

    private static void appendCountLine(StringBuilder sb, String label, int count) {
        sb.append(label).append("：").append(count).append('\n');
    }

    /** 若值非 null 則附在分隔符後；前綴可為空字串（用於 themeTag 等直接呈現的欄位）。 */
    private static void appendField(StringBuilder sb, Object value, String prefix) {
        if (value == null) return;
        String s = value.toString();
        if (s.isBlank() || "null".equalsIgnoreCase(s)) return;
        sb.append("｜").append(prefix).append(s);
    }

    private static String fmt(BigDecimal v) {
        return v == null ? "—" : v.toPlainString();
    }

}
