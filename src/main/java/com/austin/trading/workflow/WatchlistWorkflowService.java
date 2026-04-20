package com.austin.trading.workflow;

import com.austin.trading.dto.response.CandidateResponse;
import com.austin.trading.entity.ThemeSnapshotEntity;
import com.austin.trading.engine.ThemeSelectionEngine;
import com.austin.trading.notify.LineTemplateService;
import com.austin.trading.dto.response.MarketCurrentResponse;
import com.austin.trading.service.CandidateScanService;
import com.austin.trading.service.MarketDataService;
import com.austin.trading.service.ScoreConfigService;
import com.austin.trading.service.WatchlistService;
import com.austin.trading.service.WatchlistService.CandidateWatchlistInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 盤後 Watchlist 刷新工作流。
 */
@Service
public class WatchlistWorkflowService {

    private static final Logger log = LoggerFactory.getLogger(WatchlistWorkflowService.class);

    private final CandidateScanService candidateScanService;
    private final ThemeSelectionEngine themeSelectionEngine;
    private final WatchlistService watchlistService;
    private final MarketDataService marketDataService;
    private final LineTemplateService lineTemplateService;
    private final ScoreConfigService config;

    public WatchlistWorkflowService(
            CandidateScanService candidateScanService,
            ThemeSelectionEngine themeSelectionEngine,
            WatchlistService watchlistService,
            MarketDataService marketDataService,
            LineTemplateService lineTemplateService,
            ScoreConfigService config
    ) {
        this.candidateScanService = candidateScanService;
        this.themeSelectionEngine = themeSelectionEngine;
        this.watchlistService = watchlistService;
        this.marketDataService = marketDataService;
        this.lineTemplateService = lineTemplateService;
        this.config = config;
    }

    public void execute(LocalDate tradingDate) {
        log.info("[WatchlistWorkflow] 開始 tradingDate={}", tradingDate);

        // Step 1: 讀取今日候選股
        int scanMax = config.getInt("candidate.scan.maxCount", 8);
        List<CandidateResponse> candidates = candidateScanService.getCandidatesByDate(tradingDate, scanMax);
        if (candidates.isEmpty()) {
            candidates = candidateScanService.getCurrentCandidates(scanMax);
        }

        // Step 2: 讀取題材排名
        List<ThemeSnapshotEntity> themes = themeSelectionEngine.getRankedThemes(tradingDate);
        Map<String, Integer> themeRankMap = new java.util.HashMap<>();
        Map<String, BigDecimal> themeScoreMap = new java.util.HashMap<>();
        for (int i = 0; i < themes.size(); i++) {
            ThemeSnapshotEntity t = themes.get(i);
            themeRankMap.put(t.getThemeTag(), i + 1);
            themeScoreMap.put(t.getThemeTag(), t.getFinalThemeScore());
        }

        // Step 3: 轉換成 WatchlistInput
        BigDecimal minScore = config.getDecimal("watchlist.min_score_to_track", new BigDecimal("6.0"));
        List<CandidateWatchlistInput> inputs = new ArrayList<>();

        for (CandidateResponse c : candidates) {
            BigDecimal score = c.finalRankScore() != null ? c.finalRankScore()
                    : (c.score() != null ? c.score() : BigDecimal.ZERO);
            if (score.compareTo(minScore) < 0) continue;

            String theme = c.themeTag();
            Integer rank = theme != null ? themeRankMap.get(theme) : null;
            BigDecimal themeScore = theme != null ? themeScoreMap.get(theme) : null;

            inputs.add(new CandidateWatchlistInput(
                    c.symbol(), c.stockName(), score,
                    theme, c.sector(), rank, themeScore,
                    "CANDIDATE",
                    Boolean.TRUE.equals(c.isVetoed()),
                    false, // isExtended — v1 預設 false，後續整合
                    true,  // momentumStrong — 有在候選名單就視為有動能
                    false  // failedBreakout
            ));
        }

        // Step 4: 批次刷新（帶入市場等級）
        String marketGrade = marketDataService.getCurrentMarket()
                .map(MarketCurrentResponse::marketGrade).orElse("B");
        int updated = watchlistService.batchRefresh(inputs, tradingDate, marketGrade);

        // Step 5: 通知
        boolean lineEnabled = config.getBoolean("scheduling.line_notify_enabled", false);
        if (lineEnabled) {
            var readyList = watchlistService.getReadyStocks();
            if (!readyList.isEmpty()) {
                String readyText = readyList.stream()
                        .map(w -> "  " + w.getSymbol() + " " + (w.getStockName() != null ? w.getStockName() : "")
                                + " (連續" + w.getConsecutiveStrongDays() + "天)")
                        .collect(Collectors.joining("\n"));
                lineTemplateService.notifySystemAlert("Watchlist READY 更新",
                        "READY 名單：\n" + readyText + "\n來源：Trading System");
            }
        }

        log.info("[WatchlistWorkflow] 完成，更新 {} 筆", updated);
    }
}
