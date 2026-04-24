package com.austin.trading.service;

import com.austin.trading.config.ThemeSnapshotProperties;
import com.austin.trading.dto.internal.ThemeSnapshotV2Dto;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * v2 Theme Engine PR2：Theme Snapshot 讀取服務（讀 + 驗 + freshness + fallback）。
 *
 * <h3>行為契約</h3>
 * <ul>
 *   <li>主 flag 關閉 ({@code theme.engine.v2.enabled=false}) → {@link Status#DISABLED}；不讀檔</li>
 *   <li>檔案不存在 → {@link Status#FILE_MISSING}；trace key: {@code G2_THEME_SNAPSHOT_MISSING}</li>
 *   <li>解析失敗 → fallback 開啟時回 {@link Status#PARSE_ERROR_FALLBACK}，否則 {@link Status#PARSE_ERROR}</li>
 *   <li>驗證失敗 → fallback 開啟時回 {@link Status#VALIDATION_FAILED_FALLBACK}，否則 {@link Status#VALIDATION_FAILED}；
 *       trace key: {@code G2_THEME_SNAPSHOT_INVALID}</li>
 *   <li>age &gt; max_age_minutes（Asia/Taipei）→ fallback 開啟時回 {@link Status#STALE_FALLBACK}，
 *       否則 {@link Status#STALE_NO_FALLBACK}；trace key: {@code G2_THEME_SNAPSHOT_STALE}</li>
 *   <li>正常 → {@link Status#FRESH}</li>
 * </ul>
 *
 * <p>PR2 限制：此服務「只讀不寫」，不影響任何 live decision。PR3~PR5 才會將結果餵入 gate / shadow log。</p>
 */
@Service
public class ThemeSnapshotService {

    private static final Logger log = LoggerFactory.getLogger(ThemeSnapshotService.class);
    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Taipei");

    public static final String TRACE_KEY_DISABLED          = "THEME_ENGINE_DISABLED";
    public static final String TRACE_KEY_MISSING           = "G2_THEME_SNAPSHOT_MISSING";
    public static final String TRACE_KEY_PARSE_ERROR       = "G2_THEME_SNAPSHOT_PARSE_ERROR";
    public static final String TRACE_KEY_INVALID           = "G2_THEME_SNAPSHOT_INVALID";
    public static final String TRACE_KEY_STALE             = "G2_THEME_SNAPSHOT_STALE";
    public static final String TRACE_KEY_OK                = "G2_THEME_SNAPSHOT_OK";

    private final ThemeSnapshotProperties properties;
    private final ThemeSnapshotValidationService validator;
    private final ObjectMapper objectMapper;

    /** 最後一次成功（validated+fresh 或 validated）讀到的 snapshot，用於 fallback。volatile 避免 thread race。 */
    private final AtomicReference<ThemeSnapshotV2Dto> lastValidSnapshot = new AtomicReference<>();

    public ThemeSnapshotService(ThemeSnapshotProperties properties,
                                 ThemeSnapshotValidationService validator) {
        this.properties = properties;
        this.validator = validator;
        this.objectMapper = new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    /**
     * 主對外 API。PR3+ gate layer 會呼叫此 method 取得 snapshot；若 {@code result.snapshot().isEmpty()}，
     * gate layer 必須依 trace key 產出 WAIT 並落 trace。
     */
    public LoadResult getCurrentSnapshot() {
        if (!properties.engineEnabled()) {
            return new LoadResult(Optional.empty(), Status.DISABLED,
                    TRACE_KEY_DISABLED, "theme.engine.v2.enabled=false", null, null);
        }

        Path path = Path.of(properties.snapshotPath());
        if (!Files.exists(path)) {
            log.debug("[ThemeSnapshot] file missing path={}", path);
            return fallbackOrEmpty(Status.FILE_MISSING, TRACE_KEY_MISSING,
                    "snapshot file not found: " + path, null);
        }

        ThemeSnapshotV2Dto parsed;
        try {
            parsed = objectMapper.readValue(path.toFile(), ThemeSnapshotV2Dto.class);
        } catch (IOException e) {
            log.warn("[ThemeSnapshot] parse error path={} err={}", path, e.getMessage());
            return fallbackOrEmpty(Status.PARSE_ERROR, TRACE_KEY_PARSE_ERROR,
                    "parse failed: " + e.getMessage(), null);
        }

        if (properties.validationEnabled()) {
            ThemeSnapshotValidationService.Result vr = validator.validate(parsed);
            if (!vr.valid()) {
                log.warn("[ThemeSnapshot] validation failed: {}", vr.summary());
                return fallbackOrEmpty(Status.VALIDATION_FAILED, TRACE_KEY_INVALID,
                        vr.summary(), parsed.generatedAt());
            }
        }

        OffsetDateTime generatedAt = parsed.generatedAt();
        if (generatedAt == null) {
            return fallbackOrEmpty(Status.VALIDATION_FAILED, TRACE_KEY_INVALID,
                    "generated_at missing after validation", null);
        }

        Duration age = Duration.between(generatedAt, OffsetDateTime.now(MARKET_ZONE));
        long maxMin = properties.maxAgeMinutes();
        if (age.toMinutes() > maxMin) {
            log.info("[ThemeSnapshot] stale age={}m threshold={}m", age.toMinutes(), maxMin);
            return fallbackOrEmpty(Status.STALE_NO_FALLBACK, TRACE_KEY_STALE,
                    "stale: age=" + age.toMinutes() + "m > " + maxMin + "m",
                    generatedAt, age);
        }

        // 正常 → 更新 cache，回 FRESH
        lastValidSnapshot.set(parsed);
        return new LoadResult(Optional.of(parsed), Status.FRESH, TRACE_KEY_OK,
                "fresh, age=" + age.toMinutes() + "m", generatedAt, age);
    }

    // ══════════════════════════════════════════════════════════════════════
    // internal
    // ══════════════════════════════════════════════════════════════════════

    private LoadResult fallbackOrEmpty(Status failStatus, String traceKey, String reason,
                                        OffsetDateTime generatedAt) {
        return fallbackOrEmpty(failStatus, traceKey, reason, generatedAt, null);
    }

    private LoadResult fallbackOrEmpty(Status failStatus, String traceKey, String reason,
                                        OffsetDateTime generatedAt, Duration age) {
        if (properties.fallbackEnabled()) {
            ThemeSnapshotV2Dto cached = lastValidSnapshot.get();
            if (cached != null) {
                Status fbStatus = switch (failStatus) {
                    case FILE_MISSING       -> Status.FILE_MISSING_FALLBACK;
                    case PARSE_ERROR        -> Status.PARSE_ERROR_FALLBACK;
                    case VALIDATION_FAILED  -> Status.VALIDATION_FAILED_FALLBACK;
                    case STALE_NO_FALLBACK  -> Status.STALE_FALLBACK;
                    default                 -> failStatus;
                };
                return new LoadResult(Optional.of(cached), fbStatus, traceKey,
                        reason + " (using cached fallback)", generatedAt, age);
            }
        }
        return new LoadResult(Optional.empty(), failStatus, traceKey, reason, generatedAt, age);
    }

    // ══════════════════════════════════════════════════════════════════════
    // Public types
    // ══════════════════════════════════════════════════════════════════════

    public enum Status {
        DISABLED,
        FRESH,
        FILE_MISSING,
        FILE_MISSING_FALLBACK,
        PARSE_ERROR,
        PARSE_ERROR_FALLBACK,
        VALIDATION_FAILED,
        VALIDATION_FAILED_FALLBACK,
        STALE_NO_FALLBACK,
        STALE_FALLBACK
    }

    /**
     * 載入結果。PR3+ gate layer 應依 {@link #status()} + {@link #traceKey()} 決定 WAIT/PASS；
     * {@link #snapshot()} 為空時一律不可 PASS。
     */
    public record LoadResult(
            Optional<ThemeSnapshotV2Dto> snapshot,
            Status status,
            String traceKey,
            String reason,
            OffsetDateTime generatedAt,
            Duration age
    ) {
        public boolean isUsable() {
            return snapshot.isPresent();
        }
    }
}
