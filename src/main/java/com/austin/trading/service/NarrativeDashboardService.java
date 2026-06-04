package com.austin.trading.service;

import com.austin.trading.dto.response.NarrativeDashboardResponse;
import com.austin.trading.entity.KolThemeSignalDailySnapshotEntity;
import com.austin.trading.repository.KolThemeSignalDailySnapshotRepository;
import com.austin.trading.repository.KolThemeSignalRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

@Service
public class NarrativeDashboardService {

    private final KolThemeSignalDailySnapshotRepository snapshotRepo;
    private final KolThemeSignalRepository signalRepo;
    private final DataFreshnessService freshnessService;

    public NarrativeDashboardService(KolThemeSignalDailySnapshotRepository snapshotRepo) {
        this(snapshotRepo, null, new DataFreshnessService());
    }

    @Autowired
    public NarrativeDashboardService(KolThemeSignalDailySnapshotRepository snapshotRepo,
                                     KolThemeSignalRepository signalRepo,
                                     DataFreshnessService freshnessService) {
        this.snapshotRepo = snapshotRepo;
        this.signalRepo = signalRepo;
        this.freshnessService = freshnessService;
    }

    public NarrativeDashboardResponse dashboard(LocalDate date) {
        var rows = snapshotRepo.findByTradingDateOrderByNetShadowBoostDesc(date).stream()
                .map(this::toRow)
                .toList();
        LocalDate latestSignalDate = snapshotRepo.findLatestTradingDate();
        var freshness = freshnessService.evaluate(latestSignalDate, false);
        long signalCountToday = signalRepo == null ? 0 : signalRepo.countByTradingDate(date);
        long signalCount7d = signalRepo == null ? 0 : signalRepo.countByTradingDateBetween(date.minusDays(6), date);
        return new NarrativeDashboardResponse(
                date,
                true,
                KolSignalContextService.WEAK_SIGNAL_GUARDRAIL,
                rows,
                rows.stream().collect(java.util.stream.Collectors.groupingBy(
                        NarrativeDashboardResponse.Row::lifecycle,
                        java.util.LinkedHashMap::new,
                        java.util.stream.Collectors.counting())),
                rows.stream().filter(r -> "CROWDED".equals(r.lifecycle()) || "EXHAUSTED".equals(r.lifecycle()))
                        .map(NarrativeDashboardResponse.Row::theme).toList(),
                rows.stream().filter(r -> "EMERGING".equals(r.lifecycle()))
                        .map(NarrativeDashboardResponse.Row::theme).toList(),
                rows.stream().sorted(java.util.Comparator.comparing(NarrativeDashboardResponse.Row::attention).reversed())
                        .limit(5).map(NarrativeDashboardResponse.Row::theme).toList(),
                rows.stream().filter(r -> "CROWDED".equals(r.lifecycle()) || "EXHAUSTED".equals(r.lifecycle())
                        || r.crowding().compareTo(new BigDecimal("8.0")) >= 0).count(),
                latestSignalDate,
                freshness.staleDays(),
                freshness.dataFreshnessStatus().name(),
                latestSignalDate,
                signalCountToday,
                signalCount7d,
                rows.isEmpty() ? "NO_RECENT_SIGNAL" : freshness.warning()
        );
    }

    public NarrativeDashboardResponse.Row toRow(KolThemeSignalDailySnapshotEntity e) {
        BigDecimal attention = toTenPointScore(e.getPositiveScore().max(e.getNegativeScore()));
        BigDecimal crowding = crowdingScore(e.getCrowdingRisk(), e.getSourceCount(), e.getEvidenceCount(), attention);
        return new NarrativeDashboardResponse.Row(
                e.getThemeTag(),
                lifecycle(attention, crowding, e.getSourceCount(), e.getEvidenceCount()),
                attention,
                "DAILY_SNAPSHOT",
                crowding,
                e.getDirection(),
                safeInt(e.getSourceCount()),
                safeInt(e.getEvidenceCount()),
                e.getNetShadowBoost()
        );
    }

    private String lifecycle(BigDecimal attention, BigDecimal crowding, Integer sourceCount, Integer evidenceCount) {
        if (crowding.compareTo(new BigDecimal("8.0")) >= 0) return "CROWDED";
        if (attention.compareTo(new BigDecimal("8.0")) >= 0 && safeInt(sourceCount) >= 5) return "EXPANDING";
        if (attention.compareTo(new BigDecimal("6.5")) >= 0 && crowding.compareTo(new BigDecimal("6.5")) < 0) return "EMERGING";
        if (attention.compareTo(new BigDecimal("4.0")) >= 0) return "EARLY";
        return "NOISE";
    }

    private BigDecimal crowdingScore(String crowdingRisk, Integer sourceCount, Integer evidenceCount, BigDecimal attention) {
        String risk = crowdingRisk == null ? "LOW" : crowdingRisk.trim().toUpperCase();
        BigDecimal base = switch (risk) {
            case "HIGH" -> new BigDecimal("8.7");
            case "MEDIUM" -> new BigDecimal("5.2");
            default -> new BigDecimal("3.1");
        };
        return base.setScale(1, RoundingMode.HALF_UP);
    }

    private BigDecimal toTenPointScore(BigDecimal score0to1) {
        BigDecimal score = score0to1 == null ? BigDecimal.ZERO : score0to1;
        return score.multiply(BigDecimal.TEN).setScale(1, RoundingMode.HALF_UP);
    }

    private int safeInt(Integer value) {
        return value == null ? 0 : value;
    }
}
