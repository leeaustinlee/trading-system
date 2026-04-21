package com.austin.trading.service;

import com.austin.trading.dto.internal.ExecutionDecisionInput;
import com.austin.trading.dto.internal.ExecutionDecisionOutput;
import com.austin.trading.engine.ExecutionDecisionEngine;
import com.austin.trading.entity.ExecutionDecisionLogEntity;
import com.austin.trading.repository.ExecutionDecisionLogRepository;
import com.austin.trading.repository.ExecutionTimingDecisionRepository;
import com.austin.trading.repository.MarketRegimeDecisionRepository;
import com.austin.trading.repository.PortfolioRiskDecisionRepository;
import com.austin.trading.repository.SetupDecisionRepository;
import com.austin.trading.repository.StockRankingSnapshotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * P0.6 Execution Layer.
 *
 * <p>Calls {@link ExecutionDecisionEngine}, resolves upstream DB IDs for attribution,
 * and persists each decision to {@code execution_decision_log}.</p>
 *
 * <p>Java emits {@code ENTER / SKIP / EXIT / WEAKEN} here; Codex may veto via the
 * {@code codexVetoed} flag but cannot force entry.</p>
 */
@Service
public class ExecutionDecisionService {

    private static final Logger log = LoggerFactory.getLogger(ExecutionDecisionService.class);

    private final ExecutionDecisionEngine             engine;
    private final ExecutionDecisionLogRepository      execRepo;
    private final MarketRegimeDecisionRepository      regimeRepo;
    private final StockRankingSnapshotRepository      rankingRepo;
    private final SetupDecisionRepository             setupRepo;
    private final ExecutionTimingDecisionRepository   timingRepo;
    private final PortfolioRiskDecisionRepository     riskRepo;

    public ExecutionDecisionService(
            ExecutionDecisionEngine engine,
            ExecutionDecisionLogRepository execRepo,
            MarketRegimeDecisionRepository regimeRepo,
            StockRankingSnapshotRepository rankingRepo,
            SetupDecisionRepository setupRepo,
            ExecutionTimingDecisionRepository timingRepo,
            PortfolioRiskDecisionRepository riskRepo) {
        this.engine      = engine;
        this.execRepo    = execRepo;
        this.regimeRepo  = regimeRepo;
        this.rankingRepo = rankingRepo;
        this.setupRepo   = setupRepo;
        this.timingRepo  = timingRepo;
        this.riskRepo    = riskRepo;
    }

    /**
     * Evaluate and persist execution decisions for a batch of inputs.
     *
     * @return all decisions (ENTER and SKIP)
     */
    @Transactional
    public List<ExecutionDecisionOutput> logDecisions(List<ExecutionDecisionInput> inputs,
                                                       LocalDate tradingDate) {
        List<ExecutionDecisionOutput> raw = engine.evaluate(inputs);
        List<ExecutionDecisionOutput> enriched = raw.stream()
                .map(d -> enrichWithIds(d, tradingDate))
                .toList();
        execRepo.saveAll(enriched.stream().map(this::toEntity).toList());

        long enters = enriched.stream()
                .filter(d -> ExecutionDecisionEngine.ACTION_ENTER.equals(d.action())).count();
        log.info("[ExecutionDecision] tradingDate={} total={} ENTER={}",
                tradingDate, enriched.size(), enters);
        return enriched;
    }

    /**
     * Evaluate and persist a single execution decision.
     */
    @Transactional
    public ExecutionDecisionOutput logDecision(ExecutionDecisionInput input, LocalDate tradingDate) {
        ExecutionDecisionOutput raw      = engine.evaluateOne(input);
        ExecutionDecisionOutput enriched = enrichWithIds(raw, tradingDate);
        execRepo.save(toEntity(enriched));
        return enriched;
    }

    /** ENTER decisions for a given date. */
    public List<ExecutionDecisionOutput> getEnterByDate(LocalDate date) {
        return execRepo.findEnterByTradingDate(date).stream().map(this::toOutput).toList();
    }

    /** All execution decisions for a given date. */
    public List<ExecutionDecisionOutput> getByDate(LocalDate date) {
        return execRepo.findByTradingDate(date).stream().map(this::toOutput).toList();
    }

    // ── private helpers ────────────────────────────────────────────────────

    /**
     * Look up upstream DB IDs and return an enriched copy of the output.
     */
    private ExecutionDecisionOutput enrichWithIds(ExecutionDecisionOutput d, LocalDate date) {
        if (d == null) return null;
        String sym = d.symbol();

        Long regimeId  = regimeRepo.findTopByTradingDateOrderByEvaluatedAtDescIdDesc(date)
                .map(e -> e.getId()).orElse(null);
        Long rankId    = rankingRepo.findTopByTradingDateAndSymbolOrderByIdDesc(date, sym)
                .map(e -> e.getId()).orElse(null);
        Long setupId   = setupRepo.findTopByTradingDateAndSymbolAndValidTrueOrderByIdDesc(date, sym)
                .map(e -> e.getId()).orElse(null);
        Long timingId  = timingRepo.findTopByTradingDateAndSymbolOrderByIdDesc(date, sym)
                .map(e -> e.getId()).orElse(null);
        Long riskId    = riskRepo.findTopByTradingDateAndSymbolOrderByIdDesc(date, sym)
                .map(e -> e.getId()).orElse(null);

        return new ExecutionDecisionOutput(
                d.tradingDate(), d.symbol(),
                d.action(), d.reasonCode(), d.codexVetoed(),
                regimeId, rankId, setupId, timingId, riskId,
                d.payloadJson());
    }

    private ExecutionDecisionLogEntity toEntity(ExecutionDecisionOutput d) {
        ExecutionDecisionLogEntity e = new ExecutionDecisionLogEntity();
        e.setTradingDate(d.tradingDate());
        e.setSymbol(d.symbol());
        e.setAction(d.action());
        e.setReasonCode(d.reasonCode());
        e.setCodexVetoed(d.codexVetoed());
        e.setRegimeDecisionId(d.regimeDecisionId());
        e.setRankingSnapshotId(d.rankingSnapshotId());
        e.setSetupDecisionId(d.setupDecisionId());
        e.setTimingDecisionId(d.timingDecisionId());
        e.setRiskDecisionId(d.riskDecisionId());
        e.setPayloadJson(d.payloadJson());
        return e;
    }

    private ExecutionDecisionOutput toOutput(ExecutionDecisionLogEntity e) {
        return new ExecutionDecisionOutput(
                e.getTradingDate(), e.getSymbol(),
                e.getAction(), e.getReasonCode(), e.isCodexVetoed(),
                e.getRegimeDecisionId(), e.getRankingSnapshotId(),
                e.getSetupDecisionId(), e.getTimingDecisionId(),
                e.getRiskDecisionId(), e.getPayloadJson());
    }
}
