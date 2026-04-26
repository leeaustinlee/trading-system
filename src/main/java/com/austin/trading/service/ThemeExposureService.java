package com.austin.trading.service;

import com.austin.trading.dto.response.ThemeExposureItem;
import com.austin.trading.dto.response.ThemeExposureResponse;
import com.austin.trading.entity.PositionEntity;
import com.austin.trading.repository.CandidateStockRepository;
import com.austin.trading.repository.PositionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
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
    /** v2.16：對外 API 用，Spring 啟動時注入；舊 ctor 保留以免破壞既有測試。 */
    private final PositionRepository positionRepository;
    private final ScoreConfigService scoreConfigService;
    private final CapitalService capitalService;

    @Autowired
    public ThemeExposureService(CandidateStockRepository candidateRepo,
                                 PositionRepository positionRepository,
                                 ScoreConfigService scoreConfigService,
                                 CapitalService capitalService) {
        this.candidateRepo = candidateRepo;
        this.positionRepository = positionRepository;
        this.scoreConfigService = scoreConfigService;
        this.capitalService = capitalService;
    }

    /** 向下相容 ctor（既有 test 用）。 */
    public ThemeExposureService(CandidateStockRepository candidateRepo) {
        this(candidateRepo, null, null, null);
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

    // ════════════════════════════════════════════════════════════════════════
    // v2.16 Batch C：對外暴露 GET /api/themes/exposure 用的高層 API
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Compute current open position exposures with status (OK/WARN/OVER_LIMIT).
     * Status 規則：
     *   - exposurePct >= limitPct → OVER_LIMIT
     *   - exposurePct >= warnPct  → WARN
     *   - 其他                    → OK
     *
     * <p>limit / warn 從 score_config 讀取，缺值時 fallback 30 / 20。</p>
     */
    public ThemeExposureResponse computeCurrentExposure() {
        List<PositionEntity> openPositions = positionRepository != null
                ? positionRepository.findByStatus("OPEN")
                : List.of();
        return buildResponse(openPositions, fetchLimitPct(), fetchWarnPct(), fetchTotalEquity());
    }

    /** Pure-data 版本，方便單元測試。 */
    public static ThemeExposureResponse buildResponseFromAggregates(
            List<ThemeAggregate> aggregates,
            BigDecimal limitPct, BigDecimal warnPct, BigDecimal totalEquity) {
        if (aggregates == null) aggregates = List.of();

        BigDecimal grandTotalCost = aggregates.stream()
                .map(a -> a.totalCost == null ? BigDecimal.ZERO : a.totalCost)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<ThemeExposureItem> items = new ArrayList<>();
        for (ThemeAggregate a : aggregates) {
            BigDecimal cost = a.totalCost == null ? BigDecimal.ZERO : a.totalCost;
            BigDecimal mv   = a.totalMarketValue == null ? cost : a.totalMarketValue;
            BigDecimal pct  = grandTotalCost.signum() > 0
                    ? cost.multiply(HUNDRED).divide(grandTotalCost, 4, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            String status;
            if (pct.compareTo(limitPct) >= 0) status = "OVER_LIMIT";
            else if (pct.compareTo(warnPct) >= 0) status = "WARN";
            else status = "OK";
            items.add(new ThemeExposureItem(
                    a.theme, cost, mv, pct, limitPct, warnPct, status, a.positionCount));
        }
        items.sort(Comparator.comparing(ThemeExposureItem::exposurePct).reversed());

        return new ThemeExposureResponse(
                items, grandTotalCost, totalEquity, limitPct, warnPct, LocalDateTime.now());
    }

    /** Pure-data 入口：把 PositionEntity 轉 aggregates 後呼叫上面的 build。 */
    public ThemeExposureResponse buildResponse(
            List<PositionEntity> openPositions,
            BigDecimal limitPct, BigDecimal warnPct, BigDecimal totalEquity) {
        if (openPositions == null) openPositions = List.of();

        Map<String, BigDecimal> themeCost = new HashMap<>();
        Map<String, Integer>    themeCount = new HashMap<>();
        for (PositionEntity p : openPositions) {
            BigDecimal cost = positionCost(p);
            if (cost.signum() <= 0) continue;
            String theme = resolveTheme(p.getSymbol());
            themeCost.merge(theme, cost, BigDecimal::add);
            themeCount.merge(theme, 1, Integer::sum);
        }

        List<ThemeAggregate> aggs = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> e : themeCost.entrySet()) {
            aggs.add(new ThemeAggregate(e.getKey(), e.getValue(), e.getValue(),
                    themeCount.getOrDefault(e.getKey(), 0)));
        }
        return buildResponseFromAggregates(aggs, limitPct, warnPct, totalEquity);
    }

    private BigDecimal fetchLimitPct() {
        if (scoreConfigService == null) return new BigDecimal("30");
        return scoreConfigService.getDecimal("theme.exposure_limit_pct", new BigDecimal("30"));
    }
    private BigDecimal fetchWarnPct() {
        if (scoreConfigService == null) return new BigDecimal("20");
        return scoreConfigService.getDecimal("theme.exposure_warn_pct", new BigDecimal("20"));
    }
    private BigDecimal fetchTotalEquity() {
        if (capitalService == null) return null;
        try {
            var summary = capitalService.getSummary();
            if (summary == null) return null;
            return summary.totalAssets() != null ? summary.totalAssets() : summary.totalEquity();
        } catch (RuntimeException e) {
            log.warn("[ThemeExposure] fetchTotalEquity 失敗: {}", e.getMessage());
            return null;
        }
    }

    /** Pure-data aggregate 給 unit test 直接傳。 */
    public record ThemeAggregate(
            String theme,
            BigDecimal totalCost,
            BigDecimal totalMarketValue,
            int positionCount
    ) {}
}
