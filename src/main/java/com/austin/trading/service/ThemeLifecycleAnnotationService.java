package com.austin.trading.service;

import com.austin.trading.dto.response.ThemeLifecycleAnnotationResponse;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class ThemeLifecycleAnnotationService {
    @PersistenceContext
    private EntityManager entityManager;

    public ThemeLifecycleAnnotationService() {
    }

    ThemeLifecycleAnnotationService(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Transactional(readOnly = true)
    public ThemeLifecycleAnnotationResponse candidates(LocalDate date) {
        LocalDate requestedDate = defaultDate(date);
        return annotateDatedSource(SourceSpec.candidates(), requestedDate);
    }

    @Transactional(readOnly = true)
    public ThemeLifecycleAnnotationResponse watchlist(LocalDate date) {
        LocalDate requestedDate = defaultDate(date);
        return annotateDatedSource(SourceSpec.watchlist(), requestedDate);
    }

    @Transactional(readOnly = true)
    public ThemeLifecycleAnnotationResponse ranking(LocalDate date) {
        LocalDate requestedDate = defaultDate(date);
        return annotateDatedSource(SourceSpec.ranking(), requestedDate);
    }

    @Transactional(readOnly = true)
    public ThemeLifecycleAnnotationResponse positions(LocalDate date) {
        LocalDate requestedDate = defaultDate(date);
        String targetType = "positions";
        List<String> gaps = new ArrayList<>();
        if (!tableExists("position")) {
            gaps.add("TABLE_MISSING:position");
            return ThemeLifecycleAnnotationResponse.of(requestedDate, targetType, List.of(), gaps);
        }
        if (!columnExists("position", "symbol")) {
            gaps.add("COLUMN_MISSING:position.symbol");
            return ThemeLifecycleAnnotationResponse.of(requestedDate, targetType, List.of(), gaps);
        }

        boolean lifecycleAvailable = lifecycleAvailable(gaps);
        boolean mappingAvailable = tableExists("stock_theme_mapping")
                && columnExists("stock_theme_mapping", "symbol")
                && columnExists("stock_theme_mapping", "theme_tag");
        if (!mappingAvailable && !columnExists("position", "theme_tag")) {
            gaps.add("THEME_MAPPING_UNAVAILABLE:position");
        }

        String stockName = columnExpr("p", "position", "stock_name");
        String qty = columnExpr("p", "position", "qty");
        String avgCost = columnExpr("p", "position", "avg_cost");
        String status = columnExpr("p", "position", "status");
        String openedAt = columnExpr("p", "position", "opened_at");
        String themeExpr = positionThemeExpr(mappingAvailable);
        String mappingJoin = mappingAvailable ? """
                LEFT JOIN (
                    SELECT symbol, MIN(theme_tag) AS theme_tag
                    FROM stock_theme_mapping
                    WHERE %s
                    GROUP BY symbol
                ) stm ON stm.symbol = p.symbol
                """.formatted(columnExists("stock_theme_mapping", "is_active")
                ? "(is_active = TRUE OR is_active = 1)" : "1 = 1") : "";
        String lifecycleJoin = lifecycleAvailable ? "LEFT JOIN theme_lifecycle_state tls ON tls.trading_date = :date AND tls.theme_tag = " + themeExpr : "";
        String whereStatus = columnExists("position", "status")
                ? "WHERE COALESCE(UPPER(p.status), 'OPEN') IN ('OPEN','CURRENT','ACTIVE','HOLDING')"
                : "";

        try {
            List<ThemeLifecycleAnnotationResponse.Item> items = list("""
                    SELECT p.symbol, %s, %s, %s, %s, %s, %s, NULL,
                           %s
                    FROM position p
                    %s
                    %s
                    %s
                    ORDER BY p.symbol
                    """.formatted(stockName, themeExpr, qty, avgCost, status, openedAt,
                    lifecycleSelect(lifecycleAvailable), mappingJoin, lifecycleJoin, whereStatus), Map.of("date", requestedDate)).stream()
                    .map(this::row)
                    .map(r -> item(r, requestedDate, "POSITION", lifecycleAvailable))
                    .toList();
            addItemGaps(items, gaps);
            return ThemeLifecycleAnnotationResponse.of(requestedDate, targetType, items, gaps);
        } catch (RuntimeException ex) {
            gaps.add("QUERY_FAILED:position:" + ex.getClass().getSimpleName());
            return ThemeLifecycleAnnotationResponse.of(requestedDate, targetType, List.of(), gaps);
        }
    }

    public static String advisoryAction(String stage) {
        if (stage == null || stage.isBlank()) {
            return "DATA_GAP_REVIEW";
        }
        return switch (stage.trim().toUpperCase(Locale.ROOT)) {
            case "DEAD" -> "AVOID_OR_EXIT_REVIEW";
            case "DISTRIBUTION" -> "TIGHTEN_REVIEW";
            case "OVERHEATED" -> "NO_CHASE_WAIT_PULLBACK";
            case "MAINSTREAM" -> "HOLD_THESIS_OR_PRIORITY_REVIEW";
            case "EMERGING" -> "WATCH_RESEARCH_ONLY";
            default -> "DATA_GAP_REVIEW";
        };
    }

    private ThemeLifecycleAnnotationResponse annotateDatedSource(SourceSpec spec, LocalDate date) {
        List<String> gaps = new ArrayList<>();
        if (!tableExists(spec.table())) {
            gaps.add("TABLE_MISSING:" + spec.table());
            return ThemeLifecycleAnnotationResponse.of(date, spec.targetType(), List.of(), gaps);
        }
        if (!columnExists(spec.table(), spec.dateColumn())) {
            gaps.add("COLUMN_MISSING:" + spec.table() + "." + spec.dateColumn());
            return ThemeLifecycleAnnotationResponse.of(date, spec.targetType(), List.of(), gaps);
        }
        if (!columnExists(spec.table(), "symbol")) {
            gaps.add("COLUMN_MISSING:" + spec.table() + ".symbol");
            return ThemeLifecycleAnnotationResponse.of(date, spec.targetType(), List.of(), gaps);
        }

        boolean lifecycleAvailable = lifecycleAvailable(gaps);
        String stockName = columnExpr("s", spec.table(), "stock_name");
        String themeExpr = columnExpr("s", spec.table(), "theme_tag");
        if (!columnExists(spec.table(), "theme_tag")) {
            gaps.add("COLUMN_MISSING:" + spec.table() + ".theme_tag");
        }
        String sourceStatus = firstColumnExpr("s", spec.table(), spec.statusColumns());
        String rankExpr = spec.rankingSource()
                ? "ROW_NUMBER() OVER (ORDER BY s.selection_score DESC, s.id ASC)"
                : "NULL";
        if (spec.rankingSource() && !columnExists(spec.table(), "selection_score")) {
            rankExpr = columnExists(spec.table(), "id") ? "ROW_NUMBER() OVER (ORDER BY s.id ASC)" : "NULL";
            gaps.add("COLUMN_MISSING:" + spec.table() + ".selection_score");
        }
        String lifecycleJoin = lifecycleAvailable
                ? "LEFT JOIN theme_lifecycle_state tls ON tls.trading_date = s." + spec.dateColumn() + " AND tls.theme_tag = " + themeExpr
                : "";

        try {
            List<ThemeLifecycleAnnotationResponse.Item> items = list("""
                    SELECT s.symbol, %s, %s, NULL, NULL, %s, s.%s, %s,
                           %s
                    FROM %s s
                    %s
                    WHERE s.%s = :date
                    ORDER BY %s
                    """.formatted(stockName, themeExpr, sourceStatus, spec.dateColumn(), rankExpr, lifecycleSelect(lifecycleAvailable),
                    spec.table(), lifecycleJoin, spec.dateColumn(), spec.orderBy()), Map.of("date", date)).stream()
                    .map(this::row)
                    .map(r -> item(r, date, spec.sourceType(), lifecycleAvailable))
                    .toList();
            addItemGaps(items, gaps);
            return ThemeLifecycleAnnotationResponse.of(date, spec.targetType(), items, gaps);
        } catch (RuntimeException ex) {
            gaps.add("QUERY_FAILED:" + spec.table() + ":" + ex.getClass().getSimpleName());
            return ThemeLifecycleAnnotationResponse.of(date, spec.targetType(), List.of(), gaps);
        }
    }

    private ThemeLifecycleAnnotationResponse.Item item(Object[] r,
                                                       LocalDate requestedDate,
                                                       String sourceType,
                                                       boolean lifecycleAvailable) {
        String themeTag = nullableStringAt(r, 2);
        String stage = nullableStringAt(r, 8);
        String gap = null;
        if (themeTag == null) {
            gap = "THEME_TAG_MISSING";
        } else if (!lifecycleAvailable) {
            gap = "LIFECYCLE_TABLE_OR_COLUMN_MISSING";
        } else if (stage == null) {
            gap = "LIFECYCLE_STATE_MISSING";
        }
        return new ThemeLifecycleAnnotationResponse.Item(
                nullableStringAt(r, 0),
                nullableStringAt(r, 1),
                themeTag,
                dateAt(r, 6),
                dateAt(r, 6) == null ? requestedDate : dateAt(r, 6),
                sourceType,
                nullableStringAt(r, 5),
                intAt(r, 7),
                decimalAt(r, 3),
                decimalAt(r, 4),
                nullableStringAt(r, 5),
                stage,
                nullableStringAt(r, 9),
                booleanAt(r, 10),
                decimalAt(r, 11),
                decimalAt(r, 15),
                intAt(r, 13),
                intAt(r, 12),
                intAt(r, 14),
                decimalAt(r, 16),
                decimalAt(r, 17),
                nullableStringAt(r, 18),
                nullableStringAt(r, 19),
                advisoryAction(stage),
                true,
                true,
                gap);
    }

    private void addItemGaps(List<ThemeLifecycleAnnotationResponse.Item> items, List<String> gaps) {
        long missingTheme = items.stream().filter(i -> "THEME_TAG_MISSING".equals(i.dataGapReason())).count();
        long missingLifecycle = items.stream().filter(i -> "LIFECYCLE_STATE_MISSING".equals(i.dataGapReason())).count();
        if (missingTheme > 0) {
            gaps.add("ITEM_THEME_TAG_MISSING:count=" + missingTheme);
        }
        if (missingLifecycle > 0) {
            gaps.add("ITEM_LIFECYCLE_STATE_MISSING:count=" + missingLifecycle);
        }
    }

    private String lifecycleSelect(boolean lifecycleAvailable) {
        if (!lifecycleAvailable) {
            return """
                    NULL, NULL, NULL, NULL,
                    NULL, NULL, NULL, NULL,
                    NULL, NULL, NULL, NULL
                    """;
        }
        return """
                tls.stage, tls.previous_stage, tls.stage_changed, tls.stage_confidence,
                tls.leader_count, tls.breadth, tls.continuation_days, tls.lifecycle_score,
                tls.rotation_score, tls.crowding_score, tls.recommended_playbook_json, tls.avoid_playbook_json
                """;
    }

    private boolean lifecycleAvailable(List<String> gaps) {
        if (!tableExists("theme_lifecycle_state")) {
            gaps.add("TABLE_MISSING:theme_lifecycle_state");
            return false;
        }
        List<String> required = List.of("trading_date", "theme_tag", "stage", "previous_stage", "stage_changed",
                "stage_confidence", "leader_count", "breadth", "continuation_days", "lifecycle_score",
                "rotation_score", "crowding_score", "recommended_playbook_json", "avoid_playbook_json");
        boolean ok = true;
        for (String column : required) {
            if (!columnExists("theme_lifecycle_state", column)) {
                gaps.add("COLUMN_MISSING:theme_lifecycle_state." + column);
                ok = false;
            }
        }
        return ok;
    }

    private String positionThemeExpr(boolean mappingAvailable) {
        if (columnExists("position", "theme_tag")) {
            return "p.theme_tag";
        }
        return mappingAvailable ? "stm.theme_tag" : "NULL";
    }

    private String columnExpr(String alias, String table, String column) {
        return columnExists(table, column) ? alias + "." + column : "NULL";
    }

    private String firstColumnExpr(String alias, String table, List<String> columns) {
        for (String column : columns) {
            if (columnExists(table, column)) {
                return alias + "." + column;
            }
        }
        return "NULL";
    }

    private boolean tableExists(String tableName) {
        try {
            Object count = entityManager.createNativeQuery("""
                    SELECT COUNT(*)
                    FROM information_schema.tables
                    WHERE table_schema = DATABASE() AND table_name = :tableName
                    """)
                    .setParameter("tableName", tableName)
                    .getSingleResult();
            return toLong(count) > 0;
        } catch (RuntimeException ex) {
            try {
                entityManager.createNativeQuery("SELECT 1 FROM " + tableName + " WHERE 1 = 0").getResultList();
                return true;
            } catch (RuntimeException ignored) {
                return false;
            }
        }
    }

    private boolean columnExists(String tableName, String columnName) {
        try {
            Object count = entityManager.createNativeQuery("""
                    SELECT COUNT(*)
                    FROM information_schema.columns
                    WHERE table_schema = DATABASE() AND table_name = :tableName AND column_name = :columnName
                    """)
                    .setParameter("tableName", tableName)
                    .setParameter("columnName", columnName)
                    .getSingleResult();
            return toLong(count) > 0;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private List<?> list(String sql, Map<String, ?> params) {
        Query q = entityManager.createNativeQuery(sql);
        params.forEach(q::setParameter);
        return q.getResultList();
    }

    private Object[] row(Object value) {
        return value instanceof Object[] arr ? arr : new Object[]{value};
    }

    private LocalDate defaultDate(LocalDate date) {
        return date == null ? LocalDate.now() : date;
    }

    private String nullableStringAt(Object[] row, int index) {
        if (row.length <= index || row[index] == null) {
            return null;
        }
        String value = row[index].toString();
        return value.isBlank() ? null : value;
    }

    private BigDecimal decimalAt(Object[] row, int index) {
        if (row.length <= index || row[index] == null) {
            return null;
        }
        if (row[index] instanceof BigDecimal bd) {
            return bd.setScale(4, RoundingMode.HALF_UP);
        }
        if (row[index] instanceof Number n) {
            return BigDecimal.valueOf(n.doubleValue()).setScale(4, RoundingMode.HALF_UP);
        }
        return new BigDecimal(row[index].toString()).setScale(4, RoundingMode.HALF_UP);
    }

    private Integer intAt(Object[] row, int index) {
        if (row.length <= index || row[index] == null) {
            return null;
        }
        return Math.toIntExact(toLong(row[index]));
    }

    private boolean booleanAt(Object[] row, int index) {
        if (row.length <= index || row[index] == null) {
            return false;
        }
        Object value = row[index];
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof Number n) {
            return n.intValue() != 0;
        }
        return Boolean.parseBoolean(value.toString());
    }

    private LocalDate dateAt(Object[] row, int index) {
        if (row.length <= index || row[index] == null) {
            return null;
        }
        Object value = row[index];
        if (value instanceof LocalDate d) {
            return d;
        }
        if (value instanceof Date d) {
            return d.toLocalDate();
        }
        if (value instanceof Timestamp ts) {
            return ts.toLocalDateTime().toLocalDate();
        }
        if (value instanceof LocalDateTime dt) {
            return dt.toLocalDate();
        }
        return LocalDate.parse(value.toString().substring(0, 10));
    }

    private long toLong(Object value) {
        if (value == null) {
            return 0L;
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        return Long.parseLong(value.toString());
    }

    private record SourceSpec(String targetType,
                              String sourceType,
                              String table,
                              String dateColumn,
                              List<String> statusColumns,
                              boolean rankingSource,
                              String orderBy) {
        static SourceSpec candidates() {
            return new SourceSpec("candidates", "CANDIDATE", "candidate_stock", "trading_date",
                    List.of("candidate_role", "reason"), false, "s.symbol");
        }

        static SourceSpec watchlist() {
            return new SourceSpec("watchlist", "WATCHLIST", "watchlist_stock", "last_seen_date",
                    List.of("watch_status", "source_type"), false, "s.symbol");
        }

        static SourceSpec ranking() {
            return new SourceSpec("ranking", "RANKING", "stock_ranking_snapshot", "trading_date",
                    List.of("rejection_reason"), true, "8");
        }
    }
}
