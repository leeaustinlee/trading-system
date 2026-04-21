package com.austin.trading.engine;

import com.austin.trading.dto.internal.PortfolioRiskDecision;
import com.austin.trading.dto.internal.PortfolioRiskInput;
import com.austin.trading.entity.PositionEntity;
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
import java.util.Map;

/**
 * Stateless portfolio-level risk classifier.
 *
 * <h3>Portfolio-gate checks (symbol = null)</h3>
 * <ul>
 *   <li>{@code openPositions >= maxOpenPositions} → {@code PORTFOLIO_FULL}
 *       unless {@code allowWhenFullStrong = true} and all positions are STRONG/HOLD.</li>
 * </ul>
 *
 * <h3>Per-candidate checks</h3>
 * <ul>
 *   <li>{@code themeExposurePct > maxThemeExposurePct} → {@code THEME_OVER_EXPOSED}</li>
 *   <li>Candidate symbol already in open positions → {@code ALREADY_HELD}</li>
 * </ul>
 *
 * <p>This engine is <b>pure</b>: no I/O, no DB, no clock dependency.</p>
 */
@Component
public class PortfolioRiskEngine {

    private static final Logger log = LoggerFactory.getLogger(PortfolioRiskEngine.class);

    public static final String BLOCK_PORTFOLIO_FULL    = "PORTFOLIO_FULL";
    public static final String BLOCK_THEME_OVER_EXPOSED = "THEME_OVER_EXPOSED";
    public static final String BLOCK_ALREADY_HELD       = "ALREADY_HELD";

    private final ScoreConfigService scoreConfig;
    private final ObjectMapper       objectMapper;

    public PortfolioRiskEngine(ScoreConfigService scoreConfig, ObjectMapper objectMapper) {
        this.scoreConfig  = scoreConfig;
        this.objectMapper = objectMapper;
    }

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Portfolio-gate evaluation: can any new position be opened?
     * Returns a decision with {@code symbol = null}.
     */
    public PortfolioRiskDecision evaluatePortfolioGate(PortfolioRiskInput in, LocalDate date) {
        int openCount = in.openPositions() == null ? 0 : in.openPositions().size();
        int maxPos    = in.maxOpenPositions();

        if (openCount >= maxPos) {
            if (in.allowWhenFullStrong() && allStrongOrHold(in.openPositions())) {
                log.debug("[PortfolioRisk] gate: PASS (full but all STRONG/HOLD, allowWhenFullStrong=true)");
                return approved(date, null, openCount, maxPos, null, null);
            }
            return blocked(date, null, BLOCK_PORTFOLIO_FULL,
                    "openPositions=" + openCount + " >= max=" + maxPos,
                    openCount, maxPos, null, null);
        }
        return approved(date, null, openCount, maxPos, null, null);
    }

    /**
     * Per-candidate evaluation: is this specific candidate safe to add?
     */
    public PortfolioRiskDecision evaluateCandidate(PortfolioRiskInput in, LocalDate date) {
        if (in.candidate() == null) return approved(date, null, 0, in.maxOpenPositions(), null, null);

        String sym = in.candidate().symbol();

        // Belt-and-suspenders: already-held check (ranking layer normally catches this)
        boolean held = in.openPositions() != null && in.openPositions().stream()
                .anyMatch(p -> sym.equalsIgnoreCase(p.getSymbol()));
        if (held) {
            return blocked(date, sym, BLOCK_ALREADY_HELD, sym + " already in open positions",
                    sizeOf(in.openPositions()), in.maxOpenPositions(),
                    in.candidate().themeTag(), null);
        }

        // Theme over-exposure check
        String theme = in.candidate().themeTag();
        if (theme != null && in.themeExposureMap() != null && in.maxThemeExposurePct() != null) {
            BigDecimal exposure = in.themeExposureMap().getOrDefault(theme, BigDecimal.ZERO);
            if (exposure.compareTo(in.maxThemeExposurePct()) > 0) {
                return blocked(date, sym, BLOCK_THEME_OVER_EXPOSED,
                        "theme=" + theme + " exposure=" + exposure + "% > max=" + in.maxThemeExposurePct() + "%",
                        sizeOf(in.openPositions()), in.maxOpenPositions(), theme, exposure);
            }
        }

        return approved(date, sym,
                sizeOf(in.openPositions()), in.maxOpenPositions(),
                theme,
                theme != null && in.themeExposureMap() != null
                        ? in.themeExposureMap().getOrDefault(theme, BigDecimal.ZERO) : null);
    }

    /** Batch per-candidate evaluation. */
    public List<PortfolioRiskDecision> evaluateCandidates(List<PortfolioRiskInput> inputs, LocalDate date) {
        if (inputs == null) return List.of();
        List<PortfolioRiskDecision> out = new ArrayList<>(inputs.size());
        for (PortfolioRiskInput in : inputs) out.add(evaluateCandidate(in, date));
        return out;
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private boolean allStrongOrHold(List<PositionEntity> positions) {
        if (positions == null || positions.isEmpty()) return false;
        return positions.stream().allMatch(p ->
                "STRONG".equals(p.getReviewStatus()) || "HOLD".equals(p.getReviewStatus()));
    }

    private int sizeOf(List<PositionEntity> list) {
        return list == null ? 0 : list.size();
    }

    private PortfolioRiskDecision approved(LocalDate date, String symbol,
                                            int openCount, int maxPos,
                                            String theme, BigDecimal themeExp) {
        return new PortfolioRiskDecision(date, symbol, true, null,
                openCount, maxPos, theme, themeExp,
                buildPayload(symbol, true, null, openCount, maxPos, theme, themeExp));
    }

    private PortfolioRiskDecision blocked(LocalDate date, String symbol,
                                           String reason, String detail,
                                           int openCount, int maxPos,
                                           String theme, BigDecimal themeExp) {
        log.debug("[PortfolioRisk] BLOCKED {} — {}: {}", symbol, reason, detail);
        return new PortfolioRiskDecision(date, symbol, false, reason,
                openCount, maxPos, theme, themeExp,
                buildPayload(symbol, false, reason, openCount, maxPos, theme, themeExp));
    }

    private String buildPayload(String symbol, boolean approved, String reason,
                                 int openCount, int maxPos, String theme, BigDecimal themeExp) {
        try {
            ObjectNode n = objectMapper.createObjectNode();
            n.put("symbol",            symbol);
            n.put("approved",          approved);
            n.put("blockReason",       reason);
            n.put("openPositionCount", openCount);
            n.put("maxPositions",      maxPos);
            n.put("candidateTheme",    theme);
            if (themeExp != null) n.put("themeExposurePct", themeExp);
            return objectMapper.writeValueAsString(n);
        } catch (Exception e) {
            log.warn("[PortfolioRiskEngine] payload serialization failed");
            return "{}";
        }
    }
}
