package com.austin.trading.service;

import com.austin.trading.dto.request.MarketSnapshotCreateRequest;
import com.austin.trading.dto.response.MarketCurrentResponse;
import com.austin.trading.entity.MarketSnapshotEntity;
import com.austin.trading.repository.MarketSnapshotRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Service
public class MarketDataService {

    private final MarketSnapshotRepository marketSnapshotRepository;

    public MarketDataService(MarketSnapshotRepository marketSnapshotRepository) {
        this.marketSnapshotRepository = marketSnapshotRepository;
    }

    public Optional<MarketCurrentResponse> getCurrentMarket() {
        return marketSnapshotRepository.findTopByOrderByTradingDateDescCreatedAtDesc()
                .map(this::toResponse);
    }

    public List<MarketCurrentResponse> getMarketHistory(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        return marketSnapshotRepository.findAllByOrderByTradingDateDescCreatedAtDesc(PageRequest.of(0, safeLimit))
                .stream()
                .map(this::toResponse)
                .toList();
    }

    public MarketCurrentResponse createSnapshot(MarketSnapshotCreateRequest req) {
        MarketSnapshotEntity entity = new MarketSnapshotEntity();
        entity.setTradingDate(req.tradingDate() != null ? req.tradingDate() : LocalDate.now());
        entity.setMarketGrade(req.marketGrade());
        entity.setMarketPhase(req.marketPhase());
        entity.setDecision(req.decision());
        entity.setPayloadJson(req.payloadJson());
        return toResponse(marketSnapshotRepository.save(entity));
    }

    private MarketCurrentResponse toResponse(MarketSnapshotEntity entity) {
        return new MarketCurrentResponse(
                entity.getId(),
                entity.getTradingDate(),
                entity.getMarketGrade(),
                entity.getMarketPhase(),
                entity.getDecision(),
                entity.getPayloadJson(),
                entity.getCreatedAt()
        );
    }
}
