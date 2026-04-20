package com.austin.trading.service;

import com.austin.trading.dto.request.PositionCloseRequest;
import com.austin.trading.dto.request.PositionCreateRequest;
import com.austin.trading.dto.request.PositionPartialCloseRequest;
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
    private final TradeReviewService tradeReviewService;
    private final ScoreConfigService scoreConfigService;

    public PositionService(PositionRepository positionRepository, PnlService pnlService,
                            TradeReviewService tradeReviewService, ScoreConfigService scoreConfigService) {
        this.positionRepository = positionRepository;
        this.pnlService = pnlService;
        this.tradeReviewService = tradeReviewService;
        this.scoreConfigService = scoreConfigService;
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
        // v2.3: strategy_type 預設 SETUP；前端/API 可覆寫為 MOMENTUM_CHASE
        entity.setStrategyType(request.strategyType() == null ? "SETUP" : request.strategyType());

        // v2.3: Momentum 建倉若未指定停損，以 momentum.stop_loss_pct 自動補上
        if ("MOMENTUM_CHASE".equalsIgnoreCase(entity.getStrategyType())
                && entity.getStopLossPrice() == null
                && entity.getAvgCost() != null) {
            BigDecimal stopPct = scoreConfigService.getDecimal(
                    "momentum.stop_loss_pct", new BigDecimal("-0.025"));
            BigDecimal stop = entity.getAvgCost()
                    .multiply(BigDecimal.ONE.add(stopPct))
                    .setScale(4, RoundingMode.HALF_UP);
            entity.setStopLossPrice(stop);
        }
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

        // 自動產生 trade review（若啟用）
        if (scoreConfigService.getBoolean("review.auto_on_close", true)) {
            tradeReviewService.generateForPosition(saved);
        }

        return toResponse(saved);
    }

    /**
     * 分段出清：指定 qty + closePrice，將此次出清部分拆成獨立 CLOSED 紀錄，
     * 原持倉扣掉對應張數繼續保持 OPEN。若 qty >= 剩餘張數，等同全部出清。
     */
    @Transactional
    public PositionResponse partialClose(Long id, PositionPartialCloseRequest request) {
        PositionEntity entity = positionRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Position not found: " + id));

        if ("CLOSED".equalsIgnoreCase(entity.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Position already closed: " + id);
        }
        if (entity.getQty() == null || entity.getQty().signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Position qty invalid: " + id);
        }
        if (request.qty().signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "qty must be positive");
        }

        BigDecimal sellQty = request.qty();
        BigDecimal remaining = entity.getQty();

        // 若一次賣完，等同全部出清
        if (sellQty.compareTo(remaining) >= 0) {
            return close(id, new PositionCloseRequest(
                    request.closePrice(), request.closedAt(), request.exitReason(), request.note()));
        }

        LocalDateTime closedAt = request.closedAt() == null ? LocalDateTime.now() : request.closedAt();

        // 建立新 CLOSED 子紀錄：copy 原欄位 + 本次賣量/價
        PositionEntity closedLeg = new PositionEntity();
        closedLeg.setSymbol(entity.getSymbol());
        closedLeg.setStockName(entity.getStockName());
        closedLeg.setSide(entity.getSide());
        closedLeg.setQty(sellQty);
        closedLeg.setAvgCost(entity.getAvgCost());
        closedLeg.setStatus("CLOSED");
        closedLeg.setOpenedAt(entity.getOpenedAt());
        closedLeg.setClosePrice(request.closePrice());
        closedLeg.setClosedAt(closedAt);
        closedLeg.setExitReason(request.exitReason());
        closedLeg.setRealizedPnl(computePnl(closedLeg, request.closePrice()));
        closedLeg.setPayloadJson(entity.getPayloadJson());
        String parentNote = "分段出清 from #" + entity.getId();
        closedLeg.setNote(request.note() == null || request.note().isBlank()
                ? parentNote : parentNote + " / " + request.note());
        PositionEntity savedLeg = positionRepository.save(closedLeg);

        // 原持倉扣張數
        entity.setQty(remaining.subtract(sellQty));
        positionRepository.save(entity);

        // 更新當日損益
        pnlService.recordClosedPosition(savedLeg, savedLeg.getRealizedPnl());

        // 分段出清不自動產生 trade review（避免多次重複），全清時才產生
        return toResponse(savedLeg);
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
                entity.getCreatedAt(),
                entity.getStrategyType()
        );
    }
}
