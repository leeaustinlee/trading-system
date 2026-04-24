package com.austin.trading.service;

import com.austin.trading.domain.enums.CrowdingRisk;
import com.austin.trading.domain.enums.RotationSignal;
import com.austin.trading.domain.enums.ThemeRole;
import com.austin.trading.domain.enums.TrendStage;
import com.austin.trading.dto.internal.ClaudeThemeResearchOutputDto;
import com.austin.trading.dto.internal.ClaudeThemeResearchOutputDto.SymbolResearch;
import com.austin.trading.dto.internal.ThemeContextDto;
import com.austin.trading.dto.internal.ThemeSnapshotV2Dto;
import com.austin.trading.dto.internal.ThemeSnapshotV2Dto.Theme;
import com.austin.trading.dto.internal.ThemeSnapshotV2Dto.ThemeCandidate;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ThemeContextMergeServiceTests {

    private final ThemeContextMergeService svc = new ThemeContextMergeService();

    // ══════════════════════════════════════════════════════════════════

    @Test
    void nullSnapshot_returnsEmptyResult_noException() {
        ThemeContextMergeService.MergeResult r = svc.merge(null, null);
        assertThat(r.contexts()).isEmpty();
        assertThat(r.trace()).containsEntry("contextCount", 0);
    }

    @Test
    void snapshotOnly_claudeNull_contextHasCodexFieldsOnly() {
        ThemeSnapshotV2Dto snapshot = snapshot(
                theme("AI_SERVER", "MID", "IN", "MID",
                        cand("2454", "LEADER")));
        ThemeContextMergeService.MergeResult r = svc.merge(snapshot, null);

        assertThat(r.contexts()).hasSize(1);
        ThemeContextDto ctx = r.contexts().get(0);
        assertThat(ctx.symbol()).isEqualTo("2454");
        assertThat(ctx.themeTag()).isEqualTo("AI_SERVER");
        assertThat(ctx.themeStrength()).isEqualByComparingTo(new BigDecimal("7.33"));
        assertThat(ctx.trendStage()).isEqualTo(TrendStage.MID);
        assertThat(ctx.rotationSignal()).isEqualTo(RotationSignal.IN);
        assertThat(ctx.crowdingRisk()).isEqualTo(CrowdingRisk.MID);
        assertThat(ctx.themeRole()).isEqualTo(ThemeRole.LEADER);

        // Claude 側欄位全為 null
        assertThat(ctx.themeFitScore()).isNull();
        assertThat(ctx.themeDoubt()).isNull();
        assertThat(ctx.themeRotationRisk()).isNull();
        assertThat(ctx.stockSpecificCatalyst()).isNull();
        assertThat(ctx.riskNotes()).isNull();

        assertThat(r.warnings()).isEmpty();
        assertThat(r.rejectedClaudeEntries()).isEmpty();
    }

    @Test
    void claudeDataMatches_populatesAllClaudeFields() {
        ThemeSnapshotV2Dto snapshot = snapshot(
                theme("AI_SERVER", "MID", "IN", "MID",
                        cand("2454", "LEADER")));
        ClaudeThemeResearchOutputDto claude = claude(
                research("2454", "AI_SERVER", "LEADER",
                        new BigDecimal("8.5"), new BigDecimal("2.0"), new BigDecimal("3.0"),
                        "法說後訂單能見度高", List.of("geo-politics"),
                        null /* no theme_strength overwrite */));

        ThemeContextMergeService.MergeResult r = svc.merge(snapshot, claude);
        ThemeContextDto ctx = r.contexts().get(0);

        assertThat(ctx.themeFitScore()).isEqualByComparingTo(new BigDecimal("8.5"));
        assertThat(ctx.themeDoubt()).isEqualByComparingTo(new BigDecimal("2.0"));
        assertThat(ctx.themeRotationRisk()).isEqualByComparingTo(new BigDecimal("3.0"));
        assertThat(ctx.stockSpecificCatalyst()).isEqualTo("法說後訂單能見度高");
        assertThat(ctx.riskNotes()).containsExactly("geo-politics");
        assertThat(ctx.themeRole()).isEqualTo(ThemeRole.LEADER);
        assertThat(r.warnings()).isEmpty();
    }

    @Test
    void claudeTriesToOverrideThemeStrength_ignored_warningRecorded() {
        ThemeSnapshotV2Dto snapshot = snapshot(
                theme("AI_SERVER", "MID", "IN", "MID",
                        cand("2454", "LEADER")));
        // Claude 回 theme_strength=9.9，merge 必須忽略並記 warning
        ClaudeThemeResearchOutputDto claude = claude(
                research("2454", "AI_SERVER", "LEADER",
                        new BigDecimal("8.5"), null, null, null, null,
                        new BigDecimal("9.9")));

        ThemeContextMergeService.MergeResult r = svc.merge(snapshot, claude);

        ThemeContextDto ctx = r.contexts().get(0);
        // 權威仍是 snapshot 的 7.33，不是 Claude 的 9.9
        assertThat(ctx.themeStrength()).isEqualByComparingTo(new BigDecimal("7.33"));

        // warning 包含 IGNORED key + symbol|theme + claudeValue
        assertThat(r.warnings()).hasSize(1);
        String w = r.warnings().get(0);
        assertThat(w).contains("IGNORED_CLAUDE_THEME_STRENGTH_OVERRIDE")
                .contains("2454|AI_SERVER")
                .contains("claudeValue=9.9");
    }

    @Test
    void claudeSymbolNotInSnapshot_rejected_doesNotFailOthers() {
        ThemeSnapshotV2Dto snapshot = snapshot(
                theme("AI_SERVER", "MID", "IN", "MID",
                        cand("2454", "LEADER")));
        ClaudeThemeResearchOutputDto claude = claude(
                research("2454", "AI_SERVER", null, null, null, null, null, null, null),
                research("9999", "UNKNOWN_THEME", null, null, null, null, null, null, null));

        ThemeContextMergeService.MergeResult r = svc.merge(snapshot, claude);

        // 2454 對齊成功，產出 1 筆 context
        assertThat(r.contexts()).hasSize(1);
        assertThat(r.contexts().get(0).symbol()).isEqualTo("2454");
        // 9999 對不上 → rejected
        assertThat(r.rejectedClaudeEntries()).hasSize(1);
        assertThat(r.rejectedClaudeEntries().get(0))
                .contains("REJECT_NO_SNAPSHOT_MATCH")
                .contains("9999|UNKNOWN_THEME");
    }

    @Test
    void claudeEntryMissingSymbolOrTheme_rejectedIndividually() {
        ThemeSnapshotV2Dto snapshot = snapshot(
                theme("AI_SERVER", "MID", "IN", "MID",
                        cand("2454", "LEADER")));
        ClaudeThemeResearchOutputDto claude = claude(
                research("2454", "AI_SERVER", null, null, null, null, null, null, null),
                research(null, "SOMETHING", null, null, null, null, null, null, null),   // missing symbol
                research("3035", null, null, null, null, null, null, null, null)          // missing theme_tag
        );

        ThemeContextMergeService.MergeResult r = svc.merge(snapshot, claude);
        assertThat(r.contexts()).hasSize(1);
        assertThat(r.rejectedClaudeEntries()).hasSize(2);
        assertThat(r.rejectedClaudeEntries())
                .anyMatch(s -> s.contains("REJECT_MISSING_SYMBOL_OR_THEME_TAG"));
    }

    @Test
    void sameSymbolInMultipleThemes_producesMultipleContexts() {
        ThemeSnapshotV2Dto snapshot = snapshot(
                theme("AI_SERVER", "MID", "IN", "MID", cand("2454", "LEADER")),
                theme("SEMI",      "EARLY", "IN", "LOW", cand("2454", "FOLLOWER")));
        ClaudeThemeResearchOutputDto claude = claude(
                research("2454", "AI_SERVER", "LEADER",
                        new BigDecimal("8.5"), null, null, null, null, null),
                research("2454", "SEMI", "FOLLOWER",
                        new BigDecimal("7.0"), null, null, null, null, null));

        ThemeContextMergeService.MergeResult r = svc.merge(snapshot, claude);

        assertThat(r.contexts()).hasSize(2);
        Optional<ThemeContextDto> aiCtx = r.findBySymbolAndTheme("2454", "AI_SERVER");
        Optional<ThemeContextDto> semiCtx = r.findBySymbolAndTheme("2454", "SEMI");
        assertThat(aiCtx).isPresent();
        assertThat(semiCtx).isPresent();
        assertThat(aiCtx.get().themeRole()).isEqualTo(ThemeRole.LEADER);
        assertThat(semiCtx.get().themeRole()).isEqualTo(ThemeRole.FOLLOWER);
        assertThat(aiCtx.get().themeFitScore()).isEqualByComparingTo(new BigDecimal("8.5"));
        assertThat(semiCtx.get().themeFitScore()).isEqualByComparingTo(new BigDecimal("7.0"));
    }

    @Test
    void snapshotRoleUnknown_claudeFills_roleUpgraded() {
        ThemeSnapshotV2Dto snapshot = snapshot(
                theme("AI_SERVER", "MID", "IN", "MID",
                        cand("2454", "UNKNOWN")));   // snapshot 未分類
        ClaudeThemeResearchOutputDto claude = claude(
                research("2454", "AI_SERVER", "LEADER",
                        null, null, null, null, null, null));

        ThemeContextMergeService.MergeResult r = svc.merge(snapshot, claude);
        assertThat(r.contexts().get(0).themeRole()).isEqualTo(ThemeRole.LEADER);
    }

    @Test
    void snapshotRoleKnown_claudeDifferentRole_snapshotWins() {
        ThemeSnapshotV2Dto snapshot = snapshot(
                theme("AI_SERVER", "MID", "IN", "MID",
                        cand("2454", "LEADER")));
        // Claude 說是 LAGGARD，但 snapshot 已明確 LEADER → snapshot 勝
        ClaudeThemeResearchOutputDto claude = claude(
                research("2454", "AI_SERVER", "LAGGARD",
                        null, null, null, null, null, null));

        ThemeContextMergeService.MergeResult r = svc.merge(snapshot, claude);
        assertThat(r.contexts().get(0).themeRole()).isEqualTo(ThemeRole.LEADER);
    }

    @Test
    void traceSummary_countsAreAccurate() {
        ThemeSnapshotV2Dto snapshot = snapshot(
                theme("AI_SERVER", "MID", "IN", "MID", cand("2454", "LEADER")));
        ClaudeThemeResearchOutputDto claude = claude(
                research("2454", "AI_SERVER", null, null, null, null, null, null,
                        new BigDecimal("9.0")),     // 會產 warning
                research("BAD", "BAD", null, null, null, null, null, null, null));    // 會 reject

        ThemeContextMergeService.MergeResult r = svc.merge(snapshot, claude);
        assertThat(r.trace()).containsEntry("contextCount", 1);
        assertThat(r.trace()).containsEntry("warningCount", 1);
        assertThat(r.trace()).containsEntry("rejectedClaudeCount", 1);
        assertThat(r.trace()).containsEntry("claudeProvided", true);
    }

    // ═══════════════════════════════════════════════════════════════
    // helpers
    // ═══════════════════════════════════════════════════════════════

    private ThemeSnapshotV2Dto snapshot(Theme... themes) {
        return new ThemeSnapshotV2Dto(
                OffsetDateTime.now(),
                new ThemeSnapshotV2Dto.MarketRegime("BULL_TREND", new BigDecimal("1.0"), true),
                List.of(themes)
        );
    }

    private Theme theme(String tag, String stage, String rot, String crowd, ThemeCandidate... cands) {
        return new Theme(
                tag,
                new BigDecimal("7.33"),
                stage, rot,
                new BigDecimal("7.0"), new BigDecimal("7.0"),
                crowd,
                List.of("NEWS"),
                new BigDecimal("0.82"),
                null,
                List.of(cands)
        );
    }

    private ThemeCandidate cand(String symbol, String role) {
        return new ThemeCandidate(symbol, role, new BigDecimal("0.9"));
    }

    private ClaudeThemeResearchOutputDto claude(SymbolResearch... items) {
        return new ClaudeThemeResearchOutputDto(OffsetDateTime.now(), List.of(items));
    }

    private SymbolResearch research(String symbol, String themeTag, String role,
                                      BigDecimal fit, BigDecimal doubt, BigDecimal rotRisk,
                                      String catalyst, List<String> risks,
                                      BigDecimal themeStrengthOverride) {
        return new SymbolResearch(symbol, themeTag, role,
                fit, doubt, rotRisk, catalyst, risks, themeStrengthOverride);
    }
}
