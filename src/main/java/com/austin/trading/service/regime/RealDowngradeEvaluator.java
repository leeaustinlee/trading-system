package com.austin.trading.service.regime;

import com.austin.trading.dto.internal.MarketRegimeDecision;
import com.austin.trading.entity.PaperTradeEntity;
import com.austin.trading.entity.PositionEntity;
import com.austin.trading.repository.PaperTradeRepository;
import com.austin.trading.repository.PositionRepository;
import com.austin.trading.service.ScoreConfigService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * P0.2 — real C-grade downgrade.
 *
 * <p>Background: the past 30 days of {@code market_snapshot.market_grade}
 * showed A=19 / B=12 / <b>C=0</b> / NULL=19. The previous risk filter could
 * only <i>upgrade</i> (e.g. clearing PANIC_VOLATILITY blocks) — it never
 * forced the engine output down to grade C even on objectively bad days.
 * This class adds the missing direction: <b>any one</b> of the four hard
 * triggers below firing forces {@code market_grade='C'} (and {@code
 * tradeAllowed=false}), regardless of what the underlying classifier said.</p>
 *
 * <h3>Triggers</h3>
 * <ol>
 *   <li><b>{@code CONSEC_DOWN}</b> — TAIEX has 3+ consecutive down days in
 *       the most recent 5-day window.</li>
 *   <li><b>{@code TAIEX_BELOW_60MA}</b> — today's TAIEX close is strictly
 *       below its 60-day moving average.</li>
 *   <li><b>{@code SEMI_WEAK}</b> — semiconductor proxy (default 2330)
 *       5-day cumulative return &lt; -3%.</li>
 *   <li><b>{@code DAILY_LOSS_CAP}</b> — today's realised PnL on closed
 *       paper trades + position-table CLOSED rows + still-open positions'
 *       unrealised PnL (best-effort) sums to &lt; -5,000 NTD.</li>
 * </ol>
 *
 * <h3>Fail-safe</h3>
 * <p>If the underlying data is missing — e.g. the {@link MarketIndexProvider}
 * has no TAIEX history yet, or paper-trade rows haven't been MTM'd today —
 * the trigger simply <b>does not fire</b>. The evaluator never fabricates
 * data and never throws on missing inputs; the worst-case behaviour is
 * "P0.2 had no effect today", not "the whole regime evaluation broke".</p>
 *
 * <h3>Feature flag</h3>
 * <p>The whole evaluator is gated by
 * {@code market_regime.real_downgrade.enabled} (default <b>true</b>). When
 * disabled it short-circuits and returns the engine's original decision.</p>
 *
 * <p>Stateless — safe to make a Spring singleton.</p>
 */
@Component
public class RealDowngradeEvaluator {

    private static final Logger log = LoggerFactory.getLogger(RealDowngradeEvaluator.class);

    public static final String FLAG_ENABLED        = "market_regime.real_downgrade.enabled";

    /** A single key — but documented as the only NEW config to be folded into DEFAULTS. */
    public static final String CONFIG_KEY_ENABLED  = FLAG_ENABLED;

    // ── trigger names (also used as the JSON reason tag) ─────────────────
    public static final String TRIGGER_CONSEC_DOWN     = "CONSEC_DOWN";
    public static final String TRIGGER_TAIEX_BELOW_60MA = "TAIEX_BELOW_60MA";
    public static final String TRIGGER_SEMI_WEAK       = "SEMI_WEAK";
    public static final String TRIGGER_DAILY_LOSS_CAP  = "DAILY_LOSS_CAP";

    // ── tunable thresholds (read from score_config with conservative defaults) ──
    private static final String CFG_CONSEC_DAYS         = "market_regime.consec_down.days";          // default 3
    private static final String CFG_CONSEC_WINDOW       = "market_regime.consec_down.window";        // default 5
    private static final String CFG_MA_LENGTH           = "market_regime.taiex_ma.length";           // default 60
    private static final String CFG_SEMI_SYMBOL         = "market_regime.semi.symbol";               // default 2330
    private static final String CFG_SEMI_LOOKBACK       = "market_regime.semi.lookback_days";        // default 5
    private static final String CFG_SEMI_THRESHOLD_PCT  = "market_regime.semi.weak_threshold_pct";   // default -3.0
    private static final String CFG_DAILY_LOSS_CAP_NTD  = "market_regime.daily_loss_cap_ntd";        // default -5000

    private final ScoreConfigService    scoreConfig;
    private final MarketIndexProvider   marketIndexProvider;
    private final PaperTradeRepository  paperTradeRepository;
    private final PositionRepository    positionRepository;
    private final ObjectMapper          objectMapper;

