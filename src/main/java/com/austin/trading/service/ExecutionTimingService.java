package com.austin.trading.service;

import com.austin.trading.dto.internal.ExecutionTimingDecision;
import com.austin.trading.dto.internal.TimingEvaluationInput;
import com.austin.trading.engine.ExecutionTimingEngine;
import com.austin.trading.entity.ExecutionTimingDecisionEntity;
import com.austin.trading.repository.ExecutionTimingDecisionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * P0.4 Timing Layer.
 *
 * <p>Calls {@link ExecutionTimingEngine} for each input, persists every decision
 * (approved and blocked) to {@code execution_timing_decision}, and returns the
 * full result list.  Blocked decisions are persisted with their rejection reason
 * for attribution and downstream learning.</p>
 *
 * <p>{@link com.austin.trading.service.FinalDecisionService} must only allow
 * candidates whose timing decision is {@code approved = true} to reach
 * {@link com.austin.trading.engine.FinalDecisionEngine}.</p>
 */
@Service
public class ExecutionTimingService {

    private static final Logger log = LoggerFactory.getLogger(ExecutionTimingService.class);

    private final ExecutionTimingEngine               timingEngine;
    private final ExecutionTimingDecisionRepository   timingRepo;

    public ExecutionTimingService(ExecutionTimingEngine timingEngine,
                                  ExecutionTimingDecisionRepository timingRepo) {
        this.timingEngine = timingEngine;
        this.timingRepo   = timingRepo;
    }

    /**
     * Evaluate and persist timing decisions for a list of inputs.
     *
     * @return all decisions (approved and blocked)
     */
    @Transactional
    public List<ExecutionTimingDecision> evaluateAll(List<TimingEvaluationInput> inputs,
                                                      LocalDate tradingDate) {
        List<ExecutionTimingDecision> decisions = timingEngine.evaluate(inputs);
        persistAll(decisions);
        long approved = decisions.stream().filter(ExecutionTimingDecision::approved).count();
        log.info("[ExecutionTiming] tradingDate={} total={} approved={}",
                tradingDate, decisions.size(), approved);
        return decisions;
    }

    /**
     * Evaluate and persist a single timing input.
     */
    @Transactional
    public ExecutionTimingDecision evaluateOne(TimingEvaluationInput input) {
        ExecutionTimingDecision d = timingEngine.evaluateOne(input);
        if (d != null) persist(d);
        return d;
    }

    /** Latest timing decision (any outcome) for today and the given symbol. */
    public Optional<ExecutionTimingDecision> getLatestForToday(String symbol) {
        return timingRepo.findTopByTradingDateAndSymbolOrderByIdDesc(LocalDate.now(), symbol)
                .map(this::toDecision);
    }

    /** Latest approved timing decision for today and the given symbol. */
    public Optional<ExecutionTimingDecision> getLatestApprovedForToday(String symbol) {
        return timingRepo.findTopByTradingDateAndSymbolAndApprovedTrueOrderByIdDesc(
                LocalDate.now(), symbol).map(this::toDecision);
    }

    /** All timing decisions for a given date. */
    public List<ExecutionTimingDecision> getByDate(LocalDate date) {
        return timingRepo.findByTradingDate(date).stream().map(this::toDecision).toList();
    }

    /** Approved timing decisions for a given date. */
    public List<ExecutionTimingDecision> getApprovedByDate(LocalDate date) {
        return timingRepo.findApprovedByTradingDate(date).stream().map(this::toDecision).toList();
    }

    // ── private helpers ────────────────────────────────────────────────────

    private void persistAll(List<ExecutionTimingDecision> decisions) {
        timingRepo.saveAll(decisions.stream().map(this::toEntity).toList());
    }

    private void persist(ExecutionTimingDecision d) {
        timingRepo.save(toEntity(d));
    }

    private ExecutionTimingDecisionEntity toEntity(ExecutionTimingDecision d) {
        ExecutionTimingDecisionEntity e = new ExecutionTimingDecisionEntity();
        e.setTradingDate(d.tradingDate());
        e.setSymbol(d.symbol());
        e.setSetupType(d.setupType());
        e.setApproved(d.approved());
        e.setTimingMode(d.timingMode());
        e.setUrgency(d.urgency());
        e.setStaleSignal(d.staleSignal());
        e.setDelayToleranceDays(d.delayToleranceDays());
        e.setSignalAgeDays(d.signalAgeDays());
        e.setRejectionReason(d.rejectionReason());
        e.setPayloadJson(d.payloadJson());
        return e;
    }

    private ExecutionTimingDecision toDecision(ExecutionTimingDecisionEntity e) {
        return new ExecutionTimingDecision(
                e.getTradingDate(), e.getSymbol(), e.getSetupType(),
                e.isApproved(), e.getTimingMode(), e.getUrgency(),
                e.isStaleSignal(), e.getDelayToleranceDays(), e.getSignalAgeDays(),
                e.getRejectionReason(), e.getPayloadJson()
        );
    }
}
