package com.austin.trading.service;

import com.austin.trading.dto.response.HourlyGateDecisionResponse;
import com.austin.trading.dto.response.HourlyGateDecisionRecordResponse;
import com.austin.trading.entity.HourlyGateDecisionEntity;
import com.austin.trading.repository.HourlyGateDecisionRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

@Service
public class HourlyGateDecisionService {

    private final HourlyGateDecisionRepository hourlyGateDecisionRepository;

    public HourlyGateDecisionService(HourlyGateDecisionRepository hourlyGateDecisionRepository) {
        this.hourlyGateDecisionRepository = hourlyGateDecisionRepository;
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

    private String toPayload(HourlyGateDecisionResponse result) {
        return "{" +
                "\"market_grade\":\"" + result.marketGrade() + "\"," +
                "\"market_phase\":\"" + result.marketPhase() + "\"," +
                "\"decision\":\"" + result.decision() + "\"," +
                "\"hourly_gate\":\"" + result.hourlyGate() + "\"," +
                "\"should_notify\":" + result.shouldNotify() + "," +
                "\"trigger_event\":\"" + result.triggerEvent() + "\"," +
                "\"decision_lock\":\"" + result.decisionLock() + "\"," +
                "\"time_decay_stage\":\"" + result.timeDecayStage() + "\"" +
                "}";
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