    public RealDowngradeEvaluator(
            ScoreConfigService scoreConfig,
            MarketIndexProvider marketIndexProvider,
            PaperTradeRepository paperTradeRepository,
            PositionRepository positionRepository,
            ObjectMapper objectMapper
    ) {
        this.scoreConfig         = scoreConfig;
        this.marketIndexProvider = marketIndexProvider;
        this.paperTradeRepository = paperTradeRepository;
        this.positionRepository  = positionRepository;
        this.objectMapper        = objectMapper;
    }

    /**
     * Inspect the engine's decision and either return it untouched, or
     * return a downgraded copy with {@code marketGrade='C'},
     * {@code tradeAllowed=false}, an empty allowed-setups list, and
     * downgrade reasons appended to {@code reasonsJson} +
     * {@code inputSnapshotJson.downgradeReasons}.
     *
     * <p>The engine result is treated as immutable; we always return a
     * <i>new</i> {@link MarketRegimeDecision} record on downgrade.</p>
     *
     * @param engineDecision the freshly-evaluated decision from
     *                       {@code MarketRegimeEngine}
     * @param tradingDate    the trading date being evaluated
     * @return either {@code engineDecision} or its downgraded copy
     */
    public MarketRegimeDecision applyIfNeeded(
            MarketRegimeDecision engineDecision, LocalDate tradingDate
    ) {
        if (engineDecision == null) return null;

        boolean enabled = scoreConfig.getBoolean(FLAG_ENABLED, true);
        if (!enabled) {
            log.debug("[RealDowngrade] flag disabled — pass through engine decision");
            return engineDecision;
        }

        LocalDate asOf = tradingDate != null ? tradingDate : LocalDate.now();
        List<TriggerHit> hits = new ArrayList<>();

        // Each evaluator is independently fail-safe: an exception or missing data
        // produces an empty Optional rather than a fired trigger.
        evalConsecDown(asOf).ifPresent(hits::add);
        evalTaiexBelowMa(asOf).ifPresent(hits::add);
        evalSemiWeak(asOf).ifPresent(hits::add);
        evalDailyLossCap(asOf).ifPresent(hits::add);

        if (hits.isEmpty()) {
            return engineDecision; // no trigger fired — preserve A/B
        }

        return downgrade(engineDecision, hits);
    }

    // ── trigger 1: CONSEC_DOWN ──────────────────────────────────────────

    private Optional<TriggerHit> evalConsecDown(LocalDate asOf) {
        try {
            int requiredDown = Math.max(2, scoreConfig.getInt(CFG_CONSEC_DAYS,   3));
            int window       = Math.max(requiredDown + 1,
                              scoreConfig.getInt(CFG_CONSEC_WINDOW, 5));

            // Need (window) closes to derive (window-1) day-over-day deltas.
            List<BigDecimal> closes = marketIndexProvider.getTaiexCloses(asOf, window);
            if (closes == null || closes.size() < requiredDown + 1) {
                return Optional.empty(); // not enough data — fail-safe
            }

            int currentStreak = 0;
            int maxStreak     = 0;
            for (int i = 1; i < closes.size(); i++) {
                BigDecimal prev = closes.get(i - 1);
                BigDecimal curr = closes.get(i);
                if (prev == null || curr == null) {
                    currentStreak = 0;
                    continue;
                }
                if (curr.compareTo(prev) < 0) {
                    currentStreak++;
                    maxStreak = Math.max(maxStreak, currentStreak);
                } else {
                    currentStreak = 0;
                }
            }
            if (maxStreak >= requiredDown) {
                String reason = String.format(
                        "%s: max consecutive down days=%d in last %d closes (>= %d)",
                        TRIGGER_CONSEC_DOWN, maxStreak, closes.size(), requiredDown);
                return Optional.of(new TriggerHit(TRIGGER_CONSEC_DOWN, reason));
            }
            return Optional.empty();
        } catch (Exception ex) {
            log.warn("[RealDowngrade] CONSEC_DOWN eval failed (treating as no-fire)", ex);
            return Optional.empty();
        }
    }

    // ── trigger 2: TAIEX_BELOW_60MA ─────────────────────────────────────

