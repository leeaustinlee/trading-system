package com.austin.trading.service;

import com.austin.trading.dto.internal.MarketRegimeDecision;
import com.austin.trading.dto.internal.RankCandidateInput;
import com.austin.trading.dto.internal.RankedCandidate;
import com.austin.trading.dto.internal.ThemeStrengthDecision;
import com.austin.trading.dto.request.FinalDecisionCandidateRequest;
import com.austin.trading.engine.StockRankingEngine;
import com.austin.trading.engine.ThemeSelectionEngine;
import com.austin.trading.entity.PositionEntity;
import com.austin.trading.entity.StockRankingSnapshotEntity;
import com.austin.trading.repository.PositionRepository;
import com.austin.trading.repository.StockRankingSnapshotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * P0.2 Ranking Layer.
 *
 * <p>Takes pre-scored candidates (from {@code FinalDecisionService}'s scoring
 * pipeline), applies hard-reject rules (held, cooldown, codex veto), computes
 * the new {@code selectionScore} formula, persists a ranked snapshot per
 * candidate, and returns the full ranked list.</p>
 *
 * <p>Score proxies used in P0.2 (to be replaced in P1 once dedicated engines
 * exist):</p>
 * <ul>
 *   <li>{@code relativeStrengthScore} → {@code baseScore / 10} (or javaStructureScore)</li>
 *   <li>{@code themeStrengthScore}    → {@code finalThemeScore / 10}</li>
 * </ul>
 */
@Service
public class StockRankingService {

    private static final Logger log = LoggerFactory.getLogger(StockRankingService.class);

    private static final BigDecimal TEN = BigDecimal.TEN;

    private final StockRankingEngine             rankingEngine;
    private final StockRankingSnapshotRepository snapshotRepo;
    private final PositionRepository             positionRepository;
    private final CooldownService                cooldownService;
    private final ThemeSelectionEngine           themeSelectionEngine;

    public StockRankingService(StockRankingEngine rankingEngine,
                               StockRankingSnapshotRepository snapshotRepo,
                               PositionRepository positionRepository,
                               CooldownService cooldownService,
                               ThemeSelectionEngine themeSelectionEngine) {
        this.rankingEngine        = rankingEngine;
        this.snapshotRepo         = snapshotRepo;
        this.positionRepository   = positionRepository;
        this.cooldownService      = cooldownService;
        this.themeSelectionEngine = themeSelectionEngine;
    }

    /**
     * Rank candidates using real ThemeStrengthDecision data (P1.1).
     *
     * @param themeDecisions map themeTag → ThemeStrengthDecision from ThemeStrengthService
     */
    @Transactional
    public List<RankedCandidate> rank(List<FinalDecisionCandidateRequest> scoredCandidates,
                                     LocalDate tradingDate,
                                     MarketRegimeDecision regime,
                                     Map<String, ThemeStrengthDecision> themeDecisions) {
        return rankInternal(scoredCandidates, tradingDate, regime,
                themeDecisions != null ? themeDecisions : Collections.emptyMap());
    }

    /**
     * Backward-compatible overload (P0.2 proxy — no ThemeStrengthDecision).
     */
    @Transactional
    public List<RankedCandidate> rank(List<FinalDecisionCandidateRequest> scoredCandidates,
                                     LocalDate tradingDate,
                                     MarketRegimeDecision regime) {
        return rankInternal(scoredCandidates, tradingDate, regime, Collections.emptyMap());
    }

