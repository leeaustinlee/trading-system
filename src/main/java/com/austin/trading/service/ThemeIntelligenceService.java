package com.austin.trading.service;

import com.austin.trading.dto.response.DataFreshnessSnapshot;
import com.austin.trading.dto.response.ThemeContextSnapshot;
import com.austin.trading.entity.KolThemeSignalDailySnapshotEntity;
import com.austin.trading.entity.ThemeLifecycleStateEntity;
import com.austin.trading.entity.ThemeSnapshotEntity;
import com.austin.trading.repository.KolThemeSignalDailySnapshotRepository;
import com.austin.trading.repository.ThemeLifecycleStateRepository;
import com.austin.trading.repository.ThemeSnapshotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Service
public class ThemeIntelligenceService {
    private static final Logger log = LoggerFactory.getLogger(ThemeIntelligenceService.class);

    private final ThemeSnapshotRepository themeSnapshotRepository;
    private final ThemeLifecycleStateRepository lifecycleRepository;
    private final KolThemeSignalDailySnapshotRepository kolRepository;
    private final ThemeLifecycleResolver lifecycleResolver;
    private final DataFreshnessService freshnessService;

    public ThemeIntelligenceService(ThemeSnapshotRepository themeSnapshotRepository,
                                    ThemeLifecycleStateRepository lifecycleRepository,
                                    KolThemeSignalDailySnapshotRepository kolRepository,
                                    ThemeLifecycleResolver lifecycleResolver) {
        this(themeSnapshotRepository, lifecycleRepository, kolRepository, lifecycleResolver, new DataFreshnessService());
    }

    @Autowired
    public ThemeIntelligenceService(ThemeSnapshotRepository themeSnapshotRepository,
                                    ThemeLifecycleStateRepository lifecycleRepository,
                                    KolThemeSignalDailySnapshotRepository kolRepository,
                                    ThemeLifecycleResolver lifecycleResolver,
                                    DataFreshnessService freshnessService) {
        this.themeSnapshotRepository = themeSnapshotRepository;
        this.lifecycleRepository = lifecycleRepository;
        this.kolRepository = kolRepository;
        this.lifecycleResolver = lifecycleResolver;
        this.freshnessService = freshnessService;
    }

    @Transactional(readOnly = true)
    public List<ThemeContextSnapshot> summary() {
        LocalDate today = freshnessService.today();
        boolean futureDataDetected = themeSnapshotRepository.countFutureRows(today) > 0;
        if (futureDataDetected) {
            LocalDate futureMax = themeSnapshotRepository.findLatestTradingDate();
            log.warn("[ThemeIntelligence] FUTURE_DATA_DETECTED ignored in summary today={} futureMax={}", today, futureMax);
        }
        LocalDate date = themeSnapshotRepository.findLatestValidTradingDate(today);
        if (date == null) return List.of();
        DataFreshnessSnapshot freshness = freshnessService.evaluate(date, today, futureDataDetected);
        return themeSnapshotRepository.findByTradingDateOrderByFinalThemeScoreDesc(date).stream()
                .map(s -> context(s.getThemeTag(), today, freshness))
                .toList();
    }

    @Transactional(readOnly = true)
    public ThemeContextSnapshot context(String themeName) {
        return context(themeName, freshnessService.today(), null);
    }

    @Transactional(readOnly = true)
    public DataFreshnessSnapshot themeSnapshotFreshness() {
        LocalDate today = freshnessService.today();
        return freshnessService.evaluate(themeSnapshotRepository.findLatestValidTradingDate(today), today,
                themeSnapshotRepository.countFutureRows(today) > 0);
    }

    private ThemeContextSnapshot context(String themeName, LocalDate today, DataFreshnessSnapshot precomputedFreshness) {
        if (themeName == null || themeName.isBlank()) return ThemeContextSnapshot.unknown(themeName);
        boolean futureDataDetected = themeSnapshotRepository.countFutureRowsForTheme(themeName, today) > 0;
        if (futureDataDetected) {
            log.warn("[ThemeIntelligence] FUTURE_DATA_DETECTED ignored for theme={} today={}", themeName, today);
        }
        LocalDate date = latestDateForTheme(themeName, today);
        if (date == null) return ThemeContextSnapshot.unknown(themeName);
        DataFreshnessSnapshot freshness = precomputedFreshness != null && eqDate(precomputedFreshness.latestDataDate(), date)
                ? precomputedFreshness
                : freshnessService.evaluate(date, today, futureDataDetected);
        Optional<ThemeSnapshotEntity> snap = themeSnapshotRepository.findByTradingDateAndThemeTag(date, themeName);
        Optional<ThemeLifecycleStateEntity> life = lifecycleRepository.findByTradingDateAndThemeTag(date, themeName);
        List<KolThemeSignalDailySnapshotEntity> kol = kolRepository.findByTradingDateAndThemeTag(date, themeName);
        return build(themeName, date, snap.orElse(null), life.orElse(null), kol, freshness);
    }