    private Optional<TriggerHit> evalTaiexBelowMa(LocalDate asOf) {
        try {
            int maLen = Math.max(5, scoreConfig.getInt(CFG_MA_LENGTH, 60));

            // Prefer pre-computed MA if provider supplies one.
            Optional<BigDecimal> precomputedMa = marketIndexProvider.getTaiex60DayMa(asOf);

            // We always need today's close for the comparison.
            List<BigDecimal> closes = marketIndexProvider.getTaiexCloses(asOf, maLen);
            if (closes == null || closes.isEmpty()) return Optional.empty();
            BigDecimal today = closes.get(closes.size() - 1);
            if (today == null) return Optional.empty();

            BigDecimal ma;
            if (precomputedMa.isPresent() && precomputedMa.get() != null) {
                ma = precomputedMa.get();
            } else {
                if (closes.size() < maLen) return Optional.empty(); // not enough history
                BigDecimal sum = BigDecimal.ZERO;
                int count = 0;
                for (BigDecimal c : closes) {
                    if (c == null) return Optional.empty();
                    sum = sum.add(c);
                    count++;
                }
                ma = sum.divide(BigDecimal.valueOf(count), 4, RoundingMode.HALF_UP);
            }
            if (today.compareTo(ma) < 0) {
                String reason = String.format(
                        "%s: today close=%s < %d-day MA=%s",
                        TRIGGER_TAIEX_BELOW_60MA, today.toPlainString(), maLen, ma.toPlainString());
                return Optional.of(new TriggerHit(TRIGGER_TAIEX_BELOW_60MA, reason));
            }
            return Optional.empty();
        } catch (Exception ex) {
            log.warn("[RealDowngrade] TAIEX_BELOW_60MA eval failed (treating as no-fire)", ex);
            return Optional.empty();
        }
    }

    // ── trigger 3: SEMI_WEAK ────────────────────────────────────────────

    private Optional<TriggerHit> evalSemiWeak(LocalDate asOf) {
        try {
            String symbol  = scoreConfig.getString(CFG_SEMI_SYMBOL, "2330");
            int lookback   = Math.max(2, scoreConfig.getInt(CFG_SEMI_LOOKBACK, 5));
            BigDecimal threshold = scoreConfig.getDecimal(
                    CFG_SEMI_THRESHOLD_PCT, new BigDecimal("-3.0"));

            // Need lookback+1 closes for "5-day cumulative return" (today vs 5 days ago).
            List<BigDecimal> closes = marketIndexProvider.getSymbolCloses(
                    symbol, asOf, lookback + 1);
            if (closes == null || closes.size() < lookback + 1) {
                log.debug("[RealDowngrade] SEMI_WEAK: insufficient history for {} (have {} need {})",
                        symbol, closes == null ? 0 : closes.size(), lookback + 1);
                return Optional.empty();
            }
            BigDecimal old   = closes.get(0);
            BigDecimal today = closes.get(closes.size() - 1);
            if (old == null || today == null || old.signum() <= 0) return Optional.empty();

            BigDecimal returnPct = today.subtract(old)
                    .multiply(BigDecimal.valueOf(100))
                    .divide(old, 4, RoundingMode.HALF_UP);

            if (returnPct.compareTo(threshold) < 0) {
                String reason = String.format(
                        "%s: %s %d-day return=%s%% < %s%%",
                        TRIGGER_SEMI_WEAK, symbol, lookback,
                        returnPct.toPlainString(), threshold.toPlainString());
                return Optional.of(new TriggerHit(TRIGGER_SEMI_WEAK, reason));
            }
            return Optional.empty();
        } catch (Exception ex) {
            log.warn("[RealDowngrade] SEMI_WEAK eval failed (treating as no-fire)", ex);
            return Optional.empty();
        }
    }

    // ── trigger 4: DAILY_LOSS_CAP ───────────────────────────────────────

    private Optional<TriggerHit> evalDailyLossCap(LocalDate asOf) {
        try {
            BigDecimal capNtd = scoreConfig.getDecimal(
                    CFG_DAILY_LOSS_CAP_NTD, new BigDecimal("-5000"));

            BigDecimal total = BigDecimal.ZERO;

            // (a) closed paper trades that exited today
            try {
                List<PaperTradeEntity> closedToday = paperTradeRepository
                        .findByStatusAndExitDateBetweenOrderByExitDateDescIdDesc(
                                "CLOSED", asOf, asOf);
                if (closedToday != null) {
                    for (PaperTradeEntity t : closedToday) {
                        if (t.getPnlAmount() != null) total = total.add(t.getPnlAmount());
                    }
                }
            } catch (Exception ignored) {
                // PaperTrade table may not exist in some dev DBs — skip silently.
            }

            // (b) closed positions today (CLOSED at any time today)
            try {
                LocalDateTime startOfDay = asOf.atStartOfDay();
                LocalDateTime endOfDay   = asOf.plusDays(1).atStartOfDay();
                BigDecimal realized = positionRepository.sumRealizedPnlBetween(startOfDay, endOfDay);
                if (realized != null) total = total.add(realized);
            } catch (Exception ignored) {
                // ditto
            }

            // (c) still-OPEN positions: best-effort unrealised PnL using the
            // latest reference price we can find. PositionEntity carries no
            // current-price column, so we approximate as zero unrealised — the
            // payload_json may contain a snapshot but parsing is brittle here.
            // The CLOSED bucket above already captures realised intraday damage,
            // which is the dominant signal for "today went bad".
            try {
                List<PositionEntity> openPositions =
                        positionRepository.findByStatus("OPEN");
                if (openPositions != null) {
                    for (PositionEntity p : openPositions) {
                        BigDecimal unrealized = readUnrealizedFromPayload(p);
                        if (unrealized != null) total = total.add(unrealized);
                    }
                }
            } catch (Exception ignored) {
                // ditto
            }

            if (total.compareTo(capNtd) < 0) {
                String reason = String.format(
                        "%s: today total PnL=%s NTD < cap=%s NTD",
                        TRIGGER_DAILY_LOSS_CAP, total.toPlainString(), capNtd.toPlainString());
                return Optional.of(new TriggerHit(TRIGGER_DAILY_LOSS_CAP, reason));
            }
            return Optional.empty();
        } catch (Exception ex) {
            log.warn("[RealDowngrade] DAILY_LOSS_CAP eval failed (treating as no-fire)", ex);
            return Optional.empty();
        }
    }

