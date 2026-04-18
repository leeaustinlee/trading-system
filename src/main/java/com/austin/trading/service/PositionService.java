package com.austin.trading.service;

import com.austin.trading.dto.request.PositionCloseRequest;
import com.austin.trading.dto.request.PositionCreateRequest;
import com.austin.trading.dto.request.PositionUpdateRequest;
import com.austin.trading.dto.response.PositionResponse;
import com.austin.trading.entity.PositionEntity;
import com.austin.trading.repository.PositionRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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
    private final PnlService pnlService;

    public PositionService(PositionRepository positionRepository, PnlService pnlService) {
        this.positionRepository = positionRepository;
        this.pnlService = pnlService;
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
        // 重複持倉防護：同 symbol 不可有多個 OPEN position
        positionRepository.findTopBySymbolAndStatus(request.symbol(), "OPEN").ifPresent(existing -> {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "已有 OPEN 持倉: " + request.symbol() + " (id=" + existing.getId() + ")");
        });

        PositionEntity entity = new PositionEntity();
        entity.setSymbol(request.symbol());
        entity.setStockName(request.stockName());
        entity.setSide(request.side());
        entity.setQty(request.qty());
        entity.setAvgCost(request.avgCost());
        entity.setStatus("OPEN");
        entity.setStopLossPrice(request.stopLossPrice());
        entity.setTakeProfit1(request.takeProfit1());
        entity.setTakeProfit2(request.takeProfit2());
        entity.setOpenedAt(request.openedAt() == null ? LocalDateTime.now() : request.openedAt());
        entity.setNote(request.note());
        entity.setPayloadJson(request.payloadJson());
        return toResponse(positionRepository.save(entity));
    }

    /**
     * 更新持倉（停損停利/加減碼/備註）。
     * 只更新 request 中非 null 的欄位。
     */
    public PositionResponse update(Long id, PositionUpdateRequest request) {
        PositionEntity entity = positionRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Position not found: " + id));

        if ("CLOSED".equalsIgnoreCase(entity.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cannot update a closed position: " + id);
        }

        if (request.qty()            != null) entity.setQty(request.qty());
        if (request.avgCost()        != null) entity.setAvgCost(request.avgCost());
        if (request.stopLossPrice()  != null) entity.setStopLossPrice(request.stopLossPrice());
        if (request.takeProfit1()    != null) entity.setTakeProfit1(request.takeProfit1());
        if (request.takeProfit2()    != null) entity.setTakeProfit2(request.takeProfit2());
        if (request.note()           != null) entity.setNote(request.note());

        return toResponse(positionRepository.save(entity));
    }

    /**
     * 出清持倉：設定 closedAt、closePrice、exitReason、realizedPnl，狀態改為 CLOSED。
     * realizedPnl = (closePrice - avgCost) × qty（LONG），SHORT 反向計算。
     * 出清後自動更新當日 DailyPnl 記錄。
     */
    @Transactional
    public PositionResponse close(Long id, PositionCloseRequest request) {
        PositionEntity entity = positionRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Position not found: " + id));

        if ("CLOSED".equalsIgnoreCase(entity.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Position already closed: " + id);
        }

        entity.setStatus("CLOSED");
        entity.setClosePrice(request.closePrice());
        entity.setClosedAt(request.closedAt() == null ? LocalDateTime.now() : request.closedAt());
        entity.setExitReason(request.exitReason());
        if (request.note() != null) entity.setNote(request.note());
        entity.setRealizedPnl(computePnl(entity, request.closePrice()));

        PositionEntity saved = positionRepository.save(entity);

        // 自動更新當日損益
        pnlService.recordClosedPosition(saved, saved.getRealizedPnl());

        return toResponse(saved);
    }

    private BigDecimal computePnl(PositionEntity entity, BigDecimal closePrice) {
        if (entity.getAvgCost() == null || entity.getQty() == null) return null;
        BigDecimal priceDiff = closePrice.subtract(entity.getAvgCost());
        if ("SHORT".equalsIgnoreCase(entity.getSide()) || "做空".equals(entity.getSide())) {
            priceDiff = priceDiff.negate();
        }
        return priceDiff.multiply(entity.getQty()).setScale(0, RoundingMode.HALF_UP);
    }

    private PositionResponse toResponse(PositionEntity entity) {
        return new PositionResponse(
                entity.getId(),
                entity.getSymbol(),
                entity.getStockName(),
                entity.getSide(),
                entity.getQty(),
                entity.getAvgCost(),
                entity.getStatus(),
                entity.getStopLossPrice(),
                entity.getTakeProfit1(),
                entity.getTakeProfit2(),
                entity.getOpenedAt(),
                entity.getClosedAt(),
                entity.getClosePrice(),
                entity.getExitReason(),
                entity.getRealizedPnl(),
                entity.getNote(),
                entity.getPayloadJson(),
                entity.getCreatedAt()
        );
    }
}
