package com.austin.trading.engine;

import com.austin.trading.dto.internal.ExecutionDecisionInput;
import com.austin.trading.dto.internal.ExecutionDecisionOutput;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Stateless execution decision classifier.
 *
 * <h3>Rules (in evaluation order)</h3>
 * <ol>
 *   <li><b>Codex veto</b> — if {@code rankedCandidate.codexVetoed = true}, action = SKIP.
 *       Codex may veto but never force entry.</li>
 *   <li><b>Base action</b> — honors {@code FinalDecisionEngine} output:
 *       <ul>
 *         <li>{@code ENTER} → confirmed as ENTER (all upstream layers already passed)</li>
 *         <li>Anything else ({@code SKIP, REST, WATCH}) → SKIP</li>
 *       </ul>
 *   </li>
 * </ol>
 *
 * <p>EXIT and WEAKEN are emitted by the position-review pipeline, not here.
 * If they arrive as {@code baseAction} they are passed through unchanged.</p>
 *
 * <p>This engine is <b>pure</b>: no I/O, no DB, no clock dependency.</p>
 */
@Component
public class ExecutionDecisionEngine {

    private static final Logger log = LoggerFactory.getLogger(ExecutionDecisionEngine.class);

    public static final String ACTION_ENTER  = "ENTER";
    public static final String ACTION_SKIP   = "SKIP";
    public static final String ACTION_EXIT   = "EXIT";
    public static final String ACTION_WEAKEN = "WEAKEN";

    public static final String REASON_CONFIRMED     = "CONFIRMED";
    /** Any hard-reject veto from the ranking layer (cooldown, alreadyHeld, Codex, etc.). */
    public static final String REASON_VETOED        = "VETOED";
    /** @deprecated Use {@link #REASON_VETOED}. Kept for log-query backwards compat. */
    @Deprecated
    public static final String REASON_CODEX_VETO    = REASON_VETOED;
    public static final String REASON_BASE_PREFIX   = "BASE_ACTION_";

    private final ObjectMapper objectMapper;

    public ExecutionDecisionEngine(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // ── Public API ────────────────────────────────────────────────────────

    public List<ExecutionDecisionOutput> evaluate(List<ExecutionDecisionInput> inputs) {
        if (inputs == null) return List.of();
        List<ExecutionDecisionOutput> out = new ArrayList<>(inputs.size());
        for (ExecutionDecisionInput in : inputs) out.add(evaluateOne(in));
        return out;
    }

    public ExecutionDecisionOutput evaluateOne(ExecutionDecisionInput in) {
        if (in == null) return null;

        LocalDate date   = in.rankedCandidate() != null ? in.rankedCandidate().tradingDate() : null;
        String    symbol = in.rankedCandidate() != null ? in.rankedCandidate().symbol() : "UNKNOWN";
        boolean   vetoed = in.rankedCandidate() != null && in.rankedCandidate().vetoed();

        // Rule 1: Codex may veto, never force
        if (vetoed) {
            log.debug("[ExecutionDecision] {} SKIP — CODEX_VETO", symbol);
            return build(date, symbol, ACTION_SKIP, REASON_VETOED, true,
                    buildPayload(symbol, ACTION_SKIP, REASON_VETOED, in));
        }

        // Rule 2: pass-through EXIT / WEAKEN from position-review pipeline unchanged
        String base = in.baseAction() == null ? "" : in.baseAction().toUpperCase();
        if (ACTION_EXIT.equals(base) || ACTION_WEAKEN.equals(base)) {
            log.debug("[ExecutionDecision] {} {} — pass-through from position-review", symbol, base);
            return build(date, symbol, base, REASON_BASE_PREFIX + base, false,
                    buildPayload(symbol, base, REASON_BASE_PREFIX + base, in));
        }

        // Rule 3: confirm ENTER or downgrade to SKIP
        if (ACTION_ENTER.equals(base)) {
            log.debug("[ExecutionDecision] {} ENTER — CONFIRMED", symbol);
            return build(date, symbol, ACTION_ENTER, REASON_CONFIRMED, false,
                    buildPayload(symbol, ACTION_ENTER, REASON_CONFIRMED, in));
        }

        String reason = REASON_BASE_PREFIX + (base.isBlank() ? "UNKNOWN" : base);
        log.debug("[ExecutionDecision] {} SKIP — {}", symbol, reason);
        return build(date, symbol, ACTION_SKIP, reason, false,
                buildPayload(symbol, ACTION_SKIP, reason, in));
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private ExecutionDecisionOutput build(LocalDate date, String symbol,
                                           String action, String reason,
                                           boolean vetoed, String payload) {
        // Upstream IDs are null here — ExecutionDecisionService fills them after DB lookup
        return new ExecutionDecisionOutput(
                date, symbol, action, reason, vetoed,
                null, null, null, null, null,
                payload);
    }

    private String buildPayload(String symbol, String action, String reason,
                                 ExecutionDecisionInput in) {
        try {
            ObjectNode n = objectMapper.createObjectNode();
            n.put("symbol",      symbol);
            n.put("action",      action);
            n.put("reasonCode",  reason);
            n.put("baseAction",  in.baseAction());
            n.put("codexVetoed", in.rankedCandidate() != null && in.rankedCandidate().vetoed());
            if (in.regimeDecision()  != null) n.put("regimeType",   in.regimeDecision().regimeType());
            if (in.setupDecision()   != null) n.put("setupType",    in.setupDecision().setupType());
            if (in.timingDecision()  != null) n.put("timingMode",   in.timingDecision().timingMode());
            if (in.riskDecision()    != null) n.put("riskApproved", in.riskDecision().approved());
            return objectMapper.writeValueAsString(n);
        } catch (Exception e) {
            log.warn("[ExecutionDecisionEngine] payload serialization failed");
            return "{}";
        }
    }
}
