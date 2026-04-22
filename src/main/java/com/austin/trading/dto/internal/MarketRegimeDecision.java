package com.austin.trading.dto.internal;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Output contract of {@link com.austin.trading.engine.MarketRegimeEngine}.
 *
 * <p>The four supported {@code regimeType} values are:
 * <ul>
 *   <li>{@code BULL_TREND}</li>
 *   <li>{@code RANGE_CHOP}</li>
 *   <li>{@code WEAK_DOWNTREND}</li>
 *   <li>{@code PANIC_VOLATILITY}</li>
 * </ul>
 * Downstream layers (ranking / setup / timing / risk) must read
 * {@code tradeAllowed} and {@code allowedSetupTypes} instead of deriving
 * policy from the legacy A/B/C grade string.</p>
 *
 * <p>v2.6 MVP 新增：</p>
 * <ul>
 *   <li>{@code confidenceLevel}: HIGH / MEDIUM / LOW — 依缺資料程度</li>
 *   <li>{@code missingSignals}: 列出哪些 input key 為 null，下游可據此降倉而非 hard block</li>
 * </ul>
 *
 * <p>{@code id} is {@code null} for the engine result before persistence;
 * persisted decisions (loaded from repo) include the DB id.</p>
 */
public record MarketRegimeDecision(
        Long id,
        LocalDate tradingDate,
        LocalDateTime evaluatedAt,
        String regimeType,
        String marketGrade,
        boolean tradeAllowed,
        BigDecimal riskMultiplier,
        List<String> allowedSetupTypes,
        String summary,
        String reasonsJson,
        String inputSnapshotJson,
        String confidenceLevel,
        List<String> missingSignals
) {
    public static final String CONFIDENCE_HIGH   = "HIGH";
    public static final String CONFIDENCE_MEDIUM = "MEDIUM";
    public static final String CONFIDENCE_LOW    = "LOW";

    /** v2.6 ctor with confidence fields (engine 新用法，id 尚未持久化)。 */
    public MarketRegimeDecision(
            LocalDate tradingDate,
            LocalDateTime evaluatedAt,
            String regimeType,
            String marketGrade,
            boolean tradeAllowed,
            BigDecimal riskMultiplier,
            List<String> allowedSetupTypes,
            String summary,
            String reasonsJson,
            String inputSnapshotJson,
            String confidenceLevel,
            List<String> missingSignals
    ) {
        this(null, tradingDate, evaluatedAt, regimeType, marketGrade, tradeAllowed,
                riskMultiplier, allowedSetupTypes, summary, reasonsJson, inputSnapshotJson,
                confidenceLevel, missingSignals);
    }

    /** Legacy ctor（v2.5 與更早，無 confidence）：預設 HIGH + empty missingSignals。 */
    public MarketRegimeDecision(
            LocalDate tradingDate,
            LocalDateTime evaluatedAt,
            String regimeType,
            String marketGrade,
            boolean tradeAllowed,
            BigDecimal riskMultiplier,
            List<String> allowedSetupTypes,
            String summary,
            String reasonsJson,
            String inputSnapshotJson
    ) {
        this(null, tradingDate, evaluatedAt, regimeType, marketGrade, tradeAllowed,
                riskMultiplier, allowedSetupTypes, summary, reasonsJson, inputSnapshotJson,
                CONFIDENCE_HIGH, List.of());
    }

    /** Legacy 11-arg ctor with id（v2.5 持久化加載用）：預設 HIGH + empty。 */
    public MarketRegimeDecision(
            Long id,
            LocalDate tradingDate,
            LocalDateTime evaluatedAt,
            String regimeType,
            String marketGrade,
            boolean tradeAllowed,
            BigDecimal riskMultiplier,
            List<String> allowedSetupTypes,
            String summary,
            String reasonsJson,
            String inputSnapshotJson
    ) {
        this(id, tradingDate, evaluatedAt, regimeType, marketGrade, tradeAllowed,
                riskMultiplier, allowedSetupTypes, summary, reasonsJson, inputSnapshotJson,
                CONFIDENCE_HIGH, List.of());
    }
}
