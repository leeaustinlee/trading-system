package com.austin.trading.service;

import com.austin.trading.config.ThemeSnapshotProperties;
import com.austin.trading.domain.enums.DecisionDiffType;
import com.austin.trading.entity.ThemeShadowDailyReportEntity;
import com.austin.trading.entity.ThemeShadowDecisionLogEntity;
import com.austin.trading.repository.ThemeShadowDailyReportRepository;
import com.austin.trading.repository.ThemeShadowDecisionLogRepository;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * v2 Theme Engine PR5：Shadow Mode 每日彙總報表。
 *
 * <p>從 {@code theme_shadow_decision_log} 讀取某日所有紀錄，產出：</p>
 * <ol>
 *   <li>6 種 {@link DecisionDiffType} 的 count + avgScoreDiff + p90(|scoreDiff|)</li>
 *   <li>Top conflicts JSON（衝突最大 10 筆，優先看 LEGACY_BUY_THEME_BLOCK / CONFLICT_REVIEW_REQUIRED）</li>
 *   <li>Markdown 可讀摘要</li>
 *   <li>寫入 {@code {shadowReportPath}/theme-shadow-{YYYY-MM-DD}.{json,md}}</li>
 *   <li>upsert 到 {@code theme_shadow_daily_report} 表</li>
 * </ol>
 *
 * <p>預設關閉（{@code theme.shadow_mode.enabled=false}）。Trace-only，不影響任何 live decision。</p>
 *
 * <h3>前置條件</h3>
 * <p>日報資料完全來自 {@code theme_shadow_decision_log}，而該表的寫入只會在
 * {@link ThemeGateOrchestrator} 的 Outcome 為 active 時被 {@link ThemeShadowModeService} 觸發。
 * 因此 {@code theme.shadow_mode.enabled=true} 時<strong>必須同時</strong>開啟
 * {@code theme.gate.trace.enabled=true}，否則日報永遠是 empty。</p>
 */
@Service
public class ThemeShadowReportService {

    private static final Logger log = LoggerFactory.getLogger(ThemeShadowReportService.class);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE;

    /** Top conflicts JSON 最多保留幾筆。 */
    private static final int TOP_CONFLICTS_LIMIT = 10;

    private final ThemeSnapshotProperties properties;
    private final ThemeShadowDecisionLogRepository logRepo;
    private final ThemeShadowDailyReportRepository reportRepo;
    private final ObjectMapper objectMapper;

    public ThemeShadowReportService(ThemeSnapshotProperties properties,
                                     ThemeShadowDecisionLogRepository logRepo,
                                     ThemeShadowDailyReportRepository reportRepo) {
        this.properties = properties;
        this.logRepo = logRepo;
        this.reportRepo = reportRepo;
        this.objectMapper = new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .registerModule(new JavaTimeModule())
                .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }

    /**
     * 為指定日期產生 shadow 日報。
     * flag 關閉 → 回 {@link ReportResult#disabled()}；當日無紀錄 → {@link ReportResult#empty(LocalDate)}。
     */
    public ReportResult generateDaily(LocalDate tradingDate) {
        if (!properties.shadowModeEnabled()) return ReportResult.disabled();
        if (tradingDate == null) return ReportResult.empty(null);

        List<ThemeShadowDecisionLogEntity> entries = logRepo.findByTradingDate(tradingDate);
        if (entries == null || entries.isEmpty()) return ReportResult.empty(tradingDate);

        Map<DecisionDiffType, Integer> counts = countByType(entries);
        BigDecimal avgScoreDiff = avgScoreDiff(entries);
        BigDecimal p90Abs = p90AbsScoreDiff(entries);
        List<Map<String, Object>> topConflicts = topConflicts(entries, TOP_CONFLICTS_LIMIT);

        String json = buildJson(tradingDate, entries.size(), counts, avgScoreDiff, p90Abs, topConflicts);
        String md = buildMarkdown(tradingDate, entries.size(), counts, avgScoreDiff, p90Abs, topConflicts);

        Path jsonPath = null;
        Path mdPath = null;
        String writeError = null;
        try {
            Path dir = Paths.get(properties.shadowReportPath());
            Files.createDirectories(dir);
            jsonPath = dir.resolve("theme-shadow-" + DATE_FMT.format(tradingDate) + ".json");
            mdPath = dir.resolve("theme-shadow-" + DATE_FMT.format(tradingDate) + ".md");
            Files.writeString(jsonPath, json, StandardCharsets.UTF_8);
            Files.writeString(mdPath, md, StandardCharsets.UTF_8);
        } catch (IOException e) {
            writeError = e.getMessage();
            log.warn("[ThemeShadowReport] write file failed date={} path={} err={}",
                    tradingDate, properties.shadowReportPath(), e.getMessage());
        }

        ThemeShadowDailyReportEntity saved = upsertDailyReport(
                tradingDate, entries.size(), counts, avgScoreDiff, p90Abs, topConflicts, md);

        log.info("[ThemeShadowReport] date={} total={} counts={} avgDiff={} p90={} file={}",
                tradingDate, entries.size(), counts, avgScoreDiff, p90Abs,
                jsonPath == null ? "<not-written>" : jsonPath);

        return new ReportResult(true, tradingDate, entries.size(), counts,
                avgScoreDiff, p90Abs,
                jsonPath == null ? null : jsonPath.toString(),
                mdPath == null ? null : mdPath.toString(),
                writeError, saved);
    }

