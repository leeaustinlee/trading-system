package com.austin.trading.service;

import com.austin.trading.config.ThemeSnapshotProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * v2 Theme Engine PR2：{@link ThemeSnapshotService} 端到端行為測試。
 * 覆蓋：flag-off、valid、invalid JSON、stale、missing file、fallback、fresh cache 更新。
 */
class ThemeSnapshotServiceTests {

    private static final ZoneId TPE = ZoneId.of("Asia/Taipei");

    @TempDir Path tempDir;

    private ScoreConfigService scoreConfig;
    private ThemeSnapshotProperties props;
    private ThemeSnapshotValidationService validator;
    private ThemeSnapshotService service;
    private ObjectMapper mapper;

    private Map<String, Object> configOverrides;

    @BeforeEach
    void setUp() {
        scoreConfig = mock(ScoreConfigService.class);
        configOverrides = new HashMap<>();
        configOverrides.put("theme.engine.v2.enabled", true);
        configOverrides.put("theme.snapshot.validation.enabled", true);
        configOverrides.put("theme.snapshot.fallback.enabled", true);
        configOverrides.put("theme.snapshot.max_age_minutes", 30);

        when(scoreConfig.getBoolean(anyString(), anyBoolean())).thenAnswer(inv ->
                configOverrides.getOrDefault(inv.getArgument(0), inv.getArgument(1)));
        when(scoreConfig.getInt(anyString(), anyInt())).thenAnswer(inv ->
                configOverrides.getOrDefault(inv.getArgument(0), inv.getArgument(1)));
        when(scoreConfig.getString(eq("theme.snapshot.path"), anyString())).thenAnswer(inv ->
                configOverrides.getOrDefault("theme.snapshot.path", inv.getArgument(1)));
        when(scoreConfig.getString(any(), any())).thenAnswer(inv ->
                configOverrides.getOrDefault(inv.getArgument(0), inv.getArgument(1)));

        props = new ThemeSnapshotProperties(scoreConfig);
        validator = new ThemeSnapshotValidationService();
        service = new ThemeSnapshotService(props, validator);

        mapper = new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    // ══════════════════════════════════════════════════════════════════

    @Test
    void flagDisabled_returnsDisabledStatus_noFileRead() {
        configOverrides.put("theme.engine.v2.enabled", false);

        ThemeSnapshotService.LoadResult r = service.getCurrentSnapshot();
        assertThat(r.status()).isEqualTo(ThemeSnapshotService.Status.DISABLED);
        assertThat(r.snapshot()).isEmpty();
        assertThat(r.traceKey()).isEqualTo(ThemeSnapshotService.TRACE_KEY_DISABLED);
    }

    @Test
    void fileMissing_noFallback_returnsFileMissing() throws Exception {
        configOverrides.put("theme.snapshot.path", tempDir.resolve("ghost.json").toString());
        configOverrides.put("theme.snapshot.fallback.enabled", false);

        ThemeSnapshotService.LoadResult r = service.getCurrentSnapshot();
        assertThat(r.status()).isEqualTo(ThemeSnapshotService.Status.FILE_MISSING);
        assertThat(r.snapshot()).isEmpty();
        assertThat(r.traceKey()).isEqualTo(ThemeSnapshotService.TRACE_KEY_MISSING);
    }

    @Test
    void freshValidSnapshot_returnsFreshAndCaches() throws Exception {
        String json = validSnapshotJson(OffsetDateTime.now(TPE).minusMinutes(5));
        Path file = tempDir.resolve("theme-snapshot.json");
        Files.writeString(file, json);
        configOverrides.put("theme.snapshot.path", file.toString());

        ThemeSnapshotService.LoadResult r = service.getCurrentSnapshot();
        assertThat(r.status()).isEqualTo(ThemeSnapshotService.Status.FRESH);
        assertThat(r.snapshot()).isPresent();
        assertThat(r.traceKey()).isEqualTo(ThemeSnapshotService.TRACE_KEY_OK);
        assertThat(r.snapshot().get().themes()).hasSize(1);
        assertThat(r.snapshot().get().themes().get(0).themeTag()).isEqualTo("AI_SERVER");
    }

    @Test
    void staleSnapshot_withFallbackNoCache_returnsStaleNoFallback() throws Exception {
        String json = validSnapshotJson(OffsetDateTime.now(TPE).minusMinutes(90));
        Path file = tempDir.resolve("theme-snapshot.json");
        Files.writeString(file, json);
        configOverrides.put("theme.snapshot.path", file.toString());

        // fallback flag on but no cached snapshot → STALE_NO_FALLBACK
        ThemeSnapshotService.LoadResult r = service.getCurrentSnapshot();
        assertThat(r.status()).isEqualTo(ThemeSnapshotService.Status.STALE_NO_FALLBACK);
        assertThat(r.snapshot()).isEmpty();
        assertThat(r.traceKey()).isEqualTo(ThemeSnapshotService.TRACE_KEY_STALE);
    }

    @Test
    void staleSnapshot_withCachedFallback_returnsStaleFallback() throws Exception {
        Path file = tempDir.resolve("theme-snapshot.json");
        configOverrides.put("theme.snapshot.path", file.toString());

        // First load: fresh → cached
        Files.writeString(file, validSnapshotJson(OffsetDateTime.now(TPE).minusMinutes(5)));
        ThemeSnapshotService.LoadResult fresh = service.getCurrentSnapshot();
        assertThat(fresh.status()).isEqualTo(ThemeSnapshotService.Status.FRESH);

        // Second load: stale → fallback to cached
        Files.writeString(file, validSnapshotJson(OffsetDateTime.now(TPE).minusMinutes(90)));
        ThemeSnapshotService.LoadResult stale = service.getCurrentSnapshot();
        assertThat(stale.status()).isEqualTo(ThemeSnapshotService.Status.STALE_FALLBACK);
        assertThat(stale.snapshot()).isPresent();
        assertThat(stale.traceKey()).isEqualTo(ThemeSnapshotService.TRACE_KEY_STALE);
    }

    @Test
    void invalidSnapshot_withValidationOn_returnsValidationFailed() throws Exception {
        // theme_strength = 15 (out of range)
        String json = "{\"generated_at\":\"" + OffsetDateTime.now(TPE).minusMinutes(5) + "\","
                + "\"market_regime\":{\"regime_type\":\"BULL_TREND\",\"risk_multiplier\":1.0,\"trade_allowed\":true},"
                + "\"themes\":[{"
                + "\"theme_tag\":\"X\",\"theme_strength\":15,"
                + "\"trend_stage\":\"MID\",\"rotation_signal\":\"IN\","
                + "\"sustainability_score\":7,\"freshness_score\":7,"
                + "\"crowding_risk\":\"MID\",\"evidence_sources\":[\"NEWS\"],"
                + "\"confidence\":0.8,\"candidates\":[]"
                + "}]}";
        Path file = tempDir.resolve("theme-snapshot.json");
        Files.writeString(file, json);
        configOverrides.put("theme.snapshot.path", file.toString());
        configOverrides.put("theme.snapshot.fallback.enabled", false);

        ThemeSnapshotService.LoadResult r = service.getCurrentSnapshot();
        assertThat(r.status()).isEqualTo(ThemeSnapshotService.Status.VALIDATION_FAILED);
        assertThat(r.snapshot()).isEmpty();
        assertThat(r.traceKey()).isEqualTo(ThemeSnapshotService.TRACE_KEY_INVALID);
        assertThat(r.reason()).contains("theme_strength");
    }

    @Test
    void invalidSnapshot_withFallbackCached_returnsValidationFailedFallback() throws Exception {
        Path file = tempDir.resolve("theme-snapshot.json");
        configOverrides.put("theme.snapshot.path", file.toString());

        // seed cache with valid
        Files.writeString(file, validSnapshotJson(OffsetDateTime.now(TPE).minusMinutes(3)));
        assertThat(service.getCurrentSnapshot().status())
                .isEqualTo(ThemeSnapshotService.Status.FRESH);

        // overwrite with invalid
        String bad = "{\"generated_at\":\"" + OffsetDateTime.now(TPE) + "\","
                + "\"market_regime\":{\"regime_type\":null,\"risk_multiplier\":null,\"trade_allowed\":null},"
                + "\"themes\":[]}";
        Files.writeString(file, bad);

        ThemeSnapshotService.LoadResult r = service.getCurrentSnapshot();
        assertThat(r.status()).isEqualTo(ThemeSnapshotService.Status.VALIDATION_FAILED_FALLBACK);
        assertThat(r.snapshot()).isPresent();   // cached
        assertThat(r.traceKey()).isEqualTo(ThemeSnapshotService.TRACE_KEY_INVALID);
    }

    @Test
    void malformedJson_returnsParseError() throws Exception {
        Path file = tempDir.resolve("theme-snapshot.json");
        Files.writeString(file, "{ this is not json ]");
        configOverrides.put("theme.snapshot.path", file.toString());
        configOverrides.put("theme.snapshot.fallback.enabled", false);

        ThemeSnapshotService.LoadResult r = service.getCurrentSnapshot();
        assertThat(r.status()).isEqualTo(ThemeSnapshotService.Status.PARSE_ERROR);
        assertThat(r.snapshot()).isEmpty();
        assertThat(r.traceKey()).isEqualTo(ThemeSnapshotService.TRACE_KEY_PARSE_ERROR);
    }

    @Test
    void validationDisabled_stillChecksFreshness() throws Exception {
        // Even with validation off, stale should still trigger stale status (freshness gate always on)
        Path file = tempDir.resolve("theme-snapshot.json");
        Files.writeString(file, validSnapshotJson(OffsetDateTime.now(TPE).minusMinutes(60)));
        configOverrides.put("theme.snapshot.path", file.toString());
        configOverrides.put("theme.snapshot.validation.enabled", false);
        configOverrides.put("theme.snapshot.fallback.enabled", false);

        ThemeSnapshotService.LoadResult r = service.getCurrentSnapshot();
        assertThat(r.status()).isEqualTo(ThemeSnapshotService.Status.STALE_NO_FALLBACK);
    }

    // ══════════════════════════════════════════════════════════════════
    // helpers
    // ══════════════════════════════════════════════════════════════════

    private String validSnapshotJson(OffsetDateTime generatedAt) throws Exception {
        return "{\"generated_at\":\"" + generatedAt.toString() + "\","
                + "\"market_regime\":{\"regime_type\":\"BULL_TREND\",\"risk_multiplier\":1.0,\"trade_allowed\":true},"
                + "\"themes\":[{"
                + "\"theme_tag\":\"AI_SERVER\",\"theme_strength\":7.33,"
                + "\"trend_stage\":\"MID\",\"rotation_signal\":\"IN\","
                + "\"sustainability_score\":7.03,\"freshness_score\":7.05,"
                + "\"crowding_risk\":\"MID\",\"evidence_sources\":[\"NEWS\",\"VOLUME_BOARD\"],"
                + "\"confidence\":0.82,"
                + "\"candidates\":[{\"symbol\":\"2454\",\"role_hint\":\"LEADER\",\"confidence\":0.9}]"
                + "}]}";
    }
}
