package com.austin.trading.engine;

import com.austin.trading.dto.internal.MarketRegimeDecision;
import com.austin.trading.dto.internal.RankCandidateInput;
import com.austin.trading.dto.internal.RankedCandidate;
import com.austin.trading.service.ScoreConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Stateless ranker that converts pre-scored {@link RankCandidateInput} objects
 * into an ordered {@link RankedCandidate} list.
 *
 * <p>Rule order per candidate:</p>
 * <ol>
 *   <li>Hard reject  — {@code alreadyHeld}, {@code inCooldown}, {@code codexVetoed}</li>
 *   <li>Regime block — setup type not allowed by current regime (skipped in P0.2; Setup layer owns this)</li>
 *   <li>Score         — {@code selectionScore} computed from weighted inputs</li>
 *   <li>Threshold     — score below {@code ranking.min_selection_score} marks ineligible</li>
 *   <li>Top-N cap     — only top {@code ranking.top_n} pass {@code eligibleForSetup=true}</li>
 * </ol>
 *
 * <p>This engine is pure: no I/O, no clock dependency.</p>
 */
@Component
public class StockRankingEngine {

    private static final Logger log = LoggerFactory.getLogger(StockRankingEngine.class);

    private final ScoreConfigService scoreConfig;
    private final ObjectMapper objectMapper;

    public StockRankingEngine(ScoreConfigService scoreConfig, ObjectMapper objectMapper) {
        this.scoreConfig  = scoreConfig;
        this.objectMapper = objectMapper;
    }

    /**
     * Rank all candidates; the returned list preserves all inputs (including
     * rejected ones) so callers can inspect rejection reasons.
     */
    public List<RankedCandidate> rank(List<RankCandidateInput> inputs,
                                      MarketRegimeDecision regime) {
        if (inputs == null || inputs.isEmpty()) return List.of();

        BigDecimal wRs     = scoreConfig.getDecimal("ranking.weight.rs",             new BigDecimal("0.30"));
        BigDecimal wTheme  = scoreConfig.getDecimal("ranking.weight.theme",           new BigDecimal("0.25"));
        BigDecimal wJava   = scoreConfig.getDecimal("ranking.weight.java_structure",  new BigDecimal("0.25"));
        BigDecimal wThesis = scoreConfig.getDecimal("ranking.weight.thesis",          new BigDecimal("0.20"));
        BigDecimal minScore= scoreConfig.getDecimal("ranking.min_selection_score",    new BigDecimal("4.0"));
        int topN           = scoreConfig.getInt("ranking.top_n", 3);

        List<RankedCandidate> all = new ArrayList<>(inputs.size());

        for (RankCandidateInput in : inputs) {
            // ── Hard reject ─────────────────────────────────────────────────
            if (in.alreadyHeld()) {
                all.add(reject(in, "ALREADY_HELD"));
                continue;
            }
            if (in.inCooldown()) {
                all.add(reject(in, "IN_COOLDOWN"));
                continue;
            }
            if (in.codexVetoed()) {
                // In P0.2 codexVetoed is mapped from VetoEngine.isVetoed() (combined result).
                // Rename reason to VETOED to avoid misleading attribution to Codex only.
                // Codex-specific veto separation is a P1.3 VetoEngine refactor task.
                all.add(reject(in, "VETOED"));
                continue;
            }

            // ── Score ────────────────────────────────────────────────────────
            BigDecimal rs     = safe(in.relativeStrengthScore(), in.javaStructureScore());
            BigDecimal theme  = safe(in.themeStrengthScore(),   BigDecimal.ZERO);
            BigDecimal java_  = safe(in.javaStructureScore(),   BigDecimal.ZERO);
            BigDecimal thesis = safe(in.claudeScore(),          BigDecimal.ZERO);

            BigDecimal sel = rs.multiply(wRs)
                    .add(theme.multiply(wTheme))
                    .add(java_.multiply(wJava))
                    .add(thesis.multiply(wThesis))
                    .setScale(3, RoundingMode.HALF_UP);

            String breakdown = breakdown(in, sel, wRs, wTheme, wJava, wThesis);

            boolean belowMin = sel.compareTo(minScore) < 0;
            all.add(new RankedCandidate(
                    in.tradingDate(), in.symbol(),
                    sel, rs, theme, thesis, in.themeTag(),
                    false,
                    !belowMin,   // tentatively eligible; top-N capped below
                    belowMin ? "SCORE_BELOW_MIN(" + sel + "<" + minScore + ")" : null,
                    breakdown
            ));
        }

        // ── Sort eligible desc, then cap top-N ──────────────────────────────
        List<RankedCandidate> eligible = all.stream()
                .filter(RankedCandidate::eligibleForSetup)
                .sorted(Comparator.comparing(RankedCandidate::selectionScore).reversed())
                .toList();

        if (eligible.size() > topN) {
            // mark overflow as ineligible
            all = new ArrayList<>(all); // mutable copy
            for (int i = topN; i < eligible.size(); i++) {
                RankedCandidate over = eligible.get(i);
                int idx = indexOf(all, over.symbol());
                if (idx >= 0) {
                    all.set(idx, withOverflow(over, topN));
                }
            }
        }

        log.debug("[StockRankingEngine] total={} eligible={} topN={}",
                all.size(), eligible.size(), Math.min(eligible.size(), topN));
        return all;
    }

