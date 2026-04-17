package com.austin.trading.service;

import com.austin.trading.dto.request.DailyPnlCreateRequest;
import com.austin.trading.dto.response.DailyPnlResponse;
import com.austin.trading.dto.response.PnlSummaryResponse;
import com.austin.trading.entity.DailyPnlEntity;
import com.austin.trading.repository.DailyPnlRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Service
public class PnlService {

    private final DailyPnlRepository dailyPnlRepository;

    public PnlService(DailyPnlRepository dailyPnlRepository) {
        this.dailyPnlRepository = dailyPnlRepository;
    }

    public Optional<DailyPnlResponse> getLatestDaily() {
        return dailyPnlRepository.findTopByOrderByTradingDateDescCreatedAtDesc().map(this::toResponse);
    }

    public List<DailyPnlResponse> getDailyHistory(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 500));
        return dailyPnlRepository.findAllByOrderByTradingDateDescCreatedAtDesc(PageRequest.of(0, safeLimit))
                .stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * 依日期區間查詢損益歷史，支援分頁。
     */
    public List<DailyPnlResponse> getDailyHistoryByRange(
            LocalDate dateFrom,
            LocalDate dateTo,
            int page,
            int size
    ) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, 500));
        return dailyPnlRepository.findByDateRange(dateFrom, dateTo, PageRequest.of(safePage, safeSize))
                .stream()
                .map(this::toResponse)
                .toList();
    }

    public DailyPnlResponse create(DailyPnlCreateRequest request) {
        DailyPnlEntity entity = new DailyPnlEntity();
        entity.setTradingDate(request.tradingDate());
        entity.setRealizedPnl(request.realizedPnl());
        entity.setUnrealizedPnl(request.unrealizedPnl());
        entity.setWinRate(request.winRate());
        entity.setPayloadJson(request.payloadJson());
        return toResponse(dailyPnlRepository.save(entity));
    }

    public PnlSummaryResponse getSummary(int days) {
        int safeDays = Math.max(1, Math.min(days, 365));
        List<DailyPnlResponse> rows = getDailyHistory(safeDays);

        BigDecimal totalRealized = rows.stream()
                .map(r -> r.realizedPnl() == null ? BigDecimal.ZERO : r.realizedPnl())
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalUnrealized = rows.stream()
                .map(r -> r.unrealizedPnl() == null ? BigDecimal.ZERO : r.unrealizedPnl())
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal avgWinRate = rows.isEmpty()
                ? BigDecimal.ZERO
                : rows.stream()
                .map(r -> r.winRate() == null ? BigDecimal.ZERO : r.winRate())
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(rows.size()), 4, RoundingMode.HALF_UP);

        return new PnlSummaryResponse(totalRealized, totalUnrealized, avgWinRate, rows.size());
    }

    private DailyPnlResponse toResponse(DailyPnlEntity entity) {
        return new DailyPnlResponse(
                entity.getId(),
                entity.getTradingDate(),
                entity.getRealizedPnl(),
                entity.getUnrealizedPnl(),
                entity.getWinRate(),
                entity.getPayloadJson(),
                entity.getCreatedAt()
        );
    }
}
