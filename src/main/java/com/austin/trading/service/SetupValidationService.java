package com.austin.trading.service;

import com.austin.trading.dto.internal.MarketRegimeDecision;
import com.austin.trading.dto.internal.SetupDecision;
import com.austin.trading.dto.internal.SetupEvaluationInput;
import com.austin.trading.engine.SetupEngine;
import com.austin.trading.entity.SetupDecisionEntity;
import com.austin.trading.repository.SetupDecisionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * P0.3 Setup Layer.
 *
 * <p>Calls {@link SetupEngine} for each input, persists the decision to
 * {@code setup_decision_log}, and returns the full result list.  Invalid
 * decisions are persisted with their rejection reason for attribution.</p>
 *
 * <p>In P0.3 the price-structure fields in {@link SetupEvaluationInput} must be
 * supplied by the caller (postmarket data prep pipeline or manual test inputs).
 * A live intraday feed integration is in scope for P1.3 workflow rewire.</p>
 */
@Service
public class SetupValidationService {

    private static final Logger log = LoggerFactory.getLogger(SetupValidationService.class);

    private final SetupEngine             setupEngine;
    private final SetupDecisionRepository setupRepo;

    public SetupValidationService(SetupEngine setupEngine,
                                  SetupDecisionRepository setupRepo) {
        this.setupEngine = setupEngine;
        this.setupRepo   = setupRepo;
    }

    /**
     * Evaluate and persist a list of setup inputs.
     *
     * @return all decisions (valid and invalid), persisted to DB
     */
    @Transactional
    public List<SetupDecision> evaluateAll(List<SetupEvaluationInput> inputs) {
        List<SetupDecision> decisions = setupEngine.evaluate(inputs);
        persistAll(decisions);
        long valid = decisions.stream().filter(SetupDecision::valid).count();
        log.info("[SetupValidation] total={} valid={}", decisions.size(), valid);
        return decisions;
    }

    /**
     * Evaluate and persist a single setup input.
     */
    @Transactional
    public SetupDecision evaluateOne(SetupEvaluationInput input) {
        SetupDecision d = setupEngine.evaluateOne(input);
        if (d != null) persist(d);
        return d;
    }

    /** Latest setup decision (any validity) for today and the given symbol. */
    public Optional<SetupDecision> getLatestForToday(String symbol) {
        return setupRepo.findTopByTradingDateAndSymbolOrderByIdDesc(LocalDate.now(), symbol)
                .map(this::toDecision);
    }

    /** Latest valid setup for today and the given symbol. */
    public Optional<SetupDecision> getLatestValidForToday(String symbol) {
        return setupRepo.findTopByTradingDateAndSymbolAndValidTrueOrderByIdDesc(LocalDate.now(), symbol)
                .map(this::toDecision);
    }

    /** All setup decisions for a given date. */
    public List<SetupDecision> getByDate(LocalDate date) {
        return setupRepo.findByTradingDate(date).stream()
                .map(this::toDecision).toList();
    }

    /** Valid setups only for a given date. */
    public List<SetupDecision> getValidByDate(LocalDate date) {
        return setupRepo.findValidByTradingDate(date).stream()
                .map(this::toDecision).toList();
    }

    // ── private helpers ────────────────────────────────────────────────────

    private void persistAll(List<SetupDecision> decisions) {
        setupRepo.saveAll(decisions.stream().map(this::toEntity).toList());
    }

    private void persist(SetupDecision d) {
        setupRepo.save(toEntity(d));
    }

    private SetupDecisionEntity toEntity(SetupDecision d) {
        SetupDecisionEntity e = new SetupDecisionEntity();
        e.setTradingDate(d.tradingDate());
        e.setSymbol(d.symbol());
        e.setSetupType(d.setupType());
        e.setValid(d.valid());
        e.setEntryZoneLow(d.entryZoneLow());
        e.setEntryZoneHigh(d.entryZoneHigh());
        e.setIdealEntryPrice(d.idealEntryPrice());
        e.setInvalidationPrice(d.invalidationPrice());
        e.setInitialStopPrice(d.initialStopPrice());
        e.setTakeProfit1Price(d.takeProfit1Price());
        e.setTakeProfit2Price(d.takeProfit2Price());
        e.setTrailingMode(d.trailingMode());
        e.setHoldingWindowDays(d.holdingWindowDays());
        e.setRejectionReason(d.rejectionReason());
        e.setPayloadJson(d.payloadJson());
        return e;
    }

    private SetupDecision toDecision(SetupDecisionEntity e) {
        return new SetupDecision(
                e.getTradingDate(), e.getSymbol(), e.getSetupType(), e.isValid(),
                e.getEntryZoneLow(), e.getEntryZoneHigh(), e.getIdealEntryPrice(),
                e.getInvalidationPrice(), e.getInitialStopPrice(),
                e.getTakeProfit1Price(), e.getTakeProfit2Price(),
                e.getTrailingMode(), e.getHoldingWindowDays(),
                e.getRejectionReason(), e.getPayloadJson()
        );
    }
}
