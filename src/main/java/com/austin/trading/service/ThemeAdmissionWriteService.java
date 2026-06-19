package com.austin.trading.service;

import com.austin.trading.domain.enums.ThemeAdmissionProductionAction;
import com.austin.trading.dto.internal.ThemeAdmissionWriteSummary;
import com.austin.trading.engine.ThemeDrivenAdmissionEngine;
import com.austin.trading.entity.CandidateStockEntity;
import com.austin.trading.entity.HotGroupStockSignalEntity;
import com.austin.trading.entity.WatchlistStockEntity;
import com.austin.trading.repository.CandidateStockRepository;
import com.austin.trading.repository.HotGroupStockSignalRepository;
import com.austin.trading.repository.WatchlistStockRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Guarded production write adapter for P1-A theme admission. Never creates BUY/SELL/risk/ranking rows. */
@Service
public class ThemeAdmissionWriteService {

    private static final Logger log = LoggerFactory.getLogger(ThemeAdmissionWriteService.class);
    private static final String SOURCE = "THEME_ADMISSION";

    private final HotGroupStockSignalRepository signalRepository;
    private final CandidateStockRepository candidateRepository;
    private final WatchlistStockRepository watchlistRepository;
    private final ThemeDrivenAdmissionEngine engine;
    private final ObjectMapper objectMapper;

