package com.austin.trading.service;

import com.austin.trading.dto.request.TradingStateUpsertRequest;
import com.austin.trading.dto.response.TradingStateResponse;
import com.austin.trading.entity.TradingStateEntity;
import com.austin.trading.repository.TradingStateRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class TradingStateService {

    private final TradingStateRepository tradingStateRepository;

    public TradingStateService(TradingStateRepository tradingStateRepository) {
        this.tradingStateRepository = tradingStateRepository;
    }

    public Optional<TradingStateResponse> getCurrentState() {
        return tradingStateRepository.findTopByOrderByTradingDateDescUpdatedAtDesc()
                .map(this::toResponse);
    }

    public List<TradingStateResponse> getHistory(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        return tradingStateRepository.findAllByOrderByTradingDateDescUpdatedAtDesc(PageRequest.of(0, safeLimit))
                .stream()
                .map(this::toResponse)
                .toList();
    }

    public TradingStateResponse create(TradingStateUpsertRequest request) {
        TradingStateEntity entity = new TradingStateEntity();
        entity.setTradingDate(request.tradingDate());
        entity.setMarketGrade(request.marketGrade());
        entity.setDecisionLock(request.decisionLock());
        entity.setTimeDecayStage(request.timeDecayStage());
        entity.setHourlyGate(request.hourlyGate());
        entity.setMonitorMode(request.monitorMode());
        entity.setPayloadJson(request.payloadJson());
        return toResponse(tradingStateRepository.save(entity));
    }

    private TradingStateResponse toResponse(TradingStateEntity entity) {
        return new TradingStateResponse(
                entity.getId(),
                entity.getTradingDate(),
                entity.getMarketGrade(),
                entity.getDecisionLock(),
                entity.getTimeDecayStage(),
                entity.getHourlyGate(),
                entity.getMonitorMode(),
                entity.getPayloadJson(),
                entity.getUpdatedAt()
        );
    }
}
