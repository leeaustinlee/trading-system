package com.austin.trading.engine;

import com.austin.trading.dto.internal.ExecutionTimingDecision;
import com.austin.trading.dto.internal.MarketRegimeDecision;
import com.austin.trading.dto.internal.SetupDecision;
import com.austin.trading.dto.internal.TimingEvaluationInput;
import com.austin.trading.service.ScoreConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Stateless timing classifier: decides whether the market window for entry is open.
 *
 * <p>Rules per setup type:</p>
 * <ul>
 *   <li><b>BREAKOUT_CONTINUATION</b> — {@code entryTriggered} or ({@code nearDayHigh} AND NOT {@code belowOpen})</li>
 *   <li><b>PULLBACK_CONFIRMATION</b> — NOT {@code belowOpen} (price has not rolled over from today's open)</li>
 *   <li><b>EVENT_SECOND_LEG</b>      — NOT {@code belowOpen}; volume spike elevates urgency when also {@code nearDayHigh}</li>
 * </ul>
 *
 * <p>Stale-signal check runs before type-specific rules; {@code signalAgeDays > threshold} → {@code STALE}.</p>
 *
 * <p>Regime {@code riskMultiplier} caps urgency: &lt;0.7 → LOW, &lt;1.0 → at most MEDIUM.</p>
 *
 * <p>This engine is <b>pure</b>: no I/O, no DB, no clock dependency.</p>
 */
@Component
public class ExecutionTimingEngine {

    private static final Logger log = LoggerFactory.getLogger(ExecutionTimingEngine.class);

    public static final String MODE_BREAKOUT_READY = "BREAKOUT_READY";
    public static final String MODE_PULLBACK_BOUNCE = "PULLBACK_BOUNCE";
    public static final String MODE_EVENT_LAUNCH    = "EVENT_LAUNCH";
    public static final String MODE_WAIT            = "WAIT";
    public static final String MODE_STALE           = "STALE";
    public static final String MODE_NO_SETUP        = "NO_SETUP";

    public static final String URGENCY_HIGH   = "HIGH";
    public static final String URGENCY_MEDIUM = "MEDIUM";
    public static final String URGENCY_LOW    = "LOW";

    private final ScoreConfigService scoreConfig;
    private final ObjectMapper       objectMapper;

    public ExecutionTimingEngine(ScoreConfigService scoreConfig, ObjectMapper objectMapper) {
        this.scoreConfig  = scoreConfig;
        this.objectMapper = objectMapper;
    }

    // ── Public API ────────────────────────────────────────────────────────

    public List<ExecutionTimingDecision> evaluate(List<TimingEvaluationInput> inputs) {
        if (inputs == null) return List.of();
        List<ExecutionTimingDecision> out = new ArrayList<>(inputs.size());
        for (TimingEvaluationInput in : inputs) out.add(evaluateOne(in));
        return out;
    }

    public ExecutionTimingDecision evaluateOne(TimingEvaluationInput in) {
        if (in == null) return null;

        if (in.setup() == null || !in.setup().valid()) {
            return blocked(in, MODE_NO_SETUP, "NO_VALID_SETUP");
        }

        String type      = in.setup().setupType();
        int    staleDays = staleDaysFor(type);

        if (in.signalAgeDays() > staleDays) {
            return blocked(in, MODE_STALE,
                    "STALE_SIGNAL(age=" + in.signalAgeDays() + " maxDays=" + staleDays + ")");
        }

        // v2.15：swing setup hard gate（feature flag DEFAULT true，1–2 週波段必要）
        boolean swingEnabled = scoreConfig.getBoolean("execution.swing-setup.enabled", true);
        SwingSetupResult swing = evaluateSwingSetup(in);
        if (swingEnabled && !swing.passed()) {
            return blocked(in, MODE_WAIT,
                    "SWING_SETUP_NOT_CONFIRMED(" + swing.reason() + ")");
        }

        return switch (type) {
            case SetupEngine.SETUP_BREAKOUT -> evalBreakout(in, staleDays);
            case SetupEngine.SETUP_PULLBACK -> evalPullback(in, staleDays);
            case SetupEngine.SETUP_EVENT    -> evalEvent(in, staleDays);
            default -> blocked(in, MODE_NO_SETUP, "UNKNOWN_SETUP_TYPE:" + type);
        };
    }

    /** v2.15：swing setup 結果（pure data，方便 unit test 與 trace 紀錄）。 */
    public record SwingSetupResult(boolean passed, String reason) {}

    /**
     * v2.15：多日 swing setup 確認。
     *
     * <p>因 TimingEvaluationInput 暫時不直接帶 daily kline 序列，先用現有 signal flags 做
     * 多日 setup 的近似檢查（後續可改成接 kline repo 計算實際 5MA / 量比）。</p>
     *
     * <p>滿足以下任一即視為 PASSED：</p>
     * <ul>
     *   <li>BREAKOUT+VOLUME — entryTriggered=true 且 volumeSpike=true（突破 + 量增）</li>
     *   <li>NEW_HIGH+VOLUME — nearDayHigh=true 且 volumeSpike=true 且未跌破開盤（新高 + 量 + 收紅）</li>
     *   <li>STEADY_TREND — entryTriggered=true 且未跌破開盤與昨收（突破後守穩）</li>
     * </ul>
     *
     * <p>否則回 NO_SWING_CONFIRMATION（在 enabled=true 時阻擋）。</p>
     */
    public SwingSetupResult evaluateSwingSetup(TimingEvaluationInput in) {
        if (in == null) return new SwingSetupResult(false, "NULL_INPUT");
        boolean breakoutWithVolume = in.entryTriggered() && in.volumeSpike();
        if (breakoutWithVolume) return new SwingSetupResult(true, "BREAKOUT+VOLUME");

        boolean newHighWithVolume = in.nearDayHigh() && in.volumeSpike() && !in.belowOpen();
        if (newHighWithVolume)    return new SwingSetupResult(true, "NEW_HIGH+VOLUME");

        boolean steadyTrend = in.entryTriggered() && !in.belowOpen() && !in.belowPrevClose();
        if (steadyTrend)          return new SwingSetupResult(true, "STEADY_TREND");

        return new SwingSetupResult(false, "NO_SWING_CONFIRMATION");
    }

    // ── Per-setup-type evaluators ──────────────────────────────────────────

    private ExecutionTimingDecision evalBreakout(TimingEvaluationInput in, int staleDays) {
        if (in.entryTriggered()) {
            return approved(in, MODE_BREAKOUT_READY, URGENCY_HIGH, staleDays);
        }
        if (in.nearDayHigh() && !in.belowOpen()) {
            return approved(in, MODE_BREAKOUT_READY, URGENCY_MEDIUM, staleDays);
        }
        return blocked(in, MODE_WAIT, "BREAKOUT:WAITING_FOR_ENTRY_SIGNAL");
    }

    private ExecutionTimingDecision evalPullback(TimingEvaluationInput in, int staleDays) {
        if (in.belowOpen()) {
            return blocked(in, MODE_WAIT, "PULLBACK:PRICE_STILL_DECLINING(belowOpen)");
        }
        String urgency = in.belowPrevClose() ? URGENCY_LOW : URGENCY_MEDIUM;
        return approved(in, MODE_PULLBACK_BOUNCE, urgency, staleDays);
    }

    private ExecutionTimingDecision evalEvent(TimingEvaluationInput in, int staleDays) {
        if (in.belowOpen()) {
            return blocked(in, MODE_WAIT, "EVENT:BASE_BROKEN_DOWN(belowOpen)");
        }
        String urgency = (in.volumeSpike() && in.nearDayHigh()) ? URGENCY_HIGH : URGENCY_MEDIUM;
        return approved(in, MODE_EVENT_LAUNCH, urgency, staleDays);
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private int staleDaysFor(String setupType) {
        if (setupType == null) return 0;
        return switch (setupType) {
            case SetupEngine.SETUP_BREAKOUT -> scoreConfig.getInt("timing.breakout.stale_days", 3);
            case SetupEngine.SETUP_PULLBACK -> scoreConfig.getInt("timing.pullback.stale_days", 5);
            case SetupEngine.SETUP_EVENT    -> scoreConfig.getInt("timing.event.stale_days",    4);
            default -> 0;
        };
    }

    private String capUrgency(String urgency, MarketRegimeDecision regime) {
        if (regime == null) return urgency;
        BigDecimal mult = regime.riskMultiplier();
        if (mult == null) return urgency;
        if (mult.compareTo(new BigDecimal("0.70")) < 0) return URGENCY_LOW;
        if (mult.compareTo(BigDecimal.ONE) < 0 && URGENCY_HIGH.equals(urgency)) return URGENCY_MEDIUM;
        return urgency;
    }

    private ExecutionTimingDecision approved(TimingEvaluationInput in,
                                              String mode, String rawUrgency, int staleDays) {
        String urgency = capUrgency(rawUrgency, in.regime());
        LocalDate date = dateOf(in);
        String sym     = symOf(in);
        log.debug("[ExecutionTiming] APPROVED {} mode={} urgency={}", sym, mode, urgency);
        return new ExecutionTimingDecision(date, sym, setupType(in),
                true, mode, urgency,
                false, staleDays, in.signalAgeDays(),
                null, buildPayload(in, mode, urgency, "approved"));
    }

    private ExecutionTimingDecision blocked(TimingEvaluationInput in,
                                             String mode, String reason) {
        LocalDate date = dateOf(in);
        String sym     = symOf(in);
        log.debug("[ExecutionTiming] BLOCKED {} mode={} reason={}", sym, mode, reason);
        int staleDays = setupType(in) != null ? staleDaysFor(setupType(in)) : 0;
        return new ExecutionTimingDecision(date, sym, setupType(in),
                false, mode, URGENCY_LOW,
                MODE_STALE.equals(mode), staleDays, in.signalAgeDays(),
                reason, buildPayload(in, mode, URGENCY_LOW, "blocked"));
    }

    private LocalDate dateOf(TimingEvaluationInput in) {
        if (in.candidate() != null) return in.candidate().tradingDate();
        if (in.setup() != null)     return in.setup().tradingDate();
        return null;
    }

    private String symOf(TimingEvaluationInput in) {
        if (in.candidate() != null) return in.candidate().symbol();
        if (in.setup() != null)     return in.setup().symbol();
        return "UNKNOWN";
    }

    private String setupType(TimingEvaluationInput in) {
        return in.setup() != null ? in.setup().setupType() : null;
    }

    private String buildPayload(TimingEvaluationInput in, String mode, String urgency, String outcome) {
        try {
            ObjectNode n = objectMapper.createObjectNode();
            n.put("mode",            mode);
            n.put("urgency",         urgency);
            n.put("outcome",         outcome);
            n.put("setupType",       setupType(in));
            n.put("signalAgeDays",   in.signalAgeDays());
            n.put("nearDayHigh",     in.nearDayHigh());
            n.put("belowOpen",       in.belowOpen());
            n.put("belowPrevClose",  in.belowPrevClose());
            n.put("entryTriggered",  in.entryTriggered());
            n.put("volumeSpike",     in.volumeSpike());
            if (in.regime() != null) {
                n.put("regimeType",       in.regime().regimeType());
                n.put("riskMultiplier",   in.regime().riskMultiplier());
            }
            return objectMapper.writeValueAsString(n);
        } catch (Exception e) {
            log.warn("[ExecutionTimingEngine] payload serialization failed");
            return "{}";
        }
    }
}
