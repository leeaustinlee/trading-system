package com.austin.trading.service;

import com.austin.trading.dto.internal.MarketRegimeDecision;
import com.austin.trading.dto.internal.ThemeStrengthDecision;
import com.austin.trading.dto.internal.ThemeStrengthInput;
import com.austin.trading.engine.ThemeStrengthEngine;
import com.austin.trading.entity.StockThemeMappingEntity;
import com.austin.trading.entity.ThemeSnapshotEntity;
import com.austin.trading.entity.ThemeStrengthDecisionEntity;
import com.austin.trading.repository.StockThemeMappingRepository;
import com.austin.trading.repository.ThemeSnapshotRepository;
import com.austin.trading.repository.ThemeStrengthDecisionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * P1.1 Theme Strength Layer.
 *
 * <p>Reads {@code ThemeSnapshotEntity} records for a trading date, builds
 * {@link ThemeStrengthInput}, delegates to {@link ThemeStrengthEngine}, and
 * persists results to {@code theme_strength_decision}.</p>
 */
@Service
public class ThemeStrengthService {

    private static final Logger log = LoggerFactory.getLogger(ThemeStrengthService.class);

    private final ThemeStrengthEngine             engine;
    private final ThemeSnapshotRepository         snapshotRepo;
    private final StockThemeMappingRepository     mappingRepo;
    private final ThemeStrengthDecisionRepository decisionRepo;

    public ThemeStrengthService(
            ThemeStrengthEngine engine,
            ThemeSnapshotRepository snapshotRepo,
            StockThemeMappingRepository mappingRepo,
            ThemeStrengthDecisionRepository decisionRepo) {
        this.engine       = engine;
        this.snapshotRepo = snapshotRepo;
        this.mappingRepo  = mappingRepo;
        this.decisionRepo = decisionRepo;
    }

    /**
     * Evaluate all themes for {@code tradingDate} and persist.
     *
     * @return map from themeTag → ThemeStrengthDecision (insertion order = score desc)
     */
    @Transactional
    public Map<String, ThemeStrengthDecision> evaluateAll(LocalDate tradingDate,
                                                           MarketRegimeDecision regime) {
        List<ThemeSnapshotEntity> snapshots =
                snapshotRepo.findByTradingDateOrderByFinalThemeScoreDesc(tradingDate);

        Map<String, ThemeStrengthDecision> result = new LinkedHashMap<>();
        for (ThemeSnapshotEntity snap : snapshots) {
            ThemeStrengthInput in = toInput(snap, tradingDate);
            ThemeStrengthDecision d = engine.evaluate(in, regime);
            if (d == null) continue;
            result.put(d.themeTag(), d);
            decisionRepo.save(toEntity(d));
        }

        long tradable = result.values().stream().filter(ThemeStrengthDecision::tradable).count();
        log.info("[ThemeStrength] tradingDate={} themes={} tradable={}", tradingDate, result.size(), tradable);
        return result;
    }

    /**
     * Find the latest ThemeStrengthDecision for a given symbol on {@code tradingDate}.
     * Looks up the symbol's leading theme via StockThemeMapping first.
     */
    public Optional<ThemeStrengthDecision> findForSymbol(String symbol, LocalDate tradingDate) {
        List<StockThemeMappingEntity> mappings = mappingRepo.findBySymbolAndIsActiveTrue(symbol);
        if (mappings.isEmpty()) return Optional.empty();

        return mappings.stream()
                .map(m -> decisionRepo.findTopByTradingDateAndThemeTagOrderByIdDesc(
                        tradingDate, m.getThemeTag()))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .max((a, b) -> {
                    BigDecimal sa = a.getStrengthScore() != null ? a.getStrengthScore() : BigDecimal.ZERO;
                    BigDecimal sb = b.getStrengthScore() != null ? b.getStrengthScore() : BigDecimal.ZERO;
                    return sa.compareTo(sb);
                })
                .map(this::toDecision);
    }

    // ── private helpers ───────────────────────────────────────────────────────

    private ThemeStrengthInput toInput(ThemeSnapshotEntity snap, LocalDate tradingDate) {
        // breadth proxy: normalize strongStockCount against total mapped stocks
        BigDecimal breadthScore = computeBreadthScore(snap);

        return new ThemeStrengthInput(
                tradingDate,
                snap.getThemeTag(),
                orZero(snap.getMarketBehaviorScore()),
                breadthScore,
                orZero(snap.getAvgGainPct()),
                orZero(snap.getThemeHeatScore()),
                orZero(snap.getThemeContinuationScore()),
                snap.getDriverType(),
                snap.getRiskSummary() != null && !snap.getRiskSummary().isBlank()
        );
    }

    private BigDecimal computeBreadthScore(ThemeSnapshotEntity snap) {
        if (snap.getStrongStockCount() == null) return BigDecimal.ZERO;
        int totalMapped = mappingRepo.findByThemeTagAndIsActiveTrue(snap.getThemeTag()).size();
        if (totalMapped == 0) return BigDecimal.ZERO;
        double ratio = (double) snap.getStrongStockCount() / totalMapped;
        return BigDecimal.valueOf(ratio * 10).min(BigDecimal.TEN)
                .setScale(4, RoundingMode.HALF_UP);
    }

    private ThemeStrengthDecisionEntity toEntity(ThemeStrengthDecision d) {
        ThemeStrengthDecisionEntity e = new ThemeStrengthDecisionEntity();
        e.setTradingDate(d.tradingDate());
        e.setThemeTag(d.themeTag());
        e.setStrengthScore(d.strengthScore());
        e.setThemeStage(d.themeStage());
        e.setCatalystType(d.catalystType());
        e.setTradable(d.tradable());
        e.setDecayRisk(d.decayRisk());
        e.setReasonsJson(d.reasonsJson());
        return e;
    }

    private ThemeStrengthDecision toDecision(ThemeStrengthDecisionEntity e) {
        return new ThemeStrengthDecision(
                e.getTradingDate(), e.getThemeTag(),
                e.getStrengthScore(), e.getThemeStage(), e.getCatalystType(),
                e.isTradable(), e.getDecayRisk(), e.getReasonsJson());
    }

    private static BigDecimal orZero(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }
}