    private ThemeContextSnapshot build(String themeName, LocalDate date, ThemeSnapshotEntity snap,
                                       ThemeLifecycleStateEntity life, List<KolThemeSignalDailySnapshotEntity> kol,
                                       DataFreshnessSnapshot freshness) {
        BigDecimal heat = first(snap == null ? null : snap.getThemeHeatScore(), snap == null ? null : snap.getFinalThemeScore());
        BigDecimal breadth = life == null || life.getBreadth() == null ? null : BigDecimal.valueOf(life.getBreadth());
        BigDecimal rotation = life == null ? null : life.getRotationScore();
        BigDecimal crowdingScore = max(life == null ? null : life.getCrowdingScore(), kol.stream().map(KolThemeSignalDailySnapshotEntity::getNetShadowBoost).max(Comparator.naturalOrder()).orElse(null));
        BigDecimal narrativeHeat = first(life == null ? null : life.getNarrativeDensity(), kol.stream().map(KolThemeSignalDailySnapshotEntity::getPositiveScore).max(Comparator.naturalOrder()).orElse(null), heat);
        String lifecycle = lifecycleResolver.normalize(life == null ? null : life.getStage(), heat, crowdingScore, rotation);
        String crowding = crowdingRisk(crowdingScore, kol);
        String institutional = institutionalAlignment(life == null ? null : life.getInstitutionalFlowScore());
        BigDecimal waveStrength = first(life == null ? null : life.getLifecycleScore(), snap == null ? null : snap.getThemeContinuationScore());
        String leadership = sectorLeadership(life == null ? null : life.getLeaderCount(), snap == null ? null : snap.getLeadingStockSymbol());
        String status = freshness.dataFreshnessStatus().name();
        if (freshness.futureDataDetected()) status = "FUTURE_DATA_DETECTED";
        Map<String, Object> marketContext = new LinkedHashMap<>();
        marketContext.put("themeCategory", snap == null ? null : snap.getThemeCategory());
        marketContext.put("leadingStockSymbol", snap == null ? null : snap.getLeadingStockSymbol());
        marketContext.put("driverType", snap == null ? null : snap.getDriverType());
        marketContext.put("riskSummary", snap == null ? null : snap.getRiskSummary());
        marketContext.put("source", "theme_snapshot + theme_lifecycle_state + kol_theme_signal_daily_snapshot");
        marketContext.put("dataStatus", status);
        marketContext.put("latestValidTradingDate", freshness.latestDataDate());
        marketContext.put("futureDataDetected", freshness.futureDataDetected());
        marketContext.put("staleDays", freshness.staleDays());
        return new ThemeContextSnapshot(themeName, lifecycle, heat, breadth, rotation, institutional, crowding,
                narrativeHeat, waveStrength, leadership, crowdingScore, marketContext,
                lifecycleResolver.active(lifecycle), false, false, false, true, date,
                status, freshness.latestDataDate(), freshness.futureDataDetected(), freshness.staleDays(),
                freshness.latestDataDate(), freshness.dataFreshnessStatus().name());
    }

    private LocalDate latestDateForTheme(String themeName, LocalDate today) {
        LocalDate a = themeSnapshotRepository.findLatestValidTradingDateForTheme(themeName, today);
        LocalDate b = lifecycleRepository.findAll().stream().filter(x -> eq(x.getThemeTag(), themeName) && !x.getTradingDate().isAfter(today)).map(ThemeLifecycleStateEntity::getTradingDate).max(LocalDate::compareTo).orElse(null);
        LocalDate c = kolRepository.findAll().stream().filter(x -> eq(x.getThemeTag(), themeName) && !x.getTradingDate().isAfter(today)).map(KolThemeSignalDailySnapshotEntity::getTradingDate).max(LocalDate::compareTo).orElse(null);
        return java.util.stream.Stream.of(a, b, c).filter(x -> x != null).max(LocalDate::compareTo).orElse(null);
    }

    private static String institutionalAlignment(BigDecimal v) {
        if (v == null) return "UNKNOWN";
        if (v.compareTo(new BigDecimal("0.30")) >= 0) return "STRONG";
        if (v.compareTo(BigDecimal.ZERO) > 0) return "POSITIVE";
        if (v.compareTo(new BigDecimal("-0.30")) <= 0) return "NEGATIVE";
        return "NEUTRAL";
    }

    private static String sectorLeadership(Integer leaders, String leaderSymbol) {
        if (leaders != null && leaders >= 2) return "STRONG";
        if (leaders != null && leaders == 1 || (leaderSymbol != null && !leaderSymbol.isBlank())) return "POSITIVE";
        return "UNKNOWN";
    }

    private static String crowdingRisk(BigDecimal crowdingScore, List<KolThemeSignalDailySnapshotEntity> kol) {
        if (kol != null) {
            for (KolThemeSignalDailySnapshotEntity e : kol) {
                if (e.getCrowdingRisk() != null && !e.getCrowdingRisk().isBlank() && !"LOW".equalsIgnoreCase(e.getCrowdingRisk())) return e.getCrowdingRisk().toUpperCase(Locale.ROOT);
            }
        }
        if (crowdingScore == null) return "UNKNOWN";
        if (crowdingScore.compareTo(new BigDecimal("0.75")) >= 0 || crowdingScore.compareTo(new BigDecimal("8.0")) >= 0) return "HIGH";
        if (crowdingScore.compareTo(new BigDecimal("0.45")) >= 0 || crowdingScore.compareTo(new BigDecimal("5.0")) >= 0) return "MEDIUM";
        return "LOW";
    }

    private static BigDecimal first(BigDecimal... values) { for (BigDecimal v : values) if (v != null) return v; return null; }
    private static BigDecimal max(BigDecimal a, BigDecimal b) { if (a == null) return b; if (b == null) return a; return a.max(b); }
    private static boolean eq(String a, String b) { return a != null && b != null && a.equalsIgnoreCase(b); }
    private static boolean eqDate(LocalDate a, LocalDate b) { return a != null && a.equals(b); }
}