    // ══════════════════════════════════════════════════════════════════════
    // Aggregation helpers
    // ══════════════════════════════════════════════════════════════════════

    static Map<DecisionDiffType, Integer> countByType(List<ThemeShadowDecisionLogEntity> entries) {
        Map<DecisionDiffType, Integer> m = new EnumMap<>(DecisionDiffType.class);
        for (DecisionDiffType t : DecisionDiffType.values()) m.put(t, 0);
        for (ThemeShadowDecisionLogEntity e : entries) {
            DecisionDiffType raw = DecisionDiffType.parseOrConflict(e.getDecisionDiffType());
            DecisionDiffType slot = mapToLegacySlot(raw, e.getLegacyDecision());
            // 雙計：raw 也保留（new enum 觀察用），mapped 補進舊 enum slot 給 daily report 使用
            m.merge(raw, 1, Integer::sum);
            if (slot != raw) m.merge(slot, 1, Integer::sum);
        }
        return m;
    }

    /**
     * Reviewer C.2 BLOCKER 修正：把 P0.2 新 5 類 enum 對應回舊 6 類 slot，
     * 讓 ThemeShadowDailyReportEntity / LineSummary 等只讀舊 slot 的消費者能看到計數。
     *
     * <p>對應規則：
     * <ul>
     *   <li>SAME_DECISION_SAME_SCORE / SAME_DECISION_SCORE_DIFF
     *       → legacy=ENTER → {@link DecisionDiffType#SAME_BUY}; 否則 SAME_WAIT</li>
     *   <li>V2_VETO_LEGACY_PASS  → LEGACY_BUY_THEME_BLOCK（legacy 想買、theme v2 擋下）</li>
     *   <li>LEGACY_VETO_V2_PASS  → LEGACY_WAIT_THEME_BUY（legacy 不買、theme v2 看好）</li>
     *   <li>DIFF_DECISION        → CONFLICT_REVIEW_REQUIRED</li>
     *   <li>舊 6 類 → 不變</li>
     * </ul>
     */
    static DecisionDiffType mapToLegacySlot(DecisionDiffType raw, String legacyDecision) {
        if (raw == null) return DecisionDiffType.CONFLICT_REVIEW_REQUIRED;
        return switch (raw) {
            case SAME_DECISION_SAME_SCORE, SAME_DECISION_SCORE_DIFF ->
                    "ENTER".equalsIgnoreCase(legacyDecision)
                            ? DecisionDiffType.SAME_BUY
                            : DecisionDiffType.SAME_WAIT;
            case V2_VETO_LEGACY_PASS  -> DecisionDiffType.LEGACY_BUY_THEME_BLOCK;
            case LEGACY_VETO_V2_PASS  -> DecisionDiffType.LEGACY_WAIT_THEME_BUY;
            case DIFF_DECISION        -> DecisionDiffType.CONFLICT_REVIEW_REQUIRED;
            default                   -> raw;
        };
    }

