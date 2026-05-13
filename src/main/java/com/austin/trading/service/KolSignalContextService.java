package com.austin.trading.service;

import com.austin.trading.dto.response.KolThemeContextResponse;
import com.austin.trading.repository.KolThemeSignalDailySnapshotRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

@Service
public class KolSignalContextService {

    public static final String WEAK_SIGNAL_GUARDRAIL =
            "KOL/Podcast theme signals are weak external context only. They are never a BUY signal, "
                    + "must not override risk gates, market grade, vetoes, price gates, chased-high gates, "
                    + "tradability, capital rules, or FinalDecisionEngine live ENTER conditions.";

    private final KolThemeSignalDailySnapshotRepository snapshotRepo;

    public KolSignalContextService(KolThemeSignalDailySnapshotRepository snapshotRepo) {
        this.snapshotRepo = snapshotRepo;
    }

    public KolThemeContextResponse getContext(LocalDate date) {
        var themes = snapshotRepo.findByTradingDateOrderByNetShadowBoostDesc(date).stream()
                .map(e -> new KolThemeContextResponse.ThemeContext(
                        e.getThemeTag(),
                        e.getDirection(),
                        e.getNetShadowBoost(),
                        e.getCrowdingRisk(),
                        e.getSourceCount(),
                        e.getEvidenceCount()
                ))
                .toList();
        return new KolThemeContextResponse(date, WEAK_SIGNAL_GUARDRAIL, themes);
    }
}
