package com.austin.trading.service;

import com.austin.trading.dto.internal.MarketRegimeDecision;
import com.austin.trading.dto.response.HourlyGateDecisionResponse;
import com.austin.trading.dto.response.HourlyGateDecisionRecordResponse;
import com.austin.trading.entity.HourlyGateDecisionEntity;
import com.austin.trading.repository.HourlyGateDecisionRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

/**
 * Persists {@link HourlyGateDecisionResponse} and decorates the stored
 * {@code payload_json} with the latest {@link MarketRegimeDecision} regime
 * fields.
 *
 * <p>Currently the hourly engine itself still routes on legacy A/B/C grade —
 * regime consumption happens purely as metadata (so that logs + reviews can
 * trace which regime was in force at gate time). <b>Real routing migration is
 * scoped to P1.3 workflow rewire</b>; this service only closes the
 * logging/audit gap so the Regime chunk has real downstream visibility.</p>
 */
@Service
public class HourlyGateDecisionService {

    private static final Logger log = LoggerFactory.getLogger(HourlyGateDecisionService.class);

    private final HourlyGateDecisionRepository hourlyGateDecisionRepository;
    private final MarketRegimeService          marketRegimeService;
    private final ObjectMapper                 objectMapper;

    public HourlyGateDecisionService(HourlyGateDecisionRepository hourlyGateDecisionRepository,
                                      MarketRegimeService marketRegimeService,
                                      ObjectMapper objectMapper) {
        this.hourlyGateDecisionRepository = hourlyGateDecisionRepository;
        this.marketRegimeService          = marketRegimeService;
        this.objectMapper                 = objectMapper;
    }

    public void save(LocalDate tradingDate, LocalTime gateTime, HourlyGateDecisionResponse response) {
        HourlyGateDecisionEntity entity = new HourlyGateDecisionEntity();
        entity.setTradingDate(tradingDate);
        entity.setGateTime(gateTime);
        entity.setHourlyGate(response.hourlyGate());
        entity.setShouldRun5mMonitor(response.shouldRun5mMonitor());
        entity.setTriggerEvent(response.triggerEvent());
        entity.setPayloadJson(toPayload(response));
        hourlyGateDecisionRepository.save(entity);
    }

    public Optional<HourlyGateDecisionRecordResponse> getCurrent() {
        return hourlyGateDecisionRepository.findTopByOrderByTradingDateDescGateTimeDescCreatedAtDesc()
                .map(this::toResponse);
    }

    public List<HourlyGateDecisionRecordResponse> getHistory(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        return hourlyGateDecisionRepository.findAllByOrderByTradingDateDescGateTimeDescCreatedAtDesc(
                        PageRequest.of(0, safeLimit))
                .stream()
                .map(this::toResponse)
                .toList();
    }

    private String toPayload(HourlyGateDecisionResponse r) {
        ObjectNode obj = objectMapper.createObjectNode();
        obj.put("market_grade",     r.marketGrade());
        obj.put("market_phase",     r.marketPhase());
        obj.put("decision",         r.decision());
        obj.put("hourly_gate",      r.hourlyGate());
        obj.put("should_notify",    r.shouldNotify());
        obj.put("trigger_event",    r.triggerEvent());
        obj.put("decision_lock",    r.decisionLock());
        obj.put("time_decay_stage", r.timeDecayStage());

        // v3: log latest regime decision alongside hourly output. Hourly engine
        // itself has not yet been migrated to consume regime (P1.3 workflow
        // rewire), but persisting regimeType here gives reviewers traceability.
        try {
            Optional<MarketRegimeDecision> regimeOpt = marketRegimeService.getLatestForToday();
            if (regimeOpt.isPresent()) {
                MarketRegimeDecision regime = regimeOpt.get();
                ObjectNode regimeNode = obj.putObject("regime");
                regimeNode.put("regime_type",     regime.regimeType());
                regimeNode.put("trade_allowed",   regime.tradeAllowed());
                regimeNode.put("risk_multiplier", regime.riskMultiplier());
                regimeNode.put("decision_id",     regime.id());
            }
        } catch (Exception e) {
            log.debug("[HourlyGateDecisionService] regime lookup failed (non-fatal): {}", e.getMessage());
        }

        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            log.warn("[HourlyGateDecisionService] payload serialization failed", e);
            return "{}";
        }
    }

    private HourlyGateDecisionRecordResponse toResponse(HourlyGateDecisionEntity entity) {
        return new HourlyGateDecisionRecordResponse(
                entity.getId(),
                entity.getTradingDate(),
                entity.getGateTime(),
                entity.getHourlyGate(),
                entity.getShouldRun5mMonitor(),
                entity.getTriggerEvent(),
                entity.getPayloadJson(),
                entity.getCreatedAt()
        );
    }
}