    /**
     * Convenience method: returns only the top-N eligible candidates in
     * descending selectionScore order.
     */
    public List<RankedCandidate> topCandidates(List<RankCandidateInput> inputs,
                                                MarketRegimeDecision regime,
                                                int topN) {
        return rank(inputs, regime).stream()
                .filter(RankedCandidate::eligibleForSetup)
                .sorted(Comparator.comparing(RankedCandidate::selectionScore).reversed())
                .limit(topN)
                .toList();
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private static BigDecimal safe(BigDecimal v, BigDecimal fallback) {
        return (v == null || v.compareTo(BigDecimal.ZERO) == 0) ? fallback : v;
    }

    private static RankedCandidate reject(RankCandidateInput in, String reason) {
        return new RankedCandidate(
                in.tradingDate(), in.symbol(),
                BigDecimal.ZERO, in.relativeStrengthScore(), in.themeStrengthScore(),
                in.claudeScore(), in.themeTag(),
                true, false, reason, null
        );
    }

    private static RankedCandidate withOverflow(RankedCandidate r, int topN) {
        return new RankedCandidate(
                r.tradingDate(), r.symbol(),
                r.selectionScore(), r.relativeStrengthScore(), r.themeStrengthScore(),
                r.thesisScore(), r.themeTag(),
                false, false,
                "OUTSIDE_TOP_" + topN + "(score=" + r.selectionScore() + ")",
                r.scoreBreakdownJson()
        );
    }

    private static int indexOf(List<RankedCandidate> list, String symbol) {
        for (int i = 0; i < list.size(); i++) {
            if (symbol.equals(list.get(i).symbol())) return i;
        }
        return -1;
    }

    private String breakdown(RankCandidateInput in, BigDecimal sel,
                             BigDecimal wRs, BigDecimal wTheme, BigDecimal wJava, BigDecimal wThesis) {
        try {
            ObjectNode n = objectMapper.createObjectNode();
            n.put("selectionScore",       sel);
            n.put("rs_score",             in.relativeStrengthScore());
            n.put("theme_score",          in.themeStrengthScore());
            n.put("java_structure_score", in.javaStructureScore());
            n.put("thesis_score",         in.claudeScore());
            n.put("w_rs",                 wRs);
            n.put("w_theme",              wTheme);
            n.put("w_java",               wJava);
            n.put("w_thesis",             wThesis);
            n.put("codexVetoed",          in.codexVetoed());
            n.put("inCooldown",           in.inCooldown());
            n.put("alreadyHeld",          in.alreadyHeld());
            return objectMapper.writeValueAsString(n);
        } catch (Exception e) {
            log.warn("[StockRankingEngine] breakdown serialization failed for {}", in.symbol());
            return "{}";
        }
    }

    /** Expose resolved topN config for service layer use. */
    public int resolvedTopN() {
        return scoreConfig.getInt("ranking.top_n", 3);
    }

    /** Expose resolved minScore config for service layer use. */
    public BigDecimal resolvedMinScore() {
        return scoreConfig.getDecimal("ranking.min_selection_score", new BigDecimal("4.0"));
    }
}