    public ThemeAdmissionWriteService(HotGroupStockSignalRepository signalRepository,
                                      CandidateStockRepository candidateRepository,
                                      WatchlistStockRepository watchlistRepository,
                                      ThemeDrivenAdmissionEngine engine,
                                      ObjectMapper objectMapper) {
        this.signalRepository = signalRepository;
        this.candidateRepository = candidateRepository;
        this.watchlistRepository = watchlistRepository;
        this.engine = engine;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ThemeAdmissionWriteSummary rebuildForDate(LocalDate tradingDate) {
        if (tradingDate == null) {
            return ThemeAdmissionWriteSummary.empty();
        }
        Counters counters = new Counters();
        List<HotGroupStockSignalEntity> signals = signalRepository.findByTradingDateOrderByRadarRankScoreDesc(tradingDate);
        for (HotGroupStockSignalEntity signal : signals) {
            counters.processedSignals++;
            boolean existingCandidate = candidateRepository.findByTradingDateAndSymbol(tradingDate, safe(signal.getSymbol())).isPresent();
            boolean existingWatchlist = watchlistRepository.findBySymbol(safe(signal.getSymbol())).isPresent();
            var decision = engine.evaluateProduction(new ThemeDrivenAdmissionEngine.ProductionInput(
                    signal,
                    existingCandidate,
                    existingWatchlist,
                    isSuspended(signal),
                    noPrice(signal),
                    liquidityPass(signal)
            ));
            applyDecision(counters, signal, decision);
        }
        return counters.toSummary();
    }

    private void applyDecision(Counters counters,
                               HotGroupStockSignalEntity signal,
                               ThemeDrivenAdmissionEngine.ProductionDecision decision) {
        ThemeAdmissionProductionAction action = decision.action();
        switch (action) {
            case ADMIT_CANDIDATE -> writeCandidate(counters, signal, decision.reason());
            case ADMIT_WATCHLIST -> writeWatchlist(counters, signal, decision.reason());
            case SKIP_ALREADY_EXISTS -> counters.skippedAlreadyExists++;
            case SKIP_LIMIT_RISK -> counters.skippedLimitRisk++;
            case REJECT_BAD_DATA -> counters.rejectedBadData++;
            case REJECT_LIQUIDITY -> counters.rejectedLiquidity++;
            case REJECT_WEAK_THEME -> counters.rejectedWeakTheme++;
            case SHADOW_ONLY -> counters.shadowOnly++;
        }
    }

    private void writeCandidate(Counters counters, HotGroupStockSignalEntity signal, String reason) {
        try {
            CandidateStockEntity entity = new CandidateStockEntity();
            entity.setTradingDate(signal.getTradingDate());
            entity.setSymbol(safe(signal.getSymbol()));
            entity.setStockName(signal.getStockName());
            entity.setScore(signal.getRadarRankScore());
            entity.setReason(reason);
            entity.setThemeTag(signal.getThemeTag());
            entity.setCandidateRole(signal.getRole());
            entity.setThemeImportanceScore(signal.getRadarRankScore());
            entity.setTradableScore(signal.getRadarRankScore());
            entity.setShadowRankScore(signal.getRadarRankScore());
            entity.setIsThemeLeader(true);
            entity.setLeaderTradable(true);
            entity.setThemeTraceId("theme-admission-signal-" + signal.getId());
            entity.setPayloadJson(payload(signal, "THEME_GUARANTEED", reason, "TRADE_CANDIDATE"));
            candidateRepository.save(entity);
            counters.admittedCandidates++;
        } catch (Exception ex) {
            counters.rejectedBadData++;
            log.warn("Theme admission candidate write failed for {} {}: {}",
                    signal.getTradingDate(), signal.getSymbol(), ex.toString());
        }
    }

    private void writeWatchlist(Counters counters, HotGroupStockSignalEntity signal, String reason) {
        try {
            WatchlistStockEntity entity = new WatchlistStockEntity();
            entity.setSymbol(safe(signal.getSymbol()));
            entity.setStockName(signal.getStockName());
            entity.setThemeTag(signal.getThemeTag());
            entity.setSourceType(SOURCE);
            entity.setCurrentScore(signal.getRadarRankScore());
            entity.setHighestScore(signal.getRadarRankScore());
            entity.setWatchStatus("TRACKING");
            entity.setFirstSeenDate(signal.getTradingDate());
            entity.setLastSeenDate(signal.getTradingDate());
            entity.setObservationDays(1);
            entity.setConsecutiveStrongDays(0);
            entity.setNotes(reason);
            entity.setPayloadJson(payload(signal, "THEME_SECOND_LEADER", reason, "WATCH_ONLY"));
            entity.setStrategyType("SETUP");
            entity.setUpdatedAt(LocalDateTime.now());
            watchlistRepository.save(entity);
            counters.admittedWatchlists++;
        } catch (Exception ex) {
            counters.rejectedBadData++;
            log.warn("Theme admission watchlist write failed for {} {}: {}",
                    signal.getTradingDate(), signal.getSymbol(), ex.toString());
        }
    }

    private boolean liquidityPass(HotGroupStockSignalEntity signal) {
        return signal != null && signal.getTurnoverYi() != null && signal.getTurnoverYi().signum() > 0;
    }

    private boolean noPrice(HotGroupStockSignalEntity signal) {
        return signal == null || signal.getChangePct() == null;
    }

    private boolean isSuspended(HotGroupStockSignalEntity signal) {
        String tag = signal == null || signal.getTradabilityTag() == null ? "" : signal.getTradabilityTag().toUpperCase(Locale.ROOT);
        return tag.contains("SUSPEND") || tag.contains("HALT");
    }

    private String payload(HotGroupStockSignalEntity signal, String admissionType, String reason, String tradabilityTag) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("admission_type", admissionType);
        payload.put("admissionReason", reason);
        payload.put("source_signal_id", signal.getId());
        payload.put("theme_role", signal.getRole());
        payload.put("tradability_tag", tradabilityTag);
        payload.put("source", SOURCE);
        payload.put("sourcePhase", signal.getSourcePhase());
        payload.put("themeTag", signal.getThemeTag());
        payload.put("shadowOnly", false);
        payload.put("productionBuyImpact", false);
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            return "{\"source\":\"THEME_ADMISSION\",\"productionBuyImpact\":false}";
        }
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static class Counters {
        int processedSignals;
        int admittedCandidates;
        int admittedWatchlists;
        int skippedLimitRisk;
        int skippedAlreadyExists;
        int rejectedBadData;
        int rejectedLiquidity;
        int rejectedWeakTheme;
        int shadowOnly;

        ThemeAdmissionWriteSummary toSummary() {
            return new ThemeAdmissionWriteSummary(processedSignals, admittedCandidates, admittedWatchlists,
                    skippedLimitRisk, skippedAlreadyExists, rejectedBadData, rejectedLiquidity,
                    rejectedWeakTheme, shadowOnly, false);
        }
    }
}
