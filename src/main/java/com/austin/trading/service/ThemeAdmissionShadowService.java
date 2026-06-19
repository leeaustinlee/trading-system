package com.austin.trading.service;

import com.austin.trading.engine.ThemeDrivenAdmissionEngine;
import com.austin.trading.entity.HotGroupStockSignalEntity;
import com.austin.trading.entity.ThemeAdmissionShadowDecisionEntity;
import com.austin.trading.repository.HotGroupStockSignalRepository;
import com.austin.trading.repository.ThemeAdmissionShadowDecisionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/** Shadow/read-only persistence adapter for {@link ThemeDrivenAdmissionEngine}. */
@Service
public class ThemeAdmissionShadowService {

    private static final Logger log = LoggerFactory.getLogger(ThemeAdmissionShadowService.class);

    private final HotGroupStockSignalRepository signalRepository;
    private final ThemeAdmissionShadowDecisionRepository decisionRepository;
    private final ThemeDrivenAdmissionEngine engine;

    public ThemeAdmissionShadowService(HotGroupStockSignalRepository signalRepository,
                                       ThemeAdmissionShadowDecisionRepository decisionRepository,
                                       ThemeDrivenAdmissionEngine engine) {
        this.signalRepository = signalRepository;
        this.decisionRepository = decisionRepository;
        this.engine = engine;
    }

    public void safeRebuildForDate(LocalDate tradingDate) {
        try {
            rebuildForDate(tradingDate);
        } catch (Exception ex) {
            log.warn("Theme admission shadow rebuild failed for {}: {}", tradingDate, ex.toString());
        }
    }

    @Transactional
    public int rebuildForDate(LocalDate tradingDate) {
        if (tradingDate == null) {
            return 0;
        }
        List<HotGroupStockSignalEntity> signals = signalRepository.findByTradingDateOrderByRadarRankScoreDesc(tradingDate);
        int saved = 0;
        for (HotGroupStockSignalEntity signal : signals) {
            var result = engine.evaluate(signal);
            String symbol = safe(signal.getSymbol());
            String themeTag = safe(signal.getThemeTag());
            if (symbol.isEmpty() || themeTag.isEmpty()) {
                // The table key requires symbol/theme; pure engine still reports REJECT for these in tests.
                continue;
            }
            ThemeAdmissionShadowDecisionEntity entity = decisionRepository
                    .findByTradingDateAndSymbolAndThemeTagAndSignalId(tradingDate, symbol, themeTag, signal.getId())
                    .or(() -> decisionRepository.findByTradingDateAndSymbolAndThemeTag(tradingDate, symbol, themeTag))
                    .orElseGet(ThemeAdmissionShadowDecisionEntity::new);
            entity.setTradingDate(tradingDate);
            entity.setSymbol(symbol);
            entity.setStockName(signal.getStockName());
            entity.setThemeTag(themeTag);
            entity.setSignalId(signal.getId());
            entity.setSignalRole(signal.getRole());
            entity.setCurrentAction(signal.getCandidateAction());
            entity.setCurrentReason(signal.getRejectionReason());
            entity.setShadowAction(result.action());
            entity.setShadowReason(result.reason());
            entity.setWouldWriteCandidate(result.leaderLike());
            entity.setWouldWriteWatchlist(result.watchlistLike());
            entity.setWouldCreatePullbackPlan(result.pullbackPlan());
            entity.setWouldBypassTopN(result.leaderLike() || result.pullbackPlan());
            entity.setBlockedByCurrentStage(isBlockedByCurrentStage(signal.getCandidateAction(), signal.getRejectionReason())
                    ? "CURRENT_FUNNEL_BLOCKED" : null);
            entity.setDeltaStage(deltaStage(result));
            entity.setAdmissionScore(signal.getRadarRankScore());
            entity.setSignalStrength(signal.getRadarRankScore());
            entity.setNearLimit(result.nearLimit());
            entity.setLimitRisk(String.valueOf(result.limitRisk()));
            entity.setSourceTraceId(signal.getId());
            entity.setEvidenceJson(signal.getEvidenceJson());
            entity.setTraceSource("SHADOW");
            entity.setTraceStatus("ACTIVE");
            decisionRepository.save(entity);
            saved++;
        }
        return saved;
    }

    static String deltaStage(ThemeDrivenAdmissionEngine.ShadowDecision result) {
        if (result == null || result.action() == null) {
            return "NONE";
        }
        return switch (result.action()) {
            case WOULD_ADMIT_CANDIDATE -> "THEME_SIGNAL_TO_CANDIDATE";
            case WOULD_ADMIT_WATCHLIST -> "THEME_SIGNAL_TO_WATCHLIST";
            case WOULD_CREATE_PULLBACK_PLAN -> "LIMIT_RISK_TO_PULLBACK_PLAN";
            default -> "NONE";
        };
    }

    static boolean isBlockedByCurrentStage(String currentAction, String currentReason) {
        String action = safeStatic(currentAction).toUpperCase();
        String reason = safeStatic(currentReason).toUpperCase();
        if (action.isEmpty() && reason.isEmpty()) {
            return false;
        }
        if (action.contains("REJECT") || action.contains("BLOCK") || action.contains("SKIP") || action.contains("MISS")) {
            return true;
        }
        return reason.contains("REJECT") || reason.contains("BLOCK") || reason.contains("VETO") || reason.contains("NOT ")
                || reason.contains("NO ") || reason.contains("FAIL") || reason.contains("SKIP");
    }

    private static String safeStatic(String value) {
        return value == null ? "" : value.trim();
    }

    private String safe(String value) {
        return safeStatic(value);
    }
}