    /**
     * Best-effort read of {@code payload_json.unrealized_pnl} or
     * {@code payload_json.unrealizedPnl}; returns null if the field is
     * absent / unparsable. Position table doesn't store this as a column,
     * so we don't pretend to know it precisely.
     */
    private BigDecimal readUnrealizedFromPayload(PositionEntity p) {
        if (p == null || p.getPayloadJson() == null || p.getPayloadJson().isBlank()) return null;
        try {
            var node = objectMapper.readTree(p.getPayloadJson());
            String[] keys = {"unrealized_pnl", "unrealizedPnl", "unrealized", "unrealised_pnl"};
            for (String k : keys) {
                if (node.hasNonNull(k)) {
                    try { return new BigDecimal(node.get(k).asText()); }
                    catch (NumberFormatException ignored) { /* fall through */ }
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    // ── downgrade construction ──────────────────────────────────────────

    private MarketRegimeDecision downgrade(
            MarketRegimeDecision original, List<TriggerHit> hits
    ) {
        // Build the new reasons array: existing reasons + downgrade reasons.
        ArrayNode reasonsArr = objectMapper.createArrayNode();
        if (original.reasonsJson() != null && !original.reasonsJson().isBlank()) {
            try {
                var existing = objectMapper.readTree(original.reasonsJson());
                if (existing.isArray()) existing.forEach(reasonsArr::add);
            } catch (JsonProcessingException ignored) { /* skip */ }
        }
        for (TriggerHit h : hits) reasonsArr.add(h.reason);

        // Embed downgrade trigger metadata into inputSnapshotJson under
        // "downgradeReasons" so audit/UI can render the structured list
        // (each hit having {trigger, reason, triggered_at}).
        String inputSnapshotJson = enrichInputSnapshot(original.inputSnapshotJson(), hits);

        String reasonsJson;
        try {
            reasonsJson = objectMapper.writeValueAsString(reasonsArr);
        } catch (JsonProcessingException e) {
            reasonsJson = original.reasonsJson();
        }

        StringBuilder summary = new StringBuilder()
                .append("DOWNGRADED→C ")
                .append(original.summary() == null ? "" : original.summary())
                .append(" downgrade=[");
        for (int i = 0; i < hits.size(); i++) {
            if (i > 0) summary.append(',');
            summary.append(hits.get(i).trigger);
        }
        summary.append(']');

        // Force market_grade='C', no trades allowed, no setups.
        return new MarketRegimeDecision(
                original.id(),                  // null on first creation; preserved on re-eval
                original.tradingDate(),
                original.evaluatedAt(),
                original.regimeType(),          // keep the underlying regime type for audit
                "C",
                false,
                BigDecimal.ZERO.setScale(3, RoundingMode.HALF_UP),
                List.of(),
                summary.toString(),
                reasonsJson,
                inputSnapshotJson,
                original.confidenceLevel(),
                original.missingSignals()
        );
    }

    private String enrichInputSnapshot(String original, List<TriggerHit> hits) {
        ObjectNode root;
        try {
            if (original != null && !original.isBlank()) {
                var parsed = objectMapper.readTree(original);
                if (parsed.isObject()) root = (ObjectNode) parsed;
                else                   root = objectMapper.createObjectNode();
            } else {
                root = objectMapper.createObjectNode();
            }
        } catch (JsonProcessingException e) {
            root = objectMapper.createObjectNode();
        }

        ArrayNode arr = root.putArray("downgradeReasons");
        String triggeredAt = LocalDateTime.now().toString();
        for (TriggerHit h : hits) {
            ObjectNode item = arr.addObject();
            item.put("trigger",      h.trigger);
            item.put("reason",       h.reason);
            item.put("triggered_at", triggeredAt);
        }
        try {
            return objectMapper.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            return original;
        }
    }

    // ── small value-type ────────────────────────────────────────────────

    private record TriggerHit(String trigger, String reason) {}
}
