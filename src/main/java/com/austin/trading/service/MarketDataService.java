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

    /**
     * 今日優先查詢：
     * 1) 先查 trading_date = today 的最新一筆；
     * 2) 找不到才退回最近一筆，並標示 stale=true + reason=NO_TODAY_DATA。
     */
    public Optional<MarketCurrentResponse> getMarketPreferToday() {
        LocalDate today = LocalDate.now();
        Optional<MarketSnapshotEntity> todayOpt =
                marketSnapshotRepository.findTopByTradingDateOrderByCreatedAtDesc(today);
        if (todayOpt.isPresent()) {
            return todayOpt.map(this::toResponse);
        }
        return marketSnapshotRepository.findTopByOrderByTradingDateDescCreatedAtDesc()
                .map(e -> toStaleResponse(e, "NO_TODAY_DATA"));
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
                entity.getCreatedAt(),
                false,
                null
        );
    }

    private MarketCurrentResponse toStaleResponse(MarketSnapshotEntity entity, String reason) {
        return new MarketCurrentResponse(
                entity.getId(),
                entity.getTradingDate(),
                entity.getMarketGrade(),
                entity.getMarketPhase(),
                entity.getDecision(),
                entity.getPayloadJson(),
                entity.getCreatedAt(),
                true,
                reason
        );
    }
}
