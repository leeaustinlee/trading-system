package com.austin.trading.service;

import com.austin.trading.config.ThemeSnapshotProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ClaudeThemeResearchParserServiceTests {

    private static final ZoneId TPE = ZoneId.of("Asia/Taipei");

    @TempDir Path tempDir;

    private ScoreConfigService scoreConfig;
    private ThemeSnapshotProperties props;
    private ClaudeThemeResearchParserService parser;
    private Map<String, Object> overrides;

    @BeforeEach
    void setUp() {
        scoreConfig = mock(ScoreConfigService.class);
        overrides = new HashMap<>();
        overrides.put("theme.claude.context.merge.enabled", true);
        overrides.put("theme.snapshot.fallback.enabled", true);
        overrides.put("theme.claude.research.max_age_minutes", 120);

        when(scoreConfig.getBoolean(anyString(), anyBoolean())).thenAnswer(inv ->
                overrides.getOrDefault(inv.getArgument(0), inv.getArgument(1)));
        when(scoreConfig.getInt(anyString(), anyInt())).thenAnswer(inv ->
                overrides.getOrDefault(inv.getArgument(0), inv.getArgument(1)));
        when(scoreConfig.getString(eq("theme.claude.research.path"), anyString())).thenAnswer(inv ->
                overrides.getOrDefault("theme.claude.research.path", inv.getArgument(1)));
        when(scoreConfig.getString(any(), any())).thenAnswer(inv ->
                overrides.getOrDefault(inv.getArgument(0), inv.getArgument(1)));

        props = new ThemeSnapshotProperties(scoreConfig);
        parser = new ClaudeThemeResearchParserService(props);
    }

    @Test
    void flagDisabled_returnsDisabled_noFileRead() {
        overrides.put("theme.claude.context.merge.enabled", false);
        ClaudeThemeResearchParserService.LoadResult r = parser.loadCurrent();
        assertThat(r.status()).isEqualTo(ClaudeThemeResearchParserService.Status.DISABLED);
        assertThat(r.data()).isEmpty();
        assertThat(r.traceKey()).isEqualTo(ClaudeThemeResearchParserService.TRACE_KEY_DISABLED);
    }

    @Test
    void fileMissing_noFallback_returnsFileMissing() {
        overrides.put("theme.claude.research.path", tempDir.resolve("ghost.json").toString());
        overrides.put("theme.snapshot.fallback.enabled", false);

        ClaudeThemeResearchParserService.LoadResult r = parser.loadCurrent();
        assertThat(r.status()).isEqualTo(ClaudeThemeResearchParserService.Status.FILE_MISSING);
        assertThat(r.data()).isEmpty();
        assertThat(r.traceKey()).isEqualTo(ClaudeThemeResearchParserService.TRACE_KEY_MISSING);
    }

    @Test
    void freshValidJson_returnsFresh() throws Exception {
        OffsetDateTime genAt = OffsetDateTime.now(TPE).minusMinutes(30);
        Path file = tempDir.resolve("claude.json");
        Files.writeString(file, validClaudeJson(genAt));
        overrides.put("theme.claude.research.path", file.toString());

        ClaudeThemeResearchParserService.LoadResult r = parser.loadCurrent();
        assertThat(r.status()).isEqualTo(ClaudeThemeResearchParserService.Status.FRESH);
        assertThat(r.data()).isPresent();
        assertThat(r.data().get().symbols()).hasSize(2);
        assertThat(r.data().get().symbols().get(0).symbol()).isEqualTo("2454");
        assertThat(r.hasWarning()).isFalse();
    }

    @Test
    void malformedJson_returnsParseError() throws Exception {
        Path file = tempDir.resolve("claude.json");
        Files.writeString(file, "{not json ]");
        overrides.put("theme.claude.research.path", file.toString());
        overrides.put("theme.snapshot.fallback.enabled", false);

        ClaudeThemeResearchParserService.LoadResult r = parser.loadCurrent();
        assertThat(r.status()).isEqualTo(ClaudeThemeResearchParserService.Status.PARSE_ERROR);
        assertThat(r.traceKey()).isEqualTo(ClaudeThemeResearchParserService.TRACE_KEY_PARSE_ERROR);
    }

    @Test
    void missingGeneratedAt_returnsValidationFailed() throws Exception {
        Path file = tempDir.resolve("claude.json");
        // 缺 generated_at，整包 invalid（per user: parse fail / 無 generated_at 才拒整包）
        Files.writeString(file, "{\"symbols\":[]}");
        overrides.put("theme.claude.research.path", file.toString());
        overrides.put("theme.snapshot.fallback.enabled", false);

        ClaudeThemeResearchParserService.LoadResult r = parser.loadCurrent();
        assertThat(r.status()).isEqualTo(ClaudeThemeResearchParserService.Status.VALIDATION_FAILED);
        assertThat(r.traceKey()).isEqualTo(ClaudeThemeResearchParserService.TRACE_KEY_INVALID);
    }

    @Test
    void staleJson_noCached_returnsStaleNoFallback() throws Exception {
        Path file = tempDir.resolve("claude.json");
        Files.writeString(file, validClaudeJson(OffsetDateTime.now(TPE).minusMinutes(180)));
        overrides.put("theme.claude.research.path", file.toString());

        ClaudeThemeResearchParserService.LoadResult r = parser.loadCurrent();
        assertThat(r.status()).isEqualTo(ClaudeThemeResearchParserService.Status.STALE_NO_FALLBACK);
        assertThat(r.data()).isEmpty();
        assertThat(r.traceKey()).isEqualTo(ClaudeThemeResearchParserService.TRACE_KEY_STALE);
    }

    @Test
    void staleJson_withCachedFallback_returnsStaleFallback_andHasWarning() throws Exception {
        Path file = tempDir.resolve("claude.json");
        overrides.put("theme.claude.research.path", file.toString());

        // seed fresh
        Files.writeString(file, validClaudeJson(OffsetDateTime.now(TPE).minusMinutes(10)));
        ClaudeThemeResearchParserService.LoadResult fresh = parser.loadCurrent();
        assertThat(fresh.status()).isEqualTo(ClaudeThemeResearchParserService.Status.FRESH);

        // stale
        Files.writeString(file, validClaudeJson(OffsetDateTime.now(TPE).minusMinutes(180)));
        ClaudeThemeResearchParserService.LoadResult stale = parser.loadCurrent();
        assertThat(stale.status()).isEqualTo(ClaudeThemeResearchParserService.Status.STALE_FALLBACK);
        assertThat(stale.data()).isPresent();
        assertThat(stale.hasWarning()).isTrue();
    }

    @Test
    void claudeOutputWithThemeStrength_isParsedNotRejected() throws Exception {
        // 驗證：Claude 誤帶 theme_strength 不會讓整包 fail；只會在 merge 階段被忽略
        Path file = tempDir.resolve("claude.json");
        String json = "{\"generated_at\":\"" + OffsetDateTime.now(TPE).minusMinutes(5) + "\","
                + "\"symbols\":[{"
                + "\"symbol\":\"2454\",\"theme_tag\":\"AI_SERVER\","
                + "\"theme_role\":\"LEADER\",\"theme_fit_score\":8.5,"
                + "\"theme_strength\":8.0"
                + "}]}";
        Files.writeString(file, json);
        overrides.put("theme.claude.research.path", file.toString());

        ClaudeThemeResearchParserService.LoadResult r = parser.loadCurrent();
        assertThat(r.status()).isEqualTo(ClaudeThemeResearchParserService.Status.FRESH);
        assertThat(r.data().get().symbols().get(0).themeStrength()).isNotNull();
    }

    // ── helpers ──

    private String validClaudeJson(OffsetDateTime generatedAt) {
        return "{\"generated_at\":\"" + generatedAt.toString() + "\","
                + "\"symbols\":["
                + "{\"symbol\":\"2454\",\"theme_tag\":\"AI_SERVER\","
                + "\"theme_role\":\"LEADER\",\"theme_fit_score\":8.5,"
                + "\"theme_doubt\":2.0,\"theme_rotation_risk\":3.0,"
                + "\"stock_specific_catalyst\":\"法說後訂單能見度高\","
                + "\"risk_notes\":[\"geo-politics\"]},"
                + "{\"symbol\":\"3035\",\"theme_tag\":\"AI_SERVER\","
                + "\"theme_role\":\"FOLLOWER\",\"theme_fit_score\":7.2}"
                + "]}";
    }
}
