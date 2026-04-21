package com.austin.trading.service;

import com.austin.trading.dto.internal.PortfolioRiskDecision;
import com.austin.trading.dto.internal.PortfolioRiskInput;
import com.austin.trading.dto.internal.RankedCandidate;
import com.austin.trading.engine.PortfolioRiskEngine;
import com.austin.trading.entity.PortfolioRiskDecisionEntity;
import com.austin.trading.entity.PositionEntity;
import com.austin.trading.repository.PortfolioRiskDecisionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * P0.5 Risk Layer.
 *
 * <p>Provides two evaluation modes:</p>
 * <ol>
 *   <li><b>Portfolio gate</b> — checks whether any new position is allowed at all.
 *       Called at the top of {@code FinalDecisionService} in place of the old
 *       inline {@code openPositions >= maxPos} check.</li>
 *   <li><b>Per-candidate</b> — checks theme over-exposure and already-held guard
 *       for each ranked candidate that passed timing.</li>
 * </ol>
 *
 * <p>Both results are persisted to {@code portfolio_risk_decision} for attribution.</p>
 */
@Service
public class PortfolioRiskService {

    private static final Logger log = LoggerFactory.getLogger(PortfolioRiskService.class);

    private final PortfolioRiskEngine              riskEngine;
    private final ThemeExposureService             themeExposureService;
    private final PortfolioRiskDecisionRepository  riskRepo;
    private final ScoreConfigService               scoreConfig;

    public PortfolioRiskService(PortfolioRiskEngine riskEngine,
                                 ThemeExposureService themeExposureService,
                                 PortfolioRiskDecisionRepository riskRepo,
                                 ScoreConfigService scoreConfig) {
        this.riskEngine          = riskEngine;
        this.themeExposureService = themeExposureService;
        this.riskRepo            = riskRepo;
        this.scoreConfig         = scoreConfig;
    }

    /**
     * Evaluate whether the portfolio can accept any new position today.
     * Persists a portfolio-gate row (symbol = null).
     *
     * @return the gate decision; {@code approved = false} means no new entries
     */
    @Transactional
    public PortfolioRiskDecision evaluatePortfolioGate(List<PositionEntity> openPositions,
                                                        LocalDate tradingDate) {
        int maxPos            = scoreConfig.getInt("portfolio.max_open_positions", 3);
        boolean allowStrong   = scoreConfig.getBoolean("portfolio.allow_new_when_full_strong", false);

        PortfolioRiskInput in = new PortfolioRiskInput(
                null, openPositions, maxPos, allowStrong, Map.of(), BigDecimal.ZERO);
        PortfolioRiskDecision d = riskEngine.evaluatePortfolioGate(in, tradingDate);

        persist(d);
        log.info("[PortfolioRisk] gate: approved={} openPos={}/{} blockReason={}",
                d.approved(), d.openPositionCount(), d.maxPositions(), d.blockReason());
        return d;
    }

    /**
     * Evaluate per-candidate risk for all candidates that passed timing.
     * Persists one row per candidate.
     *
     * @return all decisions (approved and blocked)
     */
    @Transactional
    public List<PortfolioRiskDecision> evaluateCandidates(List<RankedCandidate> candidates,
                                                           List<PositionEntity> openPositions,
                                                           LocalDate tradingDate) {
        int maxPos            = scoreConfig.getInt("portfolio.max_open_positions", 3);
        boolean allowStrong   = scoreConfig.getBoolean("portfolio.allow_new_when_full_strong", false);
        BigDecimal maxThemePct = scoreConfig.getDecimal("risk.max_theme_exposure_pct",
                new BigDecimal("60.0"));

        Map<String, BigDecimal> themeExposure = themeExposureService.compute(openPositions);

        List<PortfolioRiskInput> inputs = candidates.stream()
                .map(c -> new PortfolioRiskInput(
                        c, openPositions, maxPos, allowStrong, themeExposure, maxThemePct))
                .toList();

        List<PortfolioRiskDecision> decisions = riskEngine.evaluateCandidates(inputs, tradingDate);
        decisions.forEach(this::persist);

        long approved = decisions.stream().filter(PortfolioRiskDecision::approved).count();
        log.info("[PortfolioRisk] candidates: total={} approved={}", decisions.size(), approved);
        return decisions;
    }

    /** Latest risk decision for a symbol on a given date. */
    public java.util.Optional<PortfolioRiskDecision> getLatestForDate(LocalDate date, String symbol) {
        return riskRepo.findTopByTradingDateAndSymbolOrderByIdDesc(date, symbol)
                .map(this::toDecision);
    }

    /** All risk decisions for a given date. */
    public List<PortfolioRiskDecision> getByDate(LocalDate date) {
        return riskRepo.findByTradingDate(date).stream().map(this::toDecision).toList();
    }

    // ── private helpers ────────────────────────────────────────────────────

    private void persist(PortfolioRiskDecision d) {
        riskRepo.save(toEntity(d));
    }

    private PortfolioRiskDecisionEntity toEntity(PortfolioRiskDecision d) {
        PortfolioRiskDecisionEntity e = new PortfolioRiskDecisionEntity();
        e.setTradingDate(d.tradingDate());
        e.setSymbol(d.symbol());
        e.setApproved(d.approved());
        e.setBlockReason(d.blockReason());
        e.setOpenPositionCount(d.openPositionCount());
        e.setMaxPositions(d.maxPositions());
        e.setCandidateTheme(d.candidateTheme());
        e.setThemeExposurePct(d.themeExposurePct());
        e.setPayloadJson(d.payloadJson());
        return e;
    }

    private PortfolioRiskDecision toDecision(PortfolioRiskDecisionEntity e) {
        return new PortfolioRiskDecision(
                e.getTradingDate(), e.getSymbol(),
                e.isApproved(), e.getBlockReason(),
                e.getOpenPositionCount(), e.getMaxPositions(),
                e.getCandidateTheme(), e.getThemeExposurePct(),
                e.getPayloadJson()
        );
    }
}
