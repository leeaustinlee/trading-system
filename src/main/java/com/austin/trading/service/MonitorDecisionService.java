package com.austin.trading.service;

import com.austin.trading.dto.response.MonitorDecisionResponse;
import com.austin.trading.dto.response.MonitorDecisionRecordResponse;
import com.austin.trading.entity.MonitorDecisionEntity;
import com.austin.trading.repository.MonitorDecisionRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class MonitorDecisionService {

    private final MonitorDecisionRepository monitorDecisionRepository;

    public MonitorDecisionService(MonitorDecisionRepository monitorDecisionRepository) {
        this.monitorDecisionRepository = monitorDecisionRepository;
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

    private String toPayload(MonitorDecisionResponse r) {
        return "{" +
                "\"market_grade\":\"" + r.marketGrade() + "\"," +
                "\"market_phase\":\"" + r.marketPhase() + "\"," +
                "\"decision\":\"" + r.decision() + "\"," +
                "\"monitor_mode\":\"" + r.monitorMode() + "\"," +
                "\"should_notify\":" + r.shouldNotify() + "," +
                "\"trigger_event\":\"" + r.triggerEvent() + "\"," +
                "\"decision_lock\":\"" + r.decisionLock() + "\"," +
                "\"time_decay_stage\":\"" + r.timeDecayStage() + "\"" +
                "}";
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
