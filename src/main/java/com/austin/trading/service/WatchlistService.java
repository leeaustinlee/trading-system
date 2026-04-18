package com.austin.trading.service;

import com.austin.trading.engine.WatchlistEngine;
import com.austin.trading.engine.WatchlistEngine.*;
import com.austin.trading.entity.WatchlistStockEntity;
import com.austin.trading.repository.PositionRepository;
import com.austin.trading.repository.WatchlistStockRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Watchlist 服務：CRUD + 批次刷新。
 */
@Service
public class WatchlistService {

    private static final Logger log = LoggerFactory.getLogger(WatchlistService.class);

    private final WatchlistStockRepository watchlistRepository;
    private final PositionRepository positionRepository;
    private final WatchlistEngine watchlistEngine;
    private final CooldownService cooldownService;

    public WatchlistService(
            WatchlistStockRepository watchlistRepository,
            PositionRepository positionRepository,
            WatchlistEngine watchlistEngine,
            CooldownService cooldownService
    ) {
        this.watchlistRepository = watchlistRepository;
        this.positionRepository = positionRepository;
        this.watchlistEngine = watchlistEngine;
        this.cooldownService = cooldownService;
    }

    /** 取得所有 TRACKING + READY 的觀察股 */
    public List<WatchlistStockEntity> getCurrentWatchlist() {
        return watchlistRepository.findByWatchStatusInOrderByCurrentScoreDesc(
                List.of("TRACKING", "READY"));
    }

    /** 只取 READY 的股 */
    public List<WatchlistStockEntity> getReadyStocks() {
        return watchlistRepository.findByWatchStatusOrderByConsecutiveStrongDaysDescCurrentScoreDesc("READY");
    }

    /** 取得完整歷史 */
    public List<WatchlistStockEntity> getHistory() {
        return watchlistRepository.findAllByOrderByUpdatedAtDesc();
    }

    /** 手動調整狀態 */
    @Transactional
    public WatchlistStockEntity updateStatus(String symbol, String newStatus) {
        WatchlistStockEntity entity = watchlistRepository.findBySymbol(symbol)
                .orElseThrow(() -> new RuntimeException("Watchlist 中找不到 " + symbol));
        entity.setWatchStatus(newStatus);
        entity.setUpdatedAt(LocalDateTime.now());
        if ("DROPPED".equals(newStatus)) {
            entity.setDroppedAt(LocalDateTime.now());
            entity.setDropReason("手動調整");
        }
        return watchlistRepository.save(entity);
    }

    /**
     * 批次評估並更新 watchlist。
     *
     * @param candidateInputs 今日候選股的評估輸入
     * @return 更新筆數
     */
    @Transactional
    public int batchRefresh(List<CandidateWatchlistInput> candidateInputs, LocalDate tradingDate, String marketGrade) {
        int updated = 0;

        for (CandidateWatchlistInput ci : candidateInputs) {
            try {
                WatchlistStockEntity existing = watchlistRepository.findBySymbol(ci.symbol()).orElse(null);
                boolean isHeld = positionRepository.findTopBySymbolAndStatus(ci.symbol(), "OPEN").isPresent();
                boolean inCooldown = cooldownService.isInCooldown(ci.symbol(), ci.themeTag());

                WatchlistEvaluationInput input = new WatchlistEvaluationInput(
                        ci.symbol(), ci.score(), existing != null ? existing.getHighestScore() : null,
                        ci.themeTag(), ci.themeRank(), ci.finalThemeScore(),
                        existing != null ? existing.getObservationDays() : 0,
                        existing != null ? existing.getConsecutiveStrongDays() : 0,
                        existing != null ? existing.getWatchStatus() : null,
                        ci.isVetoed(), isHeld,
                        existing != null && "ENTERED".equals(existing.getWatchStatus()),
                        ci.isExtended(), ci.momentumStrong(), ci.failedBreakout(), inCooldown,
                        marketGrade
                );

                WatchlistEvaluationResult result = watchlistEngine.evaluate(input);
                applyResult(existing, ci, result, tradingDate);
                updated++;

            } catch (Exception e) {
                log.warn("[WatchlistService] 處理 {} 失敗: {}", ci.symbol(), e.getMessage());
            }
        }

        // 處理今日未出現在 candidate 中的 TRACKING/READY 股 → 視為分數歸零，可能 DROP
        markMissingAsWeakened(candidateInputs, tradingDate);

        log.info("[WatchlistService] 批次刷新完成，更新 {} 筆", updated);
        return updated;
    }

