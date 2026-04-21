package com.austin.trading.service;

import com.austin.trading.entity.PositionEntity;
import com.austin.trading.repository.CandidateStockRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * P0.5 — computes per-theme exposure percentage across all open positions.
 *
 * <p>Exposure = (sum of qty * avgCost for positions in theme) / (total portfolio cost) * 100.</p>
 *
 * <p>Theme is resolved by looking up the most recent {@code CandidateStockEntity}
 * for each position symbol.  If no candidate record is found, the position is
 * bucketed under the theme {@code "UNKNOWN"}.</p>
 */
@Service
public class ThemeExposureService {

    private static final Logger log = LoggerFactory.getLogger(ThemeExposureService.class);

    private static final String UNKNOWN_THEME = "UNKNOWN";
    private static final BigDecimal HUNDRED    = new BigDecimal("100");

    private final CandidateStockRepository candidateRepo;

    public ThemeExposureService(CandidateStockRepository candidateRepo) {
        this.candidateRepo = candidateRepo;
    }

    /**
     * Compute per-theme portfolio exposure for the given open positions.
     *
     * @return map of themeTag → exposure % (0–100); empty map when no positions.
     */
    public Map<String, BigDecimal> compute(List<PositionEntity> openPositions) {
        if (openPositions == null || openPositions.isEmpty()) return Map.of();

        BigDecimal totalCost = BigDecimal.ZERO;
        Map<String, BigDecimal> themeCost = new HashMap<>();

        for (PositionEntity pos : openPositions) {
            BigDecimal cost = positionCost(pos);
            if (cost.compareTo(BigDecimal.ZERO) <= 0) continue;

            totalCost = totalCost.add(cost);

            String theme = resolveTheme(pos.getSymbol());
            themeCost.merge(theme, cost, BigDecimal::add);
        }

        if (totalCost.compareTo(BigDecimal.ZERO) == 0) return Map.of();

        Map<String, BigDecimal> result = new HashMap<>();
        BigDecimal finalTotal = totalCost;
        themeCost.forEach((theme, cost) -> {
            BigDecimal pct = cost.multiply(HUNDRED)
                    .divide(finalTotal, 4, RoundingMode.HALF_UP);
            result.put(theme, pct);
        });

        log.debug("[ThemeExposure] positions={} totalCost={} themes={}",
                openPositions.size(), totalCost.toPlainString(), result);
        return result;
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private BigDecimal positionCost(PositionEntity pos) {
        if (pos.getQty() == null || pos.getAvgCost() == null) return BigDecimal.ZERO;
        return pos.getQty().multiply(pos.getAvgCost()).abs();
    }

    private String resolveTheme(String symbol) {
        return candidateRepo.findTopBySymbolOrderByTradingDateDesc(symbol)
                .map(e -> e.getThemeTag() != null ? e.getThemeTag() : UNKNOWN_THEME)
                .orElse(UNKNOWN_THEME);
    }
}