    private List<RankedCandidate> rankInternal(List<FinalDecisionCandidateRequest> scoredCandidates,
                                               LocalDate tradingDate,
                                               MarketRegimeDecision regime,
                                               Map<String, ThemeStrengthDecision> themeDecisions) {
        if (scoredCandidates == null || scoredCandidates.isEmpty()) {
            return List.of();
        }

        Set<String> heldSymbols = positionRepository.findByStatus("OPEN").stream()
                .map(PositionEntity::getSymbol)
                .collect(Collectors.toSet());

        List<RankCandidateInput> inputs = new ArrayList<>(scoredCandidates.size());
        for (FinalDecisionCandidateRequest c : scoredCandidates) {
            boolean inCooldown  = cooldownService.isInCooldown(c.stockCode(), null);
            boolean alreadyHeld = heldSymbols.contains(c.stockCode());
            boolean codexVetoed = Boolean.TRUE.equals(c.isVetoed());

            String tag = resolveThemeTag(c.stockCode(), tradingDate);
            ThemeStrengthDecision thd = tag != null ? themeDecisions.get(tag) : null;
            BigDecimal themeScore = thd != null ? thd.strengthScore() : themeProxy(c);

            inputs.add(new RankCandidateInput(
                    tradingDate,
                    c.stockCode(),
                    c.javaStructureScore(),
                    c.claudeScore(),
                    c.codexScore(),
                    c.finalRankScore(),
                    rsProxy(c),
                    themeScore,
                    tag,
                    codexVetoed,
                    inCooldown,
                    alreadyHeld
            ));
        }

        List<RankedCandidate> ranked = rankingEngine.rank(inputs, regime);

        persistAll(ranked);

        int eligible = (int) ranked.stream().filter(RankedCandidate::eligibleForSetup).count();
        log.info("[StockRanking] tradingDate={} total={} eligible={}", tradingDate, ranked.size(), eligible);

        return ranked;
    }

    /**
     * Latest eligible snapshots for today.
     */
    public List<StockRankingSnapshotEntity> getEligibleForToday() {
        return snapshotRepo.findEligibleByTradingDate(LocalDate.now());
    }

    /**
     * All snapshots for a given date (includes rejected candidates).
     */
    public List<StockRankingSnapshotEntity> getSnapshotsForDate(LocalDate date) {
        return snapshotRepo.findByTradingDate(date);
    }

    // ── private helpers ───────────────────────────────────────────────────

    private void persistAll(List<RankedCandidate> ranked) {
        List<StockRankingSnapshotEntity> entities = ranked.stream()
                .map(this::toEntity)
                .toList();
        snapshotRepo.saveAll(entities);
    }

    private StockRankingSnapshotEntity toEntity(RankedCandidate r) {
        StockRankingSnapshotEntity e = new StockRankingSnapshotEntity();
        e.setTradingDate(r.tradingDate());
        e.setSymbol(r.symbol());
        e.setSelectionScore(r.selectionScore());
        e.setRelativeStrengthScore(r.relativeStrengthScore());
        e.setThemeStrengthScore(r.themeStrengthScore());
        e.setThesisScore(r.thesisScore());
        e.setThemeTag(r.themeTag());
        e.setVetoed(r.vetoed());
        e.setEligibleForSetup(r.eligibleForSetup());
        e.setRejectionReason(r.rejectionReason());
        e.setScoreBreakdownJson(r.scoreBreakdownJson());
        return e;
    }

    /**
     * Relative-strength proxy (P0.2): uses {@code baseScore} as-is on [0, 10] scale.
     * {@code baseScore} is already scored on 0-10 by {@code CandidateScanService}.
     * Will be replaced by {@code RelativeStrengthEngine} output in P1.
     * Falls back to {@code javaStructureScore} when baseScore is missing.
     */
    private static BigDecimal rsProxy(FinalDecisionCandidateRequest c) {
        if (c.baseScore() != null && c.baseScore().compareTo(BigDecimal.ZERO) > 0) {
            return c.baseScore().min(TEN);
        }
        return c.javaStructureScore() != null ? c.javaStructureScore() : BigDecimal.ZERO;
    }

    /**
     * Theme-strength proxy (P0.2): uses {@code finalThemeScore} as-is on [0, 10] scale,
     * capped at 10.  Will be replaced by {@code ThemeStrengthEngine} output in P1.1.
     */
    private static BigDecimal themeProxy(FinalDecisionCandidateRequest c) {
        if (c.finalThemeScore() != null && c.finalThemeScore().compareTo(BigDecimal.ZERO) > 0) {
            return c.finalThemeScore().min(TEN);
        }
        return BigDecimal.ZERO;
    }

    private String resolveThemeTag(String symbol, LocalDate tradingDate) {
        return themeSelectionEngine.getLeadingThemeForStock(symbol, tradingDate).orElse(null);
    }
}
