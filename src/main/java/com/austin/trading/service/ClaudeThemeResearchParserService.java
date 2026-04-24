package com.austin.trading.service;

import com.austin.trading.config.ThemeSnapshotProperties;
import com.austin.trading.dto.internal.ClaudeThemeResearchOutputDto;
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
 * v2 Theme Engine PR3：Claude theme research JSON 讀取服務。
 *
 * <p>結構與 {@link ThemeSnapshotService} 平行對稱（LoadResult / Status / trace key 分流）。
 * 整包級別的 validation 僅驗：{@code generated_at} 存在、{@code symbols} 為 array。
 * per-entry 的 alignment（symbol+theme_tag）於 {@link ThemeContextMergeService} merge 階段處理，
 * 因為單筆錯誤只拒收該筆，不拒收整包（user 明確要求）。</p>
 *
 * <h3>Status 分流（下游 PR4 gate 必須照此處理）</h3>
 * <ul>
 *   <li>{@link Status#FRESH}                         → usable normal</li>
 *   <li>{@link Status#STALE_FALLBACK} /
 *       {@link Status#PARSE_ERROR_FALLBACK} /
 *       {@link Status#FILE_MISSING_FALLBACK}         → usable with warning trace</li>
 *   <li>{@link Status#DISABLED} /
 *       {@link Status#FILE_MISSING} /
 *       {@link Status#PARSE_ERROR} /
 *       {@link Status#STALE_NO_FALLBACK}             → WAIT，merge 不執行</li>
 * </ul>
 */
@Service
public class ClaudeThemeResearchParserService {

    private static final Logger log = LoggerFactory.getLogger(ClaudeThemeResearchParserService.class);
    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Taipei");

    public static final String TRACE_KEY_DISABLED      = "THEME_CLAUDE_MERGE_DISABLED";
    public static final String TRACE_KEY_MISSING       = "G2B_CLAUDE_RESEARCH_MISSING";
    public static final String TRACE_KEY_PARSE_ERROR   = "G2B_CLAUDE_RESEARCH_PARSE_ERROR";
    public static final String TRACE_KEY_INVALID       = "G2B_CLAUDE_RESEARCH_INVALID";
    public static final String TRACE_KEY_STALE         = "G2B_CLAUDE_RESEARCH_STALE";
    public static final String TRACE_KEY_OK            = "G2B_CLAUDE_RESEARCH_OK";

    private final ThemeSnapshotProperties properties;
    private final ObjectMapper objectMapper;

    private final AtomicReference<ClaudeThemeResearchOutputDto> lastValid = new AtomicReference<>();

    public ClaudeThemeResearchParserService(ThemeSnapshotProperties properties) {
        this.properties = properties;
        this.objectMapper = new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    /**
     * 主入口。flag 關閉、檔缺、解析壞、stale 皆有明確 status；fallback 由 properties 控制。
     */
    public LoadResult loadCurrent() {
        if (!properties.claudeContextMergeEnabled()) {
            return new LoadResult(Optional.empty(), Status.DISABLED, TRACE_KEY_DISABLED,
                    "theme.claude.context.merge.enabled=false", null, null);
        }

        Path path = Path.of(properties.claudeResearchPath());
        if (!Files.exists(path)) {
            log.debug("[ClaudeResearch] file missing path={}", path);
            return fallbackOrEmpty(Status.FILE_MISSING, TRACE_KEY_MISSING,
                    "claude research file not found: " + path, null, null);
        }

        ClaudeThemeResearchOutputDto parsed;
        try {
            parsed = objectMapper.readValue(path.toFile(), ClaudeThemeResearchOutputDto.class);
        } catch (IOException e) {
            log.warn("[ClaudeResearch] parse error path={} err={}", path, e.getMessage());
            return fallbackOrEmpty(Status.PARSE_ERROR, TRACE_KEY_PARSE_ERROR,
                    "parse failed: " + e.getMessage(), null, null);
        }

        // 整包級 validation：generated_at 必須存在；symbols 為 null 視為空陣列（不 reject 整包）
        if (parsed.generatedAt() == null) {
            log.warn("[ClaudeResearch] missing generated_at → treat as invalid");
            return fallbackOrEmpty(Status.VALIDATION_FAILED, TRACE_KEY_INVALID,
                    "generated_at missing", null, null);
        }

        Duration age = Duration.between(parsed.generatedAt(), OffsetDateTime.now(MARKET_ZONE));
        long maxMin = properties.claudeResearchMaxAgeMinutes();
        if (age.toMinutes() > maxMin) {
            log.info("[ClaudeResearch] stale age={}m threshold={}m", age.toMinutes(), maxMin);
            return fallbackOrEmpty(Status.STALE_NO_FALLBACK, TRACE_KEY_STALE,
                    "stale: age=" + age.toMinutes() + "m > " + maxMin + "m",
                    parsed.generatedAt(), age);
        }

        lastValid.set(parsed);
        return new LoadResult(Optional.of(parsed), Status.FRESH, TRACE_KEY_OK,
                "fresh, age=" + age.toMinutes() + "m", parsed.generatedAt(), age);
    }

    private LoadResult fallbackOrEmpty(Status failStatus, String traceKey, String reason,
                                        OffsetDateTime generatedAt, Duration age) {
        if (properties.fallbackEnabled()) {
            ClaudeThemeResearchOutputDto cached = lastValid.get();
            if (cached != null) {
                Status fb = switch (failStatus) {
                    case FILE_MISSING       -> Status.FILE_MISSING_FALLBACK;
                    case PARSE_ERROR        -> Status.PARSE_ERROR_FALLBACK;
                    case VALIDATION_FAILED  -> Status.VALIDATION_FAILED_FALLBACK;
                    case STALE_NO_FALLBACK  -> Status.STALE_FALLBACK;
                    default                 -> failStatus;
                };
                return new LoadResult(Optional.of(cached), fb, traceKey,
                        reason + " (using cached fallback)", generatedAt, age);
            }
        }
        return new LoadResult(Optional.empty(), failStatus, traceKey, reason, generatedAt, age);
    }

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

    public record LoadResult(
            Optional<ClaudeThemeResearchOutputDto> data,
            Status status,
            String traceKey,
            String reason,
            OffsetDateTime generatedAt,
            Duration age
    ) {
        public boolean isUsable() { return data.isPresent(); }

        /** FRESH 以外（包含 *_FALLBACK）請在 trace 附 warning；下游可分流決策。 */
        public boolean hasWarning() {
            return status != Status.FRESH && data.isPresent();
        }
    }
}
