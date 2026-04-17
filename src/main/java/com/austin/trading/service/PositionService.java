package com.austin.trading.service;

import com.austin.trading.dto.request.PositionCloseRequest;
import com.austin.trading.dto.request.PositionCreateRequest;
import com.austin.trading.dto.response.PositionResponse;
import com.austin.trading.entity.PositionEntity;
import com.austin.trading.repository.PositionRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

@Service
public class PositionService {

    private final PositionRepository positionRepository;

    public PositionService(PositionRepository positionRepository) {
        this.positionRepository = positionRepository;
    }

    public List<PositionResponse> getOpenPositions(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        return positionRepository.findByStatusOrderByCreatedAtDesc("OPEN", PageRequest.of(0, safeLimit))
                .stream().map(this::toResponse).toList();
    }

    public List<PositionResponse> getOpenPositionsFiltered(String symbol, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, 200));
        String sym = (symbol == null || symbol.isBlank()) ? null : symbol.trim();
        return positionRepository.findOpenByFilter("OPEN", sym,
                        PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "createdAt")))
                .stream().map(this::toResponse).toList();
    }

    public List<PositionResponse> getHistory(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 500));
        return positionRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, safeLimit))
                .stream().map(this::toResponse).toList();
    }

    public List<PositionResponse> getHistoryFiltered(
            String symbol, LocalDate dateFrom, LocalDate dateTo, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, 500));
        String sym = (symbol == null || symbol.isBlank()) ? null : symbol.trim();
        LocalDateTime from = dateFrom == null ? null : dateFrom.atStartOfDay();
        LocalDateTime to   = dateTo   == null ? null : dateTo.atTime(LocalTime.MAX);
        return positionRepository.findHistoryByFilter(sym, from, to, PageRequest.of(safePage, safeSize))
                .stream().map(this::toResponse).toList();
    }

    public PositionResponse create(PositionCreateRequest request) {
        PositionEntity entity = new PositionEntity();
        entity.setSymbol(request.symbol());
        entity.setSide(request.side());
        entity.setQty(request.qty());
        entity.setAvgCost(request.avgCost());
        entity.setStatus(request.status());
        entity.setOpenedAt(request.openedAt() == null ? LocalDateTime.now() : request.openedAt());
        entity.setClosedAt(request.closedAt());
        entity.setPayloadJson(request.payloadJson());
        return toResponse(positionRepository.save(entity));
    }

    /**
     * 關閉持倉：設定 closedAt、closePrice、realizedPnl，狀態改為 CLOSED。
     * realizedPnl = (closePrice - avgCost) * qty（LONG），SHORT 反向計算。
     */
    public PositionResponse close(Long id, PositionCloseRequest request) {
        PositionEntity entity = positionRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Position not found: " + id));

        if ("CLOSED".equalsIgnoreCase(entity.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Position already closed: " + id);
        }

        entity.setStatus("CLOSED");
        entity.setClosePrice(request.closePrice());
        entity.setClosedAt(request.closedAt() == null ? LocalDateTime.now() : request.closedAt());
        entity.setRealizedPnl(computePnl(entity, request.closePrice()));

        return toResponse(positionRepository.save(entity));
    }

    private BigDecimal computePnl(PositionEntity entity, BigDecimal closePrice) {
        if (entity.getAvgCost() == null || entity.getQty() == null) return null;
        BigDecimal priceDiff = closePrice.subtract(entity.getAvgCost());
        if ("SHORT".equalsIgnoreCase(entity.getSide())) {
            priceDiff = priceDiff.negate();
        }
        return priceDiff.multiply(entity.getQty()).setScale(4, RoundingMode.HALF_UP);
    }

    private PositionResponse toResponse(PositionEntity entity) {
        return new PositionResponse(
                entity.getId(),
                entity.getSymbol(),
                entity.getSide(),
                entity.getQty(),
                entity.getAvgCost(),
                entity.getStatus(),
                entity.getOpenedAt(),
                entity.getClosedAt(),
                entity.getClosePrice(),
                entity.getRealizedPnl(),
                entity.getPayloadJson(),
                entity.getCreatedAt()
        );
    }
}
