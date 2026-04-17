package com.austin.trading.service;

import com.austin.trading.dto.request.DailyPnlCreateRequest;
import com.austin.trading.dto.request.DailyPnlUpdateRequest;
import com.austin.trading.dto.response.DailyPnlResponse;
import com.austin.trading.dto.response.PnlSummaryResponse;
import com.austin.trading.entity.DailyPnlEntity;
import com.austin.trading.entity.PositionEntity;
import com.austin.trading.repository.DailyPnlRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

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

    public List<DailyPnlResponse> getDailyHistoryByRange(
            LocalDate dateFrom, LocalDate dateTo, int page, int size) {
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
        entity.setGrossPnl(request.grossPnl() != null ? request.grossPnl() : request.realizedPnl());
        entity.setEstimatedFeeAndTax(request.estimatedFeeAndTax());
        if (request.netPnl() != null) {
            entity.setNetPnl(request.netPnl());
        } else if (entity.getGrossPnl() != null && entity.getEstimatedFeeAndTax() != null) {
            entity.setNetPnl(entity.getGrossPnl().subtract(entity.getEstimatedFeeAndTax()));
        }
        entity.setTradeCount(request.tradeCount());
        entity.setWinCount(request.winCount());
        entity.setLossCount(request.lossCount());
        entity.setNotes(request.notes());
        entity.setRealizedPnl(entity.getGrossPnl());
        entity.setUnrealizedPnl(request.unrealizedPnl());
        entity.setWinRate(request.winRate());
        entity.setPayloadJson(request.payloadJson());
        return toResponse(dailyPnlRepository.save(entity));
    }

    /**
     * 持倉出清後自動更新當日損益（upsert）。
     * 費稅估算公式：
     *   手續費 = max(20, 成交金額 × 0.1425%) × 2（買+賣）
     *   交易稅 = 賣出金額 × 0.1%（ETF，代號以 0 開頭）或 0.3%（一般股）
     */
    @Transactional
    public DailyPnlResponse recordClosedPosition(PositionEntity pos, BigDecimal realizedPnl) {
        LocalDate tradingDate = pos.getClosedAt() != null
                ? pos.getClosedAt().toLocalDate() : LocalDate.now();

        DailyPnlEntity entity = dailyPnlRepository.findByTradingDate(tradingDate)
                .orElseGet(() -> {
                    DailyPnlEntity e = new DailyPnlEntity();
                    e.setTradingDate(tradingDate);
                    e.setTradeCount(0);
                    e.setWinCount(0);
                    e.setLossCount(0);
                    return e;
                });

        BigDecimal pnl = realizedPnl != null ? realizedPnl : BigDecimal.ZERO;

        // 毛損益累計
        BigDecimal gross = entity.getGrossPnl() != null ? entity.getGrossPnl() : BigDecimal.ZERO;
        entity.setGrossPnl(gross.add(pnl));

        // 費稅估算累計
        BigDecimal feeTax = estimateFeeTax(pos);
        BigDecimal existingFee = entity.getEstimatedFeeAndTax() != null
                ? entity.getEstimatedFeeAndTax() : BigDecimal.ZERO;
        entity.setEstimatedFeeAndTax(existingFee.add(feeTax));

        // 淨損益
        entity.setNetPnl(entity.getGrossPnl().subtract(entity.getEstimatedFeeAndTax()));

        // 交易筆數
        entity.setTradeCount((entity.getTradeCount() != null ? entity.getTradeCount() : 0) + 1);
        if (pnl.compareTo(BigDecimal.ZERO) > 0) {
            entity.setWinCount((entity.getWinCount() != null ? entity.getWinCount() : 0) + 1);
        } else if (pnl.compareTo(BigDecimal.ZERO) < 0) {
            entity.setLossCount((entity.getLossCount() != null ? entity.getLossCount() : 0) + 1);
        }

        // 同步 realizedPnl 供舊 summary 使用
        entity.setRealizedPnl(entity.getGrossPnl());

        return toResponse(dailyPnlRepository.save(entity));
    }

    /**
     * 手動覆蓋損益（券商對帳後補入實際數字）。
     */
    @Transactional
    public DailyPnlResponse updateDaily(Long id, DailyPnlUpdateRequest request) {
        DailyPnlEntity entity = dailyPnlRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "DailyPnl not found: " + id));

        if (request.grossPnl()          != null) entity.setGrossPnl(request.grossPnl());
        if (request.estimatedFeeAndTax() != null) entity.setEstimatedFeeAndTax(request.estimatedFeeAndTax());
        if (request.netPnl()             != null) entity.setNetPnl(request.netPnl());
        if (request.tradeCount()         != null) entity.setTradeCount(request.tradeCount());
        if (request.winCount()           != null) entity.setWinCount(request.winCount());
        if (request.lossCount()          != null) entity.setLossCount(request.lossCount());
        if (request.notes()              != null) entity.setNotes(request.notes());

        // 若 grossPnl 或 feeTax 更新但 netPnl 未傳，自動重算
        if (request.netPnl() == null && (request.grossPnl() != null || request.estimatedFeeAndTax() != null)) {
            BigDecimal g = entity.getGrossPnl() != null ? entity.getGrossPnl() : BigDecimal.ZERO;
            BigDecimal f = entity.getEstimatedFeeAndTax() != null ? entity.getEstimatedFeeAndTax() : BigDecimal.ZERO;
            entity.setNetPnl(g.subtract(f));
        }

        // 同步 realizedPnl
        if (entity.getGrossPnl() != null) entity.setRealizedPnl(entity.getGrossPnl());

        return toResponse(dailyPnlRepository.save(entity));
    }

    public PnlSummaryResponse getSummary(int days) {
        int safeDays = Math.max(1, Math.min(days, 365));
        List<DailyPnlResponse> rows = getDailyHistory(safeDays);

        BigDecimal totalRealized = rows.stream()
                .map(r -> r.grossPnl() != null ? r.grossPnl()
                        : r.realizedPnl() != null ? r.realizedPnl() : BigDecimal.ZERO)
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

    // ── private ──

    private BigDecimal estimateFeeTax(PositionEntity pos) {
        if (pos.getAvgCost() == null || pos.getQty() == null || pos.getClosePrice() == null) {
            return BigDecimal.ZERO;
        }
        double buyValue  = pos.getAvgCost().doubleValue()    * pos.getQty().doubleValue();
        double sellValue = pos.getClosePrice().doubleValue() * pos.getQty().doubleValue();

        // 手續費：買+賣各 0.1425%，每邊最低 20 元
        double fee = Math.max(20.0, buyValue  * 0.001425)
                   + Math.max(20.0, sellValue * 0.001425);

        // 交易稅：代號以 0 開頭 → ETF 0.1%；否則 0.3%
        boolean isEtf = pos.getSymbol() != null && pos.getSymbol().startsWith("0");
        double tax = sellValue * (isEtf ? 0.001 : 0.003);

        return BigDecimal.valueOf(fee + tax).setScale(0, RoundingMode.HALF_UP);
    }

    private DailyPnlResponse toResponse(DailyPnlEntity entity) {
        return new DailyPnlResponse(
                entity.getId(),
                entity.getTradingDate(),
                entity.getRealizedPnl(),
                entity.getUnrealizedPnl(),
                entity.getWinRate(),
                entity.getGrossPnl(),
                entity.getEstimatedFeeAndTax(),
                entity.getNetPnl(),
                entity.getTradeCount(),
                entity.getWinCount(),
                entity.getLossCount(),
                entity.getNotes(),
                entity.getPayloadJson(),
                entity.getCreatedAt()
        );
    }
}
