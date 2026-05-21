package com.austin.trading.service;

import com.austin.trading.dto.response.KolShadowReportResponse;
import com.austin.trading.entity.CandidateStockEntity;
import com.austin.trading.entity.KolThemeSignalDailySnapshotEntity;
import com.austin.trading.repository.CandidateStockRepository;
import com.austin.trading.repository.KolThemeSignalDailySnapshotRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class KolSignalShadowModeService {

    private final CandidateStockRepository candidateRepo;
    private final KolThemeSignalDailySnapshotRepository snapshotRepo;

    public KolSignalShadowModeService(CandidateStockRepository candidateRepo,
                                      KolThemeSignalDailySnapshotRepository snapshotRepo) {
        this.candidateRepo = candidateRepo;
        this.snapshotRepo = snapshotRepo;
    }

    public KolShadowReportResponse run(LocalDate date) {
        return compute(date);
    }

    public KolShadowReportResponse report(LocalDate date) {
        return compute(date);
    }

    private KolShadowReportResponse compute(LocalDate date) {
        Map<String, KolThemeSignalDailySnapshotEntity> boostByTheme = snapshotRepo
                .findByTradingDateOrderByNetShadowBoostDesc(date).stream()
                .collect(Collectors.toMap(KolThemeSignalDailySnapshotEntity::getThemeTag, e -> e, (a, b) ->
                        abs(a.getNetShadowBoost()).compareTo(abs(b.getNetShadowBoost())) >= 0 ? a : b));
        List<KolShadowReportResponse.Item> items = candidateRepo.findByTradingDateOrderByScoreDesc(date, org.springframework.data.domain.Pageable.unpaged()).stream()
                .map(candidate -> toItem(candidate, boostByTheme.get(candidate.getThemeTag())))
                .sorted(Comparator.comparing(KolShadowReportResponse.Item::shadowScore,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
        return new KolShadowReportResponse(date, items.size(), items,
                "computedOnDemand=true; not persisted; shadow only; production decision unchanged");
    }

    private KolShadowReportResponse.Item toItem(CandidateStockEntity candidate,
                                               KolThemeSignalDailySnapshotEntity snapshot) {
        BigDecimal base = candidate.getScore() == null ? BigDecimal.ZERO : candidate.getScore();
        BigDecimal boost = snapshot == null ? BigDecimal.ZERO : snapshot.getNetShadowBoost();
        return new KolShadowReportResponse.Item(
                candidate.getSymbol(),
                candidate.getStockName(),
                candidate.getThemeTag(),
                base,
                boost,
                base.add(boost),
                snapshot == null ? "NONE" : snapshot.getCrowdingRisk(),
                snapshot == null ? null : toNarrativeContext(snapshot),
                "shadow only; production candidate score and final decision are unchanged"
        );
    }

    private KolShadowReportResponse.NarrativeContext toNarrativeContext(KolThemeSignalDailySnapshotEntity snapshot) {
        BigDecimal positive = safeDecimal(snapshot.getPositiveScore());
        BigDecimal negative = safeDecimal(snapshot.getNegativeScore());
        BigDecimal attention = positive.max(negative).multiply(BigDecimal.TEN).setScale(1, RoundingMode.HALF_UP);
        BigDecimal crowding = crowdingScore(snapshot.getCrowdingRisk());
        return new KolShadowReportResponse.NarrativeContext(
                true,
                snapshot.getThemeTag(),
                lifecycle(attention, crowding, snapshot.getSourceCount()),
                attention,
                "DAILY_SNAPSHOT",
                crowding,
                snapshot.getDirection(),
                safeInt(snapshot.getSourceCount()),
                safeInt(snapshot.getEvidenceCount()),
                safeDecimal(snapshot.getNetShadowBoost()),
                KolSignalContextService.WEAK_SIGNAL_GUARDRAIL
        );
    }

    private String lifecycle(BigDecimal attention, BigDecimal crowding, Integer sourceCount) {
        if (crowding.compareTo(new BigDecimal("8.0")) >= 0) return "CROWDED";
        if (attention.compareTo(new BigDecimal("8.0")) >= 0 && safeInt(sourceCount) >= 5) return "EXPANDING";
        if (attention.compareTo(new BigDecimal("6.5")) >= 0 && crowding.compareTo(new BigDecimal("6.5")) < 0) return "EMERGING";
        if (attention.compareTo(new BigDecimal("4.0")) >= 0) return "EARLY";
        return "NOISE";
    }

    private BigDecimal crowdingScore(String crowdingRisk) {
        String risk = crowdingRisk == null ? "LOW" : crowdingRisk.trim().toUpperCase();
        BigDecimal base = switch (risk) {
            case "HIGH" -> new BigDecimal("8.7");
            case "MEDIUM" -> new BigDecimal("5.2");
            default -> new BigDecimal("3.1");
        };
        return base.setScale(1, RoundingMode.HALF_UP);
    }

    private int safeInt(Integer value) {
        return value == null ? 0 : value;
    }

    private BigDecimal safeDecimal(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private BigDecimal abs(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value.abs();
    }
}
