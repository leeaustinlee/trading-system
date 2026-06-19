package com.austin.trading.service;

import com.austin.trading.dto.response.LifecycleExitReviewResponse;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class LifecycleExitReviewService {
    @PersistenceContext
    private EntityManager entityManager;

    public LifecycleExitReviewService() {
    }

    LifecycleExitReviewService(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Transactional(readOnly = true)
    public LifecycleExitReviewResponse report(LocalDate date) {
        LocalDate requestedDate = defaultDate(date);
        List<String> gaps = new ArrayList<>();
        if (!tableExists("lifecycle_exit_review_shadow")) {
            gaps.add("TABLE_MISSING:lifecycle_exit_review_shadow");
            return LifecycleExitReviewResponse.of(requestedDate, 0, List.of(), gaps);
        }
        List<LifecycleExitReviewResponse.Item> rows = readRows(requestedDate, gaps);
        addRowGaps(rows, gaps);
        return LifecycleExitReviewResponse.of(requestedDate, 0, rows, gaps);
    }

    @Transactional
    public LifecycleExitReviewResponse rebuild(LocalDate date) {
        LocalDate requestedDate = defaultDate(date);
        List<String> gaps = new ArrayList<>();
        if (!tableExists("lifecycle_exit_review_shadow")) {
            gaps.add("TABLE_MISSING:lifecycle_exit_review_shadow");
            return LifecycleExitReviewResponse.of(requestedDate, 0, List.of(), gaps);
        }
        if (!tableExists("position")) {
            gaps.add("TABLE_MISSING:position");
            return LifecycleExitReviewResponse.of(requestedDate, 0, readRows(requestedDate, gaps), gaps);
        }
        if (!columnExists("position", "symbol")) {
            gaps.add("COLUMN_MISSING:position.symbol");
            return LifecycleExitReviewResponse.of(requestedDate, 0, readRows(requestedDate, gaps), gaps);
        }

        List<Object[]> sourceRows = sourceRows(requestedDate, gaps);
        int rebuilt = 0;
        for (Object[] row : sourceRows) {
            RowDraft draft = draft(row);
            ReviewMapping mapping = reviewMapping(draft.lifecycleStage(), draft.dataGapReason());
            upsert(requestedDate, draft, mapping);
            rebuilt++;
        }

        List<LifecycleExitReviewResponse.Item> rows = readRows(requestedDate, gaps);
        addRowGaps(rows, gaps);
        return LifecycleExitReviewResponse.of(requestedDate, rebuilt, rows, gaps);
    }

    public static ReviewMapping reviewMapping(String lifecycleStage, String dataGapReason) {
        if (dataGapReason != null || lifecycleStage == null || lifecycleStage.isBlank()) {
            return new ReviewMapping("DATA_GAP_REVIEW", "REVIEW");
        }
        return switch (lifecycleStage.trim().toUpperCase(Locale.ROOT)) {
            case "DISTRIBUTION" -> new ReviewMapping("TIGHTEN_STOP_REVIEW", "HIGH");
            case "DEAD" -> new ReviewMapping("HIGH_PRIORITY_EXIT_REVIEW", "CRITICAL");
            case "OVERHEATED" -> new ReviewMapping("NO_ADD_NO_CHASE", "MEDIUM");
            case "MAINSTREAM" -> new ReviewMapping("HOLD_THESIS", "LOW");
            case "EMERGING" -> new ReviewMapping("WATCH_RESEARCH_ONLY", "LOW");
            default -> new ReviewMapping("DATA_GAP_REVIEW", "REVIEW");
        };
    }

    private List<Object[]> sourceRows(LocalDate date, List<String> gaps) {
        boolean positionStockName = columnExists("position", "stock_name");
        boolean positionStatus = columnExists("position", "status");
        boolean positionTheme = columnExists("position", "theme_tag");
        boolean mappingAvailable = mappingAvailable();
        boolean lifecycleAvailable = lifecycleAvailable(gaps);
        boolean structuralAvailable = structuralAvailable(gaps);

        if (!positionTheme && !mappingAvailable) {
            gaps.add("THEME_SOURCE_UNAVAILABLE:position.theme_tag_or_stock_theme_mapping");
        }

        String stockName = positionStockName ? "p.stock_name" : "NULL";
        String status = positionStatus ? "p.status" : "NULL";
        String themeExpr = positionTheme
                ? "p.theme_tag"
                : (mappingAvailable ? "stm.theme_tag" : "NULL");
        String mappingJoin = mappingAvailable ? """
                LEFT JOIN (
                    SELECT symbol, MIN(theme_tag) AS theme_tag
                    FROM stock_theme_mapping
                    WHERE %s
                    GROUP BY symbol
                ) stm ON stm.symbol = p.symbol
                """.formatted(columnExists("stock_theme_mapping", "is_active")
                ? "(is_active = TRUE OR is_active = 1)" : "1 = 1") : "";
        String lifecycleJoin = lifecycleAvailable
                ? "LEFT JOIN theme_lifecycle_state tls ON tls.trading_date = :date AND tls.theme_tag = " + themeExpr
                : "";
        String structuralJoin = structuralAvailable ? """
                LEFT JOIN (
                    SELECT s1.symbol, s1.evaluation_date, s1.arbiter_tier, s1.price_state, s1.structure_state
                    FROM structural_exit_decision_log s1
                    INNER JOIN (
                        SELECT symbol, MAX(id) AS max_id
                        FROM structural_exit_decision_log
                        WHERE evaluation_date = :date
                        GROUP BY symbol
                    ) latest ON latest.max_id = s1.id
                ) sedl ON sedl.symbol = p.symbol
                """ : "";
        String whereStatus = positionStatus
                ? "WHERE COALESCE(UPPER(p.status), 'OPEN') IN ('OPEN','CURRENT','ACTIVE','HOLDING')"
                : "";
        if (!positionStatus) {
            gaps.add("COLUMN_MISSING:position.status");
        }

        String lifecycleSelect = lifecycleAvailable
                ? "tls.stage, tls.previous_stage, tls.lifecycle_score, tls.continuation_days, tls.breadth, tls.leader_count, tls.rotation_score, tls.crowding_score"
                : "NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL";
        String structuralSelect = structuralAvailable
                ? "sedl.arbiter_tier, sedl.price_state, sedl.structure_state"
                : "NULL, NULL, NULL";

        try {
            return list("""
                    SELECT p.id, p.symbol, %s, %s, %s,
                           %s,
                           %s
                    FROM position p
                    %s
                    %s
                    %s
                    %s
                    ORDER BY p.symbol, p.id
                    """.formatted(stockName, status, themeExpr, lifecycleSelect, structuralSelect,
                    mappingJoin, lifecycleJoin, structuralJoin, whereStatus), Map.of("date", date)).stream()
                    .map(this::row)
                    .toList();
        } catch (RuntimeException ex) {
            gaps.add("QUERY_FAILED:position_lifecycle_exit_review:" + ex.getClass().getSimpleName());
            return List.of();
        }
    }

    private RowDraft draft(Object[] r) {
        String themeTag = stringAt(r, 4);
        String stage = stringAt(r, 5);
        String dataGap = null;
        if (themeTag == null) {
            dataGap = "THEME_TAG_MISSING";
        } else if (stage == null) {
            dataGap = "LIFECYCLE_STATE_MISSING";
        }
        return new RowDraft(
                longAt(r, 0),
                stringAt(r, 1),
                stringAt(r, 2),
                stringAt(r, 3),
                themeTag,
                stage,
                stringAt(r, 6),
                decimalAt(r, 7),
                intAt(r, 8),
                intAt(r, 9),
                intAt(r, 10),
                decimalAt(r, 11),
                decimalAt(r, 12),
                stringAt(r, 13),
                stringAt(r, 14),
                stringAt(r, 15),
                dataGap);
    }

    private void upsert(LocalDate date, RowDraft draft, ReviewMapping mapping) {
        String reasonJson = reasonJson(draft, mapping);
        entityManager.createNativeQuery("""
                INSERT INTO lifecycle_exit_review_shadow (
                    review_date, symbol, stock_name, position_id, theme_tag,
                    lifecycle_stage, previous_stage, lifecycle_score, continuation_days,
                    breadth, leader_count, rotation_score, crowding_score,
                    review_action, review_priority, review_only, auto_sell_enabled,
                    stop_mutation_enabled, position_mutation_enabled, source_position_status,
                    structural_exit_tier, price_state, structure_state, data_gap_reason, reason_json
                ) VALUES (
                    :reviewDate, :symbol, :stockName, :positionId, :themeTag,
                    :lifecycleStage, :previousStage, :lifecycleScore, :continuationDays,
                    :breadth, :leaderCount, :rotationScore, :crowdingScore,
                    :reviewAction, :reviewPriority, 1, 0, 0, 0, :sourcePositionStatus,
                    :structuralExitTier, :priceState, :structureState, :dataGapReason, :reasonJson
                )
                ON DUPLICATE KEY UPDATE
                    stock_name = VALUES(stock_name),
                    position_id = VALUES(position_id),
                    theme_tag = VALUES(theme_tag),
                    lifecycle_stage = VALUES(lifecycle_stage),
                    previous_stage = VALUES(previous_stage),
                    lifecycle_score = VALUES(lifecycle_score),
                    continuation_days = VALUES(continuation_days),
                    breadth = VALUES(breadth),
                    leader_count = VALUES(leader_count),
                    rotation_score = VALUES(rotation_score),
                    crowding_score = VALUES(crowding_score),
                    review_action = VALUES(review_action),
                    review_priority = VALUES(review_priority),
                    review_only = 1,
                    auto_sell_enabled = 0,
                    stop_mutation_enabled = 0,
                    position_mutation_enabled = 0,
                    source_position_status = VALUES(source_position_status),
                    structural_exit_tier = VALUES(structural_exit_tier),
                    price_state = VALUES(price_state),
                    structure_state = VALUES(structure_state),
                    data_gap_reason = VALUES(data_gap_reason),
                    reason_json = VALUES(reason_json)
                """)
                .setParameter("reviewDate", date)
                .setParameter("symbol", draft.symbol())
                .setParameter("stockName", draft.stockName())
                .setParameter("positionId", draft.positionId())
                .setParameter("themeTag", draft.themeTag())
                .setParameter("lifecycleStage", draft.lifecycleStage())
                .setParameter("previousStage", draft.previousStage())
                .setParameter("lifecycleScore", draft.lifecycleScore())
                .setParameter("continuationDays", draft.continuationDays())
                .setParameter("breadth", draft.breadth())
                .setParameter("leaderCount", draft.leaderCount())
                .setParameter("rotationScore", draft.rotationScore())
                .setParameter("crowdingScore", draft.crowdingScore())
                .setParameter("reviewAction", mapping.reviewAction())
                .setParameter("reviewPriority", mapping.reviewPriority())
                .setParameter("sourcePositionStatus", draft.sourcePositionStatus())
                .setParameter("structuralExitTier", draft.structuralExitTier())
                .setParameter("priceState", draft.priceState())
                .setParameter("structureState", draft.structureState())
                .setParameter("dataGapReason", draft.dataGapReason())
                .setParameter("reasonJson", reasonJson)
                .executeUpdate();
    }

    private List<LifecycleExitReviewResponse.Item> readRows(LocalDate date, List<String> gaps) {
        try {
            return list("""
                    SELECT id, review_date, symbol, stock_name, position_id, theme_tag,
                           lifecycle_stage, previous_stage, lifecycle_score, continuation_days,
                           breadth, leader_count, rotation_score, crowding_score,
                           review_action, review_priority, review_only, auto_sell_enabled,
                           stop_mutation_enabled, position_mutation_enabled, source_position_status,
                           structural_exit_tier, price_state, structure_state, data_gap_reason,
                           reason_json, created_at
                    FROM lifecycle_exit_review_shadow
                    WHERE review_date = :date
                    ORDER BY
                        CASE review_priority
                            WHEN 'CRITICAL' THEN 1
                            WHEN 'HIGH' THEN 2
                            WHEN 'MEDIUM' THEN 3
                            WHEN 'REVIEW' THEN 4
                            ELSE 5
                        END,
                        symbol
                    """, Map.of("date", date)).stream()
                    .map(this::row)
                    .map(this::item)
                    .toList();
        } catch (RuntimeException ex) {
            gaps.add("QUERY_FAILED:lifecycle_exit_review_shadow:" + ex.getClass().getSimpleName());
            return List.of();
        }
    }

    private LifecycleExitReviewResponse.Item item(Object[] r) {
        return new LifecycleExitReviewResponse.Item(
                longAt(r, 0),
                dateAt(r, 1),
                stringAt(r, 2),
                stringAt(r, 3),
                longAt(r, 4),
                stringAt(r, 5),
                stringAt(r, 6),
                stringAt(r, 7),
                decimalAt(r, 8),
                intAt(r, 9),
                intAt(r, 10),
                intAt(r, 11),
                decimalAt(r, 12),
                decimalAt(r, 13),
                stringAt(r, 14),
                stringAt(r, 15),
                booleanAt(r, 16),
                booleanAt(r, 17),
                booleanAt(r, 18),
                booleanAt(r, 19),
                stringAt(r, 20),
                stringAt(r, 21),
                stringAt(r, 22),
                stringAt(r, 23),
                stringAt(r, 24),
                stringAt(r, 25),
                dateTimeAt(r, 26));
    }

    private void addRowGaps(List<LifecycleExitReviewResponse.Item> rows, List<String> gaps) {
        long missingTheme = rows.stream().filter(i -> "THEME_TAG_MISSING".equals(i.dataGapReason())).count();
        long missingLifecycle = rows.stream().filter(i -> "LIFECYCLE_STATE_MISSING".equals(i.dataGapReason())).count();
        if (missingTheme > 0) {
            gaps.add("ITEM_THEME_TAG_MISSING:count=" + missingTheme);
        }
        if (missingLifecycle > 0) {
            gaps.add("ITEM_LIFECYCLE_STATE_MISSING:count=" + missingLifecycle);
        }
    }

    private boolean mappingAvailable() {
        return tableExists("stock_theme_mapping")
                && columnExists("stock_theme_mapping", "symbol")
                && columnExists("stock_theme_mapping", "theme_tag");
    }

    private boolean lifecycleAvailable(List<String> gaps) {
        if (!tableExists("theme_lifecycle_state")) {
            gaps.add("TABLE_MISSING:theme_lifecycle_state");
            return false;
        }
        boolean ok = true;
        for (String column : List.of("trading_date", "theme_tag", "stage", "previous_stage", "lifecycle_score",
                "continuation_days", "breadth", "leader_count", "rotation_score", "crowding_score")) {
            if (!columnExists("theme_lifecycle_state", column)) {
                gaps.add("COLUMN_MISSING:theme_lifecycle_state." + column);
                ok = false;
            }
        }
        return ok;
    }

    private boolean structuralAvailable(List<String> gaps) {
        if (!tableExists("structural_exit_decision_log")) {
            gaps.add("TABLE_MISSING:structural_exit_decision_log");
            return false;
        }
        boolean ok = true;
        for (String column : List.of("id", "symbol", "evaluation_date", "arbiter_tier", "price_state", "structure_state")) {
            if (!columnExists("structural_exit_decision_log", column)) {
                gaps.add("COLUMN_MISSING:structural_exit_decision_log." + column);
                ok = false;
            }
        }
        return ok;
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

    private String reasonJson(RowDraft draft, ReviewMapping mapping) {
        return "{"
                + "\"reviewOnly\":true,"
                + "\"advisoryOnly\":true,"
                + "\"doesNotAffectBuySell\":true,"
                + "\"autoSellEnabled\":false,"
                + "\"stopMutationEnabled\":false,"
                + "\"positionMutationEnabled\":false,"
                + "\"reviewAction\":\"" + escape(mapping.reviewAction()) + "\","
                + "\"reviewPriority\":\"" + escape(mapping.reviewPriority()) + "\","
                + "\"lifecycleStage\":" + jsonString(draft.lifecycleStage()) + ","
                + "\"dataGapReason\":" + jsonString(draft.dataGapReason())
                + "}";
    }

    private String jsonString(String value) {
        return value == null ? "null" : "\"" + escape(value) + "\"";
    }

    private String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String stringAt(Object[] row, int index) {
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
        Long value = longAt(row, index);
        return value == null ? null : Math.toIntExact(value);
    }

    private Long longAt(Object[] row, int index) {
        if (row.length <= index || row[index] == null) {
            return null;
        }
        return toLong(row[index]);
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

    private LocalDateTime dateTimeAt(Object[] row, int index) {
        if (row.length <= index || row[index] == null) {
            return null;
        }
        Object value = row[index];
        if (value instanceof LocalDateTime dt) {
            return dt;
        }
        if (value instanceof Timestamp ts) {
            return ts.toLocalDateTime();
        }
        return LocalDateTime.parse(value.toString().replace(" ", "T"));
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

    public record ReviewMapping(String reviewAction, String reviewPriority) {}

    private record RowDraft(Long positionId,
                            String symbol,
                            String stockName,
                            String sourcePositionStatus,
                            String themeTag,
                            String lifecycleStage,
                            String previousStage,
                            BigDecimal lifecycleScore,
                            Integer continuationDays,
                            Integer breadth,
                            Integer leaderCount,
                            BigDecimal rotationScore,
                            BigDecimal crowdingScore,
                            String structuralExitTier,
                            String priceState,
                            String structureState,
                            String dataGapReason) {}
}
