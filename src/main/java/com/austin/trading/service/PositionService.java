package com.austin.trading.service;

import com.austin.trading.dto.request.PositionCloseRequest;
import com.austin.trading.dto.request.PositionCreateRequest;
import com.austin.trading.dto.request.PositionPartialCloseRequest;
import com.austin.trading.dto.request.PositionUpdateRequest;
import com.austin.trading.dto.request.StopLossTakeProfitEvaluateRequest;
import com.austin.trading.dto.response.PositionResponse;
import com.austin.trading.engine.StopLossTakeProfitEngine;
import com.austin.trading.entity.PositionEntity;
import com.austin.trading.entity.PositionReviewLogEntity;
import com.austin.trading.repository.PositionRepository;
import com.austin.trading.repository.PositionReviewLogRepository;
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
    private final StopLossTakeProfitEngine stopLossTakeProfitEngine;
    // v3：所有現金變動透過 ledger service 記帳
    private final CapitalLedgerService ledgerService;
    private final CapitalService capitalService;
    /** v2.14：讀取最近一次 position_review_log 給 mobile 持倉風險顯示。 */
    private final PositionReviewLogRepository positionReviewLogRepository;

    public PositionService(PositionRepository positionRepository, PnlService pnlService,
                            TradeReviewService tradeReviewService, ScoreConfigService scoreConfigService,
                            StopLossTakeProfitEngine stopLossTakeProfitEngine,
                            CapitalLedgerService ledgerService,
                            CapitalService capitalService,
                            PositionReviewLogRepository positionReviewLogRepository) {
        this.positionRepository = positionRepository;
        this.pnlService = pnlService;
        this.tradeReviewService = tradeReviewService;
        this.scoreConfigService = scoreConfigService;
        this.stopLossTakeProfitEngine = stopLossTakeProfitEngine;
        this.ledgerService = ledgerService;
        this.capitalService = capitalService;
        this.positionReviewLogRepository = positionReviewLogRepository;
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

    /**
     * 新增持倉。
     * <p>v3：建倉同時寫 ledger — {@code BUY_OPEN(-cost)} + {@code FEE(-買方手續費)}。
     * 若可動用現金不足以支付 cost + fee，回 HTTP 409 {@code INSUFFICIENT_CASH}。</p>
     *
     * <p>SHORT（做空）部位目前不實際扣現金（券商模擬），
     * 為避免誤扣，暫只對 LONG 執行現金聯動；未來若納入保證金模型再擴充。</p>
     */
    @Transactional
    public PositionResponse create(PositionCreateRequest request) {
        // 重複持倉防護：同 symbol 不可有多個 OPEN position
        positionRepository.findTopBySymbolAndStatus(request.symbol(), "OPEN").ifPresent(existing -> {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "已有 OPEN 持倉: " + request.symbol() + " (id=" + existing.getId() + ")");
        });

        boolean    isLong = !isShortSide(request.side());
        BigDecimal cost   = request.avgCost().multiply(request.qty());
        BigDecimal fee    = isLong ? estimateFee(cost) : BigDecimal.ZERO;

        // 現金檢查（LONG）
        if (isLong) {
            BigDecimal required = cost.add(fee);
            BigDecimal avail    = capitalService.getAvailableCash();
            if (avail.compareTo(required) < 0) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "INSUFFICIENT_CASH: available=" + avail
                                + " required=" + required
                                + " (cost=" + cost + " fee=" + fee + ")");
            }
        }

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
        autofillMissingTargets(entity);
        PositionEntity saved = positionRepository.save(entity);

        // v3：寫入 ledger（LONG 才聯動現金）
        if (isLong) {
            LocalDate tradeDate = saved.getOpenedAt() != null
                    ? saved.getOpenedAt().toLocalDate() : LocalDate.now();
            ledgerService.recordBuyOpen(saved.getId(), saved.getSymbol(), cost, tradeDate,
                    "建倉 " + saved.getSymbol() + " " + saved.getQty() + " 股 @" + saved.getAvgCost());
            if (fee.signum() > 0) {
                ledgerService.recordFee(saved.getId(), saved.getSymbol(), fee, tradeDate,
                        "建倉手續費");
            }
        }
        return toResponse(saved);
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
     *
     * <p>v3：LONG 持倉同步寫 ledger — {@code SELL_CLOSE(+proceeds)} + {@code FEE(-賣方費)} + {@code TAX(-)}。
     * realizedPnl 仍寫回 position 作為歷史紀錄，但不再直接改現金（現金等於 proceeds − fee − tax）。</p>
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

        // v3：現金聯動（LONG）
        writeSellLedger(saved, saved.getQty(), request.closePrice(), LedgerSellKind.FULL);

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

        // v3：現金聯動（LONG 部分賣出，position_id 指向原持倉）
        // 用原持倉 id 便於追蹤，symbol 用 savedLeg 的（等同原 symbol）
        writeSellLedger(entity, sellQty, request.closePrice(), LedgerSellKind.PARTIAL);

        // 更新當日損益
        pnlService.recordClosedPosition(savedLeg, savedLeg.getRealizedPnl());

        // 分段出清不自動產生 trade review（避免多次重複），全清時才產生
        return toResponse(savedLeg);
    }

    // ── ledger integration helpers ──────────────────────────────────────

    private enum LedgerSellKind { FULL, PARTIAL }

    /**
     * 寫賣出相關 ledger：SELL_CLOSE / SELL_PARTIAL + FEE + TAX。
     * 僅對 LONG 部位聯動現金；SHORT 暫不處理（見 create() 註解）。
     */
    private void writeSellLedger(PositionEntity pos, BigDecimal sellQty, BigDecimal closePrice,
                                  LedgerSellKind kind) {
        if (isShortSide(pos.getSide())) return;
        if (sellQty == null || closePrice == null || sellQty.signum() <= 0) return;

        BigDecimal proceeds = closePrice.multiply(sellQty);
        BigDecimal fee      = estimateFee(proceeds);
        BigDecimal tax      = estimateTax(pos.getSymbol(), proceeds);
        LocalDate tradeDate = pos.getClosedAt() != null
                ? pos.getClosedAt().toLocalDate() : LocalDate.now();
        String note = (kind == LedgerSellKind.FULL ? "出清 " : "部分出清 ")
                + pos.getSymbol() + " " + sellQty + " 股 @" + closePrice;

        if (kind == LedgerSellKind.FULL) {
            ledgerService.recordSellClose(pos.getId(), pos.getSymbol(), proceeds, tradeDate, note);
        } else {
            ledgerService.recordSellPartial(pos.getId(), pos.getSymbol(), proceeds, tradeDate, note);
        }
        if (fee.signum() > 0) {
            ledgerService.recordFee(pos.getId(), pos.getSymbol(), fee, tradeDate,
                    (kind == LedgerSellKind.FULL ? "出清手續費" : "部分出清手續費"));
        }
        if (tax.signum() > 0) {
            ledgerService.recordTax(pos.getId(), pos.getSymbol(), tax, tradeDate,
                    (kind == LedgerSellKind.FULL ? "出清交易稅" : "部分出清交易稅"));
        }
    }

    private static boolean isShortSide(String side) {
        return "SHORT".equalsIgnoreCase(side) || "做空".equals(side);
    }

    /** 券商手續費：0.1425%，每邊最低 20 元。 */
    private static BigDecimal estimateFee(BigDecimal value) {
        if (value == null || value.signum() <= 0) return BigDecimal.ZERO;
        double fee = Math.max(20.0, value.doubleValue() * 0.001425);
        return BigDecimal.valueOf(fee).setScale(0, RoundingMode.HALF_UP);
    }

    /** 交易稅：ETF（代號開頭 0）0.1%，一般股 0.3%。 */
    private static BigDecimal estimateTax(String symbol, BigDecimal sellValue) {
        if (sellValue == null || sellValue.signum() <= 0) return BigDecimal.ZERO;
        boolean isEtf = symbol != null && symbol.startsWith("0");
        double tax = sellValue.doubleValue() * (isEtf ? 0.001 : 0.003);
        return BigDecimal.valueOf(tax).setScale(0, RoundingMode.HALF_UP);
    }

    private BigDecimal computePnl(PositionEntity entity, BigDecimal closePrice) {
        if (entity.getAvgCost() == null || entity.getQty() == null) return null;
        BigDecimal priceDiff = closePrice.subtract(entity.getAvgCost());
        if ("SHORT".equalsIgnoreCase(entity.getSide()) || "做空".equals(entity.getSide())) {
            priceDiff = priceDiff.negate();
        }
        return priceDiff.multiply(entity.getQty()).setScale(0, RoundingMode.HALF_UP);
    }

    private void autofillMissingTargets(PositionEntity entity) {
        if (entity.getAvgCost() == null || entity.getAvgCost().signum() <= 0) return;
        if (entity.getStopLossPrice() != null && entity.getTakeProfit1() != null && entity.getTakeProfit2() != null) return;

        boolean momentum = "MOMENTUM_CHASE".equalsIgnoreCase(entity.getStrategyType());
        BigDecimal stopLossPct = momentum
                ? scoreConfigService.getDecimal("momentum.stop_loss_pct", new BigDecimal("-0.025")).abs()
                : new BigDecimal("6.0");
        BigDecimal takeProfit1Pct = momentum
                ? scoreConfigService.getDecimal("momentum.take_profit_1_pct", new BigDecimal("0.06"))
                .multiply(new BigDecimal("100"))
                : new BigDecimal("8.0");
        BigDecimal takeProfit2Pct = momentum
                ? scoreConfigService.getDecimal("momentum.take_profit_2_pct", new BigDecimal("0.10"))
                .multiply(new BigDecimal("100"))
                : new BigDecimal("13.0");

        var suggestion = stopLossTakeProfitEngine.evaluate(new StopLossTakeProfitEvaluateRequest(
                entity.getAvgCost().doubleValue(),
                stopLossPct.doubleValue(),
                takeProfit1Pct.doubleValue(),
                takeProfit2Pct.doubleValue(),
                false
        ));

        if (entity.getStopLossPrice() == null) {
            entity.setStopLossPrice(BigDecimal.valueOf(suggestion.stopLossPrice()));
        }
        if (entity.getTakeProfit1() == null) {
            entity.setTakeProfit1(BigDecimal.valueOf(suggestion.takeProfit1()));
        }
        if (entity.getTakeProfit2() == null) {
            entity.setTakeProfit2(BigDecimal.valueOf(suggestion.takeProfit2()));
        }
        entity.setNote(appendSystemSuggestionNote(
                entity.getNote(),
                momentum ? "系統已依追價策略自動補上停損/停利"
                        : "系統已依一般策略自動補上停損/停利"));
    }

    private String appendSystemSuggestionNote(String note, String marker) {
        if (note == null || note.isBlank()) return marker;
        if (note.contains(marker)) return note;
        return note + "\n" + marker;
    }

    private PositionResponse toResponse(PositionEntity entity) {
        // v2.14：附帶最近一次 review_log 結果（STRONG / WEAKEN / EXIT），供 mobile 直接顯示。
        String reviewStatus = null;
        java.time.LocalDateTime reviewedAt = null;
        String reviewReason = null;
        if (entity.getId() != null && positionReviewLogRepository != null) {
            try {
                PositionReviewLogEntity rev = positionReviewLogRepository
                        .findTopByPositionIdOrderByCreatedAtDesc(entity.getId())
                        .orElse(null);
                if (rev != null) {
                    reviewStatus = rev.getDecisionStatus();
                    reviewReason = rev.getReason();
                    if (rev.getReviewDate() != null) {
                        java.time.LocalTime t = rev.getReviewTime() != null
                                ? rev.getReviewTime() : java.time.LocalTime.MIDNIGHT;
                        reviewedAt = java.time.LocalDateTime.of(rev.getReviewDate(), t);
                    } else if (rev.getCreatedAt() != null) {
                        reviewedAt = rev.getCreatedAt();
                    }
                }
            } catch (RuntimeException ignored) {
                // 讀 review_log 失敗不影響 position 主資料；保持 null。
            }
        }

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
                entity.getStrategyType(),
                reviewStatus,
                reviewedAt,
                reviewReason
        );
    }
}