    public record CandidateWatchlistInput(
            String symbol, String stockName, BigDecimal score,
            String themeTag, String sector, Integer themeRank,
            BigDecimal finalThemeScore, String sourceType,
            boolean isVetoed, boolean isExtended,
            boolean momentumStrong, boolean failedBreakout
    ) {}

    // ── 私有方法 ──────────────────────────────────────────────────────────

    private void applyResult(WatchlistStockEntity existing, CandidateWatchlistInput ci,
                             WatchlistEvaluationResult result, LocalDate tradingDate) {
        switch (result.action()) {
            case ADD -> {
                WatchlistStockEntity e = existing != null ? existing : new WatchlistStockEntity();
                e.setSymbol(ci.symbol());
                e.setStockName(ci.stockName());
                e.setThemeTag(ci.themeTag());
                e.setSector(ci.sector());
                e.setSourceType(ci.sourceType());
                e.setCurrentScore(ci.score());
                e.setHighestScore(ci.score());
                e.setWatchStatus("TRACKING");
                e.setFirstSeenDate(tradingDate);
                e.setLastSeenDate(tradingDate);
                e.setObservationDays(1);
                e.setConsecutiveStrongDays(ci.momentumStrong() ? 1 : 0);
                e.setDroppedAt(null);
                e.setDropReason(null);
                e.setUpdatedAt(LocalDateTime.now());
                watchlistRepository.save(e);
            }
            case KEEP -> {
                if (existing == null) return;
                existing.setCurrentScore(ci.score());
                existing.setLastSeenDate(tradingDate);
                existing.setObservationDays(existing.getObservationDays() + 1);
                if (ci.momentumStrong()) {
                    existing.setConsecutiveStrongDays(existing.getConsecutiveStrongDays() + 1);
                } else {
                    existing.setConsecutiveStrongDays(0);
                }
                if (ci.score() != null && (existing.getHighestScore() == null
                        || ci.score().compareTo(existing.getHighestScore()) > 0)) {
                    existing.setHighestScore(ci.score());
                }
                existing.setUpdatedAt(LocalDateTime.now());
                watchlistRepository.save(existing);
            }
            case PROMOTE_READY -> {
                if (existing == null) return;
                existing.setCurrentScore(ci.score());
                existing.setWatchStatus("READY");
                existing.setLastSeenDate(tradingDate);
                existing.setObservationDays(existing.getObservationDays() + 1);
                existing.setConsecutiveStrongDays(existing.getConsecutiveStrongDays() + 1);
                existing.setPromotedAt(LocalDateTime.now());
                existing.setUpdatedAt(LocalDateTime.now());
                watchlistRepository.save(existing);
            }
            case DROP, EXPIRE -> {
                if (existing == null) return;
                existing.setWatchStatus(result.action() == WatchlistAction.EXPIRE ? "EXPIRED" : "DROPPED");
                existing.setDroppedAt(LocalDateTime.now());
                existing.setDropReason(result.reason());
                existing.setUpdatedAt(LocalDateTime.now());
                watchlistRepository.save(existing);
            }
        }
    }

    private void markMissingAsWeakened(List<CandidateWatchlistInput> todayInputs, LocalDate tradingDate) {
        List<String> todaySymbols = todayInputs.stream()
                .map(CandidateWatchlistInput::symbol).toList();

        List<WatchlistStockEntity> active = getCurrentWatchlist();
        for (WatchlistStockEntity w : active) {
            if (!todaySymbols.contains(w.getSymbol())) {
                w.setConsecutiveStrongDays(0);
                w.setObservationDays(w.getObservationDays() + 1);
                w.setUpdatedAt(LocalDateTime.now());

                int maxDays = 10; // 簡化，正式由 engine 判斷
                if (w.getObservationDays() > maxDays && "TRACKING".equals(w.getWatchStatus())) {
                    w.setWatchStatus("EXPIRED");
                    w.setDroppedAt(LocalDateTime.now());
                    w.setDropReason("連續未出現在候選名單，到期移除");
                }
                watchlistRepository.save(w);
            }
        }
    }
}
