package com.austin.trading.service;

import com.austin.trading.dto.response.KolShadowReportResponse;
import com.austin.trading.entity.CandidateStockEntity;
import com.austin.trading.entity.KolThemeSignalDailySnapshotEntity;
import com.austin.trading.repository.CandidateStockRepository;
import com.austin.trading.repository.KolThemeSignalDailySnapshotRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
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
                "shadow only; production candidate score and final decision are unchanged"
        );
    }

    private BigDecimal abs(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value.abs();
    }
}
