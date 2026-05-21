package com.austin.trading.service;

import com.austin.trading.dto.response.NarrativeDashboardResponse;
import com.austin.trading.entity.KolThemeSignalDailySnapshotEntity;
import com.austin.trading.repository.KolThemeSignalDailySnapshotRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

@Service
public class NarrativeDashboardService {

    private final KolThemeSignalDailySnapshotRepository snapshotRepo;

    public NarrativeDashboardService(KolThemeSignalDailySnapshotRepository snapshotRepo) {
        this.snapshotRepo = snapshotRepo;
    }

    public NarrativeDashboardResponse dashboard(LocalDate date) {
        var rows = snapshotRepo.findByTradingDateOrderByNetShadowBoostDesc(date).stream()
                .map(this::toRow)
                .toList();
        return new NarrativeDashboardResponse(
                date,
                true,
                KolSignalContextService.WEAK_SIGNAL_GUARDRAIL,
                rows
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
        // Keep MVP transparent and conservative: risk bucket dominates, while counts/attention are
        // available in the row for audit. Do not turn this into a BUY/ENTER score.
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