    static BigDecimal avgScoreDiff(List<ThemeShadowDecisionLogEntity> entries) {
        List<BigDecimal> vals = entries.stream()
                .map(ThemeShadowDecisionLogEntity::getScoreDiff)
                .filter(Objects::nonNull)
                .toList();
        if (vals.isEmpty()) return null;
        BigDecimal sum = vals.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(vals.size()), 3, RoundingMode.HALF_UP);
    }

    /** P90(|scoreDiff|)：取絕對值後排序，index = ceil(0.9 × n) - 1。 */
    static BigDecimal p90AbsScoreDiff(List<ThemeShadowDecisionLogEntity> entries) {
        List<BigDecimal> abs = entries.stream()
                .map(ThemeShadowDecisionLogEntity::getScoreDiff)
                .filter(Objects::nonNull)
                .map(BigDecimal::abs)
                .sorted()
                .toList();
        if (abs.isEmpty()) return null;
        int n = abs.size();
        int idx = (int) Math.ceil(0.9 * n) - 1;
        if (idx < 0) idx = 0;
        if (idx >= n) idx = n - 1;
        return abs.get(idx).setScale(3, RoundingMode.HALF_UP);
    }

    /** 衝突重點優先排：LEGACY_BUY_THEME_BLOCK / CONFLICT_REVIEW_REQUIRED / LEGACY_WAIT_THEME_BUY；次之 |scoreDiff| 大。
     *  P0.2 後使用 {@link #mapToLegacySlot} 把新 enum 對應回舊 enum，避免「同決策」誤入衝突列表。 */
    static List<Map<String, Object>> topConflicts(List<ThemeShadowDecisionLogEntity> entries, int limit) {
        return entries.stream()
                .filter(e -> {
                    DecisionDiffType raw = DecisionDiffType.parseOrConflict(e.getDecisionDiffType());
                    DecisionDiffType mapped = mapToLegacySlot(raw, e.getLegacyDecision());
                    return mapped != DecisionDiffType.SAME_BUY && mapped != DecisionDiffType.SAME_WAIT;
                })
                .sorted(Comparator
                        .comparingInt((ThemeShadowDecisionLogEntity e) -> priority(
                                mapToLegacySlot(
                                        DecisionDiffType.parseOrConflict(e.getDecisionDiffType()),
                                        e.getLegacyDecision())))
                        .thenComparing((ThemeShadowDecisionLogEntity e) -> {
                            BigDecimal s = e.getScoreDiff();
                            return s == null ? BigDecimal.ZERO : s.abs();
                        }, Comparator.reverseOrder()))
                .limit(limit)
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("symbol", e.getSymbol());
                    m.put("diffType", e.getDecisionDiffType());
                    m.put("legacyDecision", e.getLegacyDecision());
                    m.put("themeDecision", e.getThemeDecision());
                    m.put("legacyFinalScore", e.getLegacyFinalScore());
                    m.put("themeFinalScore", e.getThemeFinalScore());
                    m.put("scoreDiff", e.getScoreDiff());
                    m.put("themeVetoReason", e.getThemeVetoReason());
                    return m;
                })
                .toList();
    }

    private static int priority(DecisionDiffType t) {
        return switch (t) {
            case LEGACY_BUY_THEME_BLOCK     -> 0;
            case CONFLICT_REVIEW_REQUIRED   -> 1;
            case LEGACY_WAIT_THEME_BUY      -> 2;
            case BOTH_BLOCK                 -> 3;
            default                         -> 4;
        };
    }

    // ══════════════════════════════════════════════════════════════════════
    // Serialization
    // ══════════════════════════════════════════════════════════════════════

    private String buildJson(LocalDate date, int total, Map<DecisionDiffType, Integer> counts,
                              BigDecimal avg, BigDecimal p90, List<Map<String, Object>> topConflicts) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("tradingDate", date.toString());
        root.put("generatedAt", LocalDateTime.now().toString());
        root.put("totalCandidates", total);
        Map<String, Integer> countMap = new LinkedHashMap<>();
        for (DecisionDiffType t : DecisionDiffType.values()) {
            countMap.put(t.name().toLowerCase() + "_count", counts.getOrDefault(t, 0));
        }
        root.put("counts", countMap);
        root.put("avgScoreDiff", avg);
        root.put("p90AbsScoreDiff", p90);
        root.put("topConflicts", topConflicts);
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (JsonProcessingException e) {
            log.warn("[ThemeShadowReport] build json failed: {}", e.getMessage());
            return "{}";
        }
    }

    private String buildMarkdown(LocalDate date, int total, Map<DecisionDiffType, Integer> counts,
                                  BigDecimal avg, BigDecimal p90, List<Map<String, Object>> topConflicts) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Theme Engine Shadow Report — ").append(date).append("\n\n");
        sb.append("Generated at: ").append(LocalDateTime.now()).append("  \n");
        sb.append("Total candidates: **").append(total).append("**\n\n");

        sb.append("## Decision Diff Counts\n\n");
        sb.append("| Type | Count |\n|---|---:|\n");
        for (DecisionDiffType t : DecisionDiffType.values()) {
            sb.append("| ").append(t.name()).append(" | ").append(counts.getOrDefault(t, 0)).append(" |\n");
        }
        sb.append("\n");

        sb.append("## Score Diff Stats\n\n");
        sb.append("- avgScoreDiff (theme − legacy): ").append(avg == null ? "N/A" : avg).append("\n");
        sb.append("- p90(|scoreDiff|): ").append(p90 == null ? "N/A" : p90).append("\n\n");

        sb.append("## Top Conflicts (最多 ").append(TOP_CONFLICTS_LIMIT).append(" 筆)\n\n");
        if (topConflicts.isEmpty()) {
            sb.append("_No conflicts._\n");
        } else {
            sb.append("| Symbol | DiffType | Legacy | Theme | LegacyScore | ThemeScore | Diff | VetoReason |\n");
            sb.append("|---|---|---|---|---:|---:|---:|---|\n");
            for (Map<String, Object> c : topConflicts) {
                sb.append("| ").append(c.get("symbol"))
                        .append(" | ").append(c.get("diffType"))
                        .append(" | ").append(c.get("legacyDecision"))
                        .append(" | ").append(c.get("themeDecision"))
                        .append(" | ").append(fmt(c.get("legacyFinalScore")))
                        .append(" | ").append(fmt(c.get("themeFinalScore")))
                        .append(" | ").append(fmt(c.get("scoreDiff")))
                        .append(" | ").append(nvl(c.get("themeVetoReason")))
                        .append(" |\n");
            }
        }
        return sb.toString();
    }

    private static String fmt(Object v) { return v == null ? "—" : v.toString(); }
    private static String nvl(Object v) { return v == null ? "—" : v.toString(); }

    // ══════════════════════════════════════════════════════════════════════
    // Persistence
    // ══════════════════════════════════════════════════════════════════════

    private ThemeShadowDailyReportEntity upsertDailyReport(
            LocalDate date, int total, Map<DecisionDiffType, Integer> counts,
            BigDecimal avg, BigDecimal p90, List<Map<String, Object>> topConflicts, String markdown) {
        ThemeShadowDailyReportEntity e = reportRepo.findByTradingDate(date)
                .orElseGet(ThemeShadowDailyReportEntity::new);
        e.setTradingDate(date);
        e.setTotalCandidates(total);
        e.setSameBuyCount(counts.getOrDefault(DecisionDiffType.SAME_BUY, 0));
        e.setSameWaitCount(counts.getOrDefault(DecisionDiffType.SAME_WAIT, 0));
        e.setLegacyBuyThemeBlockCount(counts.getOrDefault(DecisionDiffType.LEGACY_BUY_THEME_BLOCK, 0));
        e.setLegacyWaitThemeBuyCount(counts.getOrDefault(DecisionDiffType.LEGACY_WAIT_THEME_BUY, 0));
        e.setBothBlockCount(counts.getOrDefault(DecisionDiffType.BOTH_BLOCK, 0));
        e.setConflictReviewRequiredCount(counts.getOrDefault(DecisionDiffType.CONFLICT_REVIEW_REQUIRED, 0));
        e.setAvgScoreDiff(avg);
        e.setP90AbsScoreDiff(p90);
        try {
            e.setTopConflictsJson(objectMapper.writeValueAsString(topConflicts));
        } catch (JsonProcessingException ex) {
            log.warn("[ThemeShadowReport] serialize top conflicts failed: {}", ex.getMessage());
        }
        e.setReportMarkdown(markdown);
        e.setCreatedAt(LocalDateTime.now());
        return reportRepo.save(e);
    }

    // ══════════════════════════════════════════════════════════════════════
    // Result type
    // ══════════════════════════════════════════════════════════════════════

    public record ReportResult(
            boolean active,
            LocalDate tradingDate,
            int totalCandidates,
            Map<DecisionDiffType, Integer> counts,
            BigDecimal avgScoreDiff,
            BigDecimal p90AbsScoreDiff,
            String jsonPath,
            String markdownPath,
            String writeError,
            ThemeShadowDailyReportEntity persisted
    ) {
        public static ReportResult disabled() {
            Map<DecisionDiffType, Integer> empty = new LinkedHashMap<>();
            for (DecisionDiffType t : DecisionDiffType.values()) empty.put(t, 0);
            return new ReportResult(false, null, 0, empty, null, null, null, null, null, null);
        }

        public static ReportResult empty(LocalDate date) {
            Map<DecisionDiffType, Integer> empty = new LinkedHashMap<>();
            for (DecisionDiffType t : DecisionDiffType.values()) empty.put(t, 0);
            return new ReportResult(true, date, 0, empty, null, null, null, null, null, null);
        }
    }

}
