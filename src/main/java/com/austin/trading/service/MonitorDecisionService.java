package com.austin.trading.service;

import com.austin.trading.dto.internal.MarketRegimeDecision;
import com.austin.trading.dto.response.MonitorDecisionResponse;
import com.austin.trading.dto.response.MonitorDecisionRecordResponse;
import com.austin.trading.entity.MonitorDecisionEntity;
import com.austin.trading.repository.MonitorDecisionRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Persists {@link MonitorDecisionResponse}; also decorates
 * {@code payload_json} with the latest {@link MarketRegimeDecision} so that
 * 5-minute monitor history carries regime context for post-trade review.
 *
 * <p>Full routing migration ({@code monitorMode} derived from regime) is
 * scoped to P1.3 workflow rewire.</p>
 */
@Service
public class MonitorDecisionService {

    private static final Logger log = LoggerFactory.getLogger(MonitorDecisionService.class);

    private final MonitorDecisionRepository monitorDecisionRepository;
    private final MarketRegimeService       marketRegimeService;
    private final ObjectMapper              objectMapper;

    public MonitorDecisionService(MonitorDecisionRepository monitorDecisionRepository,
                                    MarketRegimeService marketRegimeService,
                                    ObjectMapper objectMapper) {
        this.monitorDecisionRepository = monitorDecisionRepository;
        this.marketRegimeService       = marketRegimeService;
        this.objectMapper              = objectMapper;
    }

    public void save(LocalDate tradingDate, LocalDateTime decisionTime, MonitorDecisionResponse response) {
        MonitorDecisionEntity entity = new MonitorDecisionEntity();
        entity.setTradingDate(tradingDate);
        entity.setDecisionTime(decisionTime);
        entity.setMonitorMode(response.monitorMode());
        entity.setShouldNotify(response.shouldNotify());
        entity.setTriggerEvent(response.triggerEvent());
        entity.setPayloadJson(toPayload(response));
        monitorDecisionRepository.save(entity);
    }

    public Optional<MonitorDecisionRecordResponse> getCurrent() {
        return monitorDecisionRepository.findTopByOrderByTradingDateDescDecisionTimeDescCreatedAtDesc()
                .map(this::toResponse);
    }

    public List<MonitorDecisionRecordResponse> getHistory(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        return monitorDecisionRepository.findAllByOrderByTradingDateDescDecisionTimeDescCreatedAtDesc(
                        PageRequest.of(0, safeLimit))
                .stream()
                .map(this::toResponse)
                .toList();
    }

    public List<MonitorDecisionRecordResponse> getHistoryByDate(LocalDate tradingDate, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        return monitorDecisionRepository.findAllByTradingDateOrderByDecisionTimeDescCreatedAtDesc(
                        tradingDate, PageRequest.of(0, safeLimit))
                .stream()
                .map(this::toResponse)
                .toList();
    }

    private String toPayload(MonitorDecisionResponse r) {
        ObjectNode obj = objectMapper.createObjectNode();
        obj.put("market_grade",     r.marketGrade());
        obj.put("market_phase",     r.marketPhase());
        obj.put("decision",         r.decision());
        obj.put("monitor_mode",     r.monitorMode());
        obj.put("should_notify",    r.shouldNotify());
        obj.put("trigger_event",    r.triggerEvent());
        obj.put("decision_lock",    r.decisionLock());
        obj.put("time_decay_stage", r.timeDecayStage());

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
            log.debug("[MonitorDecisionService] regime lookup failed (non-fatal): {}", e.getMessage());
        }

        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            log.warn("[MonitorDecisionService] payload serialization failed", e);
            return "{}";
        }
    }

    private MonitorDecisionRecordResponse toResponse(MonitorDecisionEntity entity) {
        return new MonitorDecisionRecordResponse(
                entity.getId(),
                entity.getTradingDate(),
                entity.getDecisionTime(),
                entity.getMonitorMode(),
                entity.getShouldNotify(),
                entity.getTriggerEvent(),
                entity.getPayloadJson(),
                entity.getCreatedAt()
        );
    }
}
