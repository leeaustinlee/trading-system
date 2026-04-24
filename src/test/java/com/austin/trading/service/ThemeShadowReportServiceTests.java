package com.austin.trading.service;

import com.austin.trading.config.ThemeSnapshotProperties;
import com.austin.trading.domain.enums.DecisionDiffType;
import com.austin.trading.entity.ThemeShadowDailyReportEntity;
import com.austin.trading.entity.ThemeShadowDecisionLogEntity;
import com.austin.trading.repository.ThemeShadowDailyReportRepository;
import com.austin.trading.repository.ThemeShadowDecisionLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * v2 Theme Engine PR5：ThemeShadowReportService 彙總、檔案、WSL path override 測試。
 */
class ThemeShadowReportServiceTests {

    private ThemeSnapshotProperties props;
    private ThemeShadowDecisionLogRepository logRepo;
    private ThemeShadowDailyReportRepository reportRepo;
    private ThemeShadowReportService service;

    private static final LocalDate TODAY = LocalDate.of(2026, 4, 24);

    @TempDir
    Path tmpDir;

    @BeforeEach
    void setUp() {
        props = mock(ThemeSnapshotProperties.class);
        logRepo = mock(ThemeShadowDecisionLogRepository.class);
        reportRepo = mock(ThemeShadowDailyReportRepository.class);
        when(props.shadowModeEnabled()).thenReturn(true);
        when(props.shadowReportPath()).thenReturn(tmpDir.toString());
        when(reportRepo.findByTradingDate(any())).thenReturn(Optional.empty());
        when(reportRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        service = new ThemeShadowReportService(props, logRepo, reportRepo);
    }

    // ── flag / empty ─────────────────────────────────────────────────

    @Test
    void flagDisabled_noWork() {
        when(props.shadowModeEnabled()).thenReturn(false);
        var r = service.generateDaily(TODAY);
        assertThat(r.active()).isFalse();
        verifyNoInteractions(logRepo, reportRepo);
    }

    @Test
    void noEntries_returnsEmpty() {
        when(logRepo.findByTradingDate(TODAY)).thenReturn(List.of());
        var r = service.generateDaily(TODAY);
        assertThat(r.totalCandidates()).isZero();
        assertThat(r.jsonPath()).isNull();
    }

    // ── 彙總計算 ─────────────────────────────────────────────────────

    @Test
    void aggregates_counts_avg_p90_correctly() {
        when(logRepo.findByTradingDate(TODAY)).thenReturn(List.of(
                entry("2454", "SAME_BUY",            new BigDecimal("0.1")),
                entry("2330", "SAME_BUY",            new BigDecimal("0.2")),
                entry("3017", "LEGACY_BUY_THEME_BLOCK", new BigDecimal("-1.5")),
                entry("3661", "CONFLICT_REVIEW_REQUIRED", new BigDecimal("0.8")),
                entry("2382", "BOTH_BLOCK",          new BigDecimal("-0.4")),
                entry("1101", "SAME_WAIT",           null)
        ));

        var r = service.generateDaily(TODAY);

        assertThat(r.totalCandidates()).isEqualTo(6);
        assertThat(r.counts().get(DecisionDiffType.SAME_BUY)).isEqualTo(2);
        assertThat(r.counts().get(DecisionDiffType.LEGACY_BUY_THEME_BLOCK)).isEqualTo(1);
        assertThat(r.counts().get(DecisionDiffType.CONFLICT_REVIEW_REQUIRED)).isEqualTo(1);
        assertThat(r.counts().get(DecisionDiffType.BOTH_BLOCK)).isEqualTo(1);
        assertThat(r.counts().get(DecisionDiffType.SAME_WAIT)).isEqualTo(1);

        // avg = (0.1 + 0.2 - 1.5 + 0.8 - 0.4) / 5 = -0.16
        assertThat(r.avgScoreDiff()).isEqualByComparingTo(new BigDecimal("-0.160"));
        // |diffs| sorted: 0.1, 0.2, 0.4, 0.8, 1.5；n=5；idx = ceil(0.9*5)-1 = 4 → 1.5
        assertThat(r.p90AbsScoreDiff()).isEqualByComparingTo(new BigDecimal("1.500"));
    }

    // ── 檔案寫入 ─────────────────────────────────────────────────────

    @Test
    void writesJsonAndMarkdownFiles() throws Exception {
        when(logRepo.findByTradingDate(TODAY)).thenReturn(List.of(
                entry("2454", "LEGACY_BUY_THEME_BLOCK", new BigDecimal("-1.2"))
        ));

        var r = service.generateDaily(TODAY);

        assertThat(r.jsonPath()).isNotNull().endsWith("theme-shadow-2026-04-24.json");
        assertThat(r.markdownPath()).isNotNull().endsWith("theme-shadow-2026-04-24.md");
        Path json = Path.of(r.jsonPath());
        Path md = Path.of(r.markdownPath());
        assertThat(Files.exists(json)).isTrue();
        assertThat(Files.exists(md)).isTrue();
        String jsonContent = Files.readString(json);
        String mdContent = Files.readString(md);
        assertThat(jsonContent)
                .contains("\"totalCandidates\" : 1")
                .contains("LEGACY_BUY_THEME_BLOCK")
                .contains("\"legacy_buy_theme_block_count\" : 1");
        assertThat(mdContent)
                .contains("Theme Engine Shadow Report — 2026-04-24")
                .contains("LEGACY_BUY_THEME_BLOCK");
    }

    @Test
    void wslPathOverride_isRespected() throws Exception {
        Path wsl = tmpDir.resolve("wsl-override");
        when(props.shadowReportPath()).thenReturn(wsl.toString());
        when(logRepo.findByTradingDate(TODAY)).thenReturn(List.of(
                entry("2454", "SAME_BUY", new BigDecimal("0.1"))
        ));

        var r = service.generateDaily(TODAY);

        assertThat(r.jsonPath()).startsWith(wsl.toString());
        assertThat(Files.exists(Path.of(r.jsonPath()))).isTrue();
    }

    @Test
    void topConflicts_prioritizesLegacyBuyThemeBlockThenCONFLICTREVIEW() {
        when(logRepo.findByTradingDate(TODAY)).thenReturn(List.of(
                // SAME_BUY/SAME_WAIT 應被過濾
                entry("A1", "SAME_BUY", new BigDecimal("0.5")),
                // LEGACY_BUY_THEME_BLOCK 最高優先
                entry("A2", "LEGACY_BUY_THEME_BLOCK", new BigDecimal("-0.3")),
                entry("A3", "LEGACY_BUY_THEME_BLOCK", new BigDecimal("-2.0")),  // 同類取大 diff
                // 次之 CONFLICT_REVIEW_REQUIRED
                entry("A4", "CONFLICT_REVIEW_REQUIRED", new BigDecimal("1.1"))
        ));

        var r = service.generateDaily(TODAY);

        // 落檔檢查 markdown 有 A3 最前（同類內差距最大）
        String md = Files.exists(Path.of(r.markdownPath()))
                ? readFile(r.markdownPath()) : "";
        int idxA3 = md.indexOf(" A3 ");
        int idxA2 = md.indexOf(" A2 ");
        int idxA4 = md.indexOf(" A4 ");
        assertThat(idxA3).isGreaterThan(-1);
        assertThat(idxA2).isGreaterThan(-1);
        assertThat(idxA4).isGreaterThan(-1);
        assertThat(idxA3).isLessThan(idxA2);    // 同 priority 內 A3 diff 較大排前
        assertThat(idxA2).isLessThan(idxA4);    // LEGACY_BUY_THEME_BLOCK 整批在 CONFLICT 之前
        assertThat(md).doesNotContain(" A1 "); // SAME_BUY 被過濾
    }

    @Test
    void upsert_dailyReport_entity() {
        when(logRepo.findByTradingDate(TODAY)).thenReturn(List.of(
                entry("2454", "SAME_BUY", new BigDecimal("0.0"))
        ));
        ThemeShadowDailyReportEntity existing = new ThemeShadowDailyReportEntity();
        existing.setTradingDate(TODAY);
        when(reportRepo.findByTradingDate(TODAY)).thenReturn(Optional.of(existing));

        var r = service.generateDaily(TODAY);

        assertThat(r.persisted()).isNotNull();
        // 同一 entity reference 被更新（非新建）
        assertThat(r.persisted()).isSameAs(existing);
        assertThat(existing.getSameBuyCount()).isEqualTo(1);
        assertThat(existing.getTotalCandidates()).isEqualTo(1);
    }

    // ══════════════════════════════════════════════════════════════════
    // helpers
    // ══════════════════════════════════════════════════════════════════

    private static ThemeShadowDecisionLogEntity entry(String symbol, String diffType, BigDecimal scoreDiff) {
        ThemeShadowDecisionLogEntity e = new ThemeShadowDecisionLogEntity();
        e.setTradingDate(TODAY);
        e.setSymbol(symbol);
        e.setLegacyDecision(diffType.startsWith("LEGACY_BUY") || "SAME_BUY".equals(diffType)
                            || "CONFLICT_REVIEW_REQUIRED".equals(diffType) ? "ENTER" : "WAIT");
        e.setThemeDecision(diffType.contains("THEME_BUY") || "SAME_BUY".equals(diffType)
                            ? "PASS"
                            : diffType.contains("BLOCK") ? "BLOCK" : "WAIT");
        e.setLegacyFinalScore(new BigDecimal("7.0"));
        e.setThemeFinalScore(scoreDiff == null ? null : new BigDecimal("7.0").add(scoreDiff));
        e.setScoreDiff(scoreDiff);
        e.setDecisionDiffType(diffType);
        e.setThemeVetoReason(diffType.contains("BLOCK") ? "G2_THEME_VETO:SAMPLE" : null);
        return e;
    }

    private static String readFile(String path) {
        try { return Files.readString(Path.of(path)); }
        catch (Exception e) { return ""; }
    }
}
