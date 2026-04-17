package com.austin.trading.service;

import com.austin.trading.dto.response.CapitalSummaryResponse;
import com.austin.trading.dto.response.LiveQuoteResponse;
import com.austin.trading.entity.CapitalConfigEntity;
import com.austin.trading.entity.PositionEntity;
import com.austin.trading.repository.CapitalConfigRepository;
import com.austin.trading.repository.PositionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class CapitalService {

    private static final Long CONFIG_ID = 1L;

    private final CapitalConfigRepository capitalConfigRepository;
    private final PositionRepository positionRepository;
    private final CandidateScanService candidateScanService;

    public CapitalService(
            CapitalConfigRepository capitalConfigRepository,
            PositionRepository positionRepository,
            CandidateScanService candidateScanService
    ) {
        this.capitalConfigRepository = capitalConfigRepository;
        this.positionRepository = positionRepository;
        this.candidateScanService = candidateScanService;
    }

    /** 取得資金設定（不含即時報價） */
    public CapitalConfigEntity getConfig() {
        return capitalConfigRepository.findById(CONFIG_ID)
                .orElseGet(this::defaultConfig);
    }

    /** 更新可動用現金與備註 */
    @Transactional
    public CapitalConfigEntity updateConfig(BigDecimal availableCash, String notes) {
        CapitalConfigEntity cfg = capitalConfigRepository.findById(CONFIG_ID)
                .orElseGet(this::defaultConfig);
        if (availableCash != null) cfg.setAvailableCash(availableCash);
        if (notes        != null) cfg.setNotes(notes);
        cfg.setUpdatedAt(LocalDateTime.now());
        return capitalConfigRepository.save(cfg);
    }

    /**
     * 完整資金總覽：可動用現金 + 持倉現值（呼叫 TWSE 即時報價）。
     * 盤外或報價失敗時 investedValue/unrealizedPnl 以持倉成本估算。
     */
    public CapitalSummaryResponse getSummary() {
        CapitalConfigEntity cfg = getConfig();
        BigDecimal availableCash = cfg.getAvailableCash() != null ? cfg.getAvailableCash() : BigDecimal.ZERO;

        // 取所有持倉中部位
        List<PositionEntity> openPositions = positionRepository.findByStatus("OPEN");

        // 計算持倉成本
        BigDecimal investedCost = openPositions.stream()
                .map(p -> p.getAvgCost() != null && p.getQty() != null
                        ? p.getAvgCost().multiply(p.getQty()) : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(0, RoundingMode.HALF_UP);

        // 抓即時報價
        List<String> symbols = openPositions.stream()
                .map(PositionEntity::getSymbol)
                .distinct().collect(Collectors.toList());

        boolean liveAvailable = false;
        BigDecimal investedValue = null;
        BigDecimal unrealizedPnl = null;

        if (!symbols.isEmpty()) {
            try {
                List<LiveQuoteResponse> quotes = candidateScanService.getLiveQuotesBySymbols(symbols);
                Map<String, Double> priceMap = quotes.stream()
                        .filter(q -> q.currentPrice() != null)
                        .collect(Collectors.toMap(
                                LiveQuoteResponse::symbol,
                                LiveQuoteResponse::currentPrice,
                                (a, b) -> a));

                if (!priceMap.isEmpty()) {
                    liveAvailable = true;
                    BigDecimal totalValue = BigDecimal.ZERO;
                    BigDecimal totalUnrealized = BigDecimal.ZERO;

                    for (PositionEntity p : openPositions) {
                        if (p.getAvgCost() == null || p.getQty() == null) continue;
                        Double cur = priceMap.get(p.getSymbol());
                        if (cur == null) continue;

                        BigDecimal curBD = BigDecimal.valueOf(cur);
                        BigDecimal value = curBD.multiply(p.getQty());
                        totalValue = totalValue.add(value);

                        boolean isShort = "SHORT".equalsIgnoreCase(p.getSide())
                                || "做空".equals(p.getSide());
                        BigDecimal diff = isShort
                                ? p.getAvgCost().subtract(curBD)
                                : curBD.subtract(p.getAvgCost());
                        totalUnrealized = totalUnrealized.add(diff.multiply(p.getQty()));
                    }
                    investedValue   = totalValue.setScale(0, RoundingMode.HALF_UP);
                    unrealizedPnl   = totalUnrealized.setScale(0, RoundingMode.HALF_UP);
                }
            } catch (Exception ignored) { /* 報價失敗不影響設定值 */ }
        }

        // 無即時報價時，以成本替代估算總資產
        BigDecimal effectiveValue = investedValue != null ? investedValue : investedCost;
        BigDecimal totalAssets    = availableCash.add(effectiveValue).setScale(0, RoundingMode.HALF_UP);

        BigDecimal cashRatio = totalAssets.compareTo(BigDecimal.ZERO) > 0
                ? availableCash.divide(totalAssets, 4, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP)
                : BigDecimal.valueOf(100);

        String updatedAt = cfg.getUpdatedAt() != null
                ? cfg.getUpdatedAt().format(DateTimeFormatter.ofPattern("MM/dd HH:mm"))
                : null;

        return new CapitalSummaryResponse(
                availableCash, investedCost, investedValue, unrealizedPnl,
                totalAssets, cashRatio,
                openPositions.size(), liveAvailable,
                cfg.getNotes(), updatedAt
        );
    }

    private CapitalConfigEntity defaultConfig() {
        CapitalConfigEntity e = new CapitalConfigEntity();
        e.setId(CONFIG_ID);
        e.setAvailableCash(BigDecimal.ZERO);
        return e;
    }
}
