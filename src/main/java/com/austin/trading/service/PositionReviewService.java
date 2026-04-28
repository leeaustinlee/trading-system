package com.austin.trading.service;

import com.austin.trading.dto.internal.MarketRegimeDecision;
import com.austin.trading.dto.internal.ThemeStrengthDecision;
import com.austin.trading.dto.request.PositionCloseRequest;
import com.austin.trading.dto.response.LiveQuoteResponse;
import com.austin.trading.engine.ExitRegimeIntegrationEngine;
import com.austin.trading.engine.PositionDecisionEngine;
import com.austin.trading.engine.PositionDecisionEngine.*;
import com.austin.trading.engine.StopLossTakeProfitEngine;
import com.austin.trading.entity.PaperTradeEntity;
import com.austin.trading.entity.PositionEntity;
import com.austin.trading.entity.PositionReviewLogEntity;
import com.austin.trading.notify.LineSender;
import com.austin.trading.repository.PaperTradeRepository;
import com.austin.trading.repository.PositionReviewLogRepository;
import com.austin.trading.repository.PositionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * 持倉審查服務。
 * <p>整合 PositionDecisionEngine + 即時報價 + DB 讀寫。</p>
 */
@Service
public class PositionReviewService {

    private static final Logger log = LoggerFactory.getLogger(PositionReviewService.class);

    private final PositionRepository             positionRepository;
    private final PositionReviewLogRepository    reviewLogRepository;
    private final PositionDecisionEngine         positionDecisionEngine;
    private final ExitRegimeIntegrationEngine    exitRegimeEngine;
    private final CandidateScanService           candidateScanService;
    private final StopLossTakeProfitEngine       stopLossTakeProfitEngine;
    private final ScoreConfigService             scoreConfigService;
    private final MarketRegimeService            marketRegimeService;
    private final ThemeStrengthService           themeStrengthService;

    /** P0.2: review = EXIT 時送 LINE 警示。LineSender 已在 disabled / 429 時自行 graceful。 */
    private final ObjectProvider<LineSender>     lineSenderProvider;

    /** P0.3: review = EXIT 時自動平倉（先寫 paper_trade，shadow window 結束後才動真倉）。 */
    private final ObjectProvider<PaperTradeService>      paperTradeServiceProvider;
    private final ObjectProvider<PaperTradeRepository>   paperTradeRepositoryProvider;
    private final ObjectProvider<PositionService>        positionServiceProvider;

    public PositionReviewService(
            PositionRepository positionRepository,
            PositionReviewLogRepository reviewLogRepository,
            PositionDecisionEngine positionDecisionEngine,
            ExitRegimeIntegrationEngine exitRegimeEngine,
            CandidateScanService candidateScanService,
            StopLossTakeProfitEngine stopLossTakeProfitEngine,
            ScoreConfigService scoreConfigService,
            MarketRegimeService marketRegimeService,
            ThemeStrengthService themeStrengthService,
            ObjectProvider<LineSender> lineSenderProvider
    ) {
        this(positionRepository, reviewLogRepository, positionDecisionEngine, exitRegimeEngine,
                candidateScanService, stopLossTakeProfitEngine, scoreConfigService,
                marketRegimeService, themeStrengthService, lineSenderProvider, null, null, null);
    }

    /**
     * P0.3 全參數建構子：注入 paper-trade / real-position 自動平倉 collaborators。
     *
     * <p>這些 collaborators 用 ObjectProvider 包起來，主要原因：
     * (1) 解 PaperTradeService → ScoreConfigService 等既有 ObjectProvider 鏈的初始化順序；
     * (2) 既有測試可繼續用上面那個短建構子，不需要一口氣補 mock。</p>
     */
    @org.springframework.beans.factory.annotation.Autowired
    public PositionReviewService(
            PositionRepository positionRepository,
            PositionReviewLogRepository reviewLogRepository,
            PositionDecisionEngine positionDecisionEngine,
            ExitRegimeIntegrationEngine exitRegimeEngine,
            CandidateScanService candidateScanService,
            StopLossTakeProfitEngine stopLossTakeProfitEngine,
            ScoreConfigService scoreConfigService,
            MarketRegimeService marketRegimeService,
            ThemeStrengthService themeStrengthService,
            ObjectProvider<LineSender> lineSenderProvider,
            ObjectProvider<PaperTradeService> paperTradeServiceProvider,
            ObjectProvider<PaperTradeRepository> paperTradeRepositoryProvider,
            ObjectProvider<PositionService> positionServiceProvider
    ) {
        this.positionRepository   = positionRepository;
        this.reviewLogRepository  = reviewLogRepository;
        this.positionDecisionEngine = positionDecisionEngine;
        this.exitRegimeEngine     = exitRegimeEngine;
        this.candidateScanService  = candidateScanService;
        this.stopLossTakeProfitEngine = stopLossTakeProfitEngine;
        this.scoreConfigService   = scoreConfigService;
        this.marketRegimeService  = marketRegimeService;
        this.themeStrengthService = themeStrengthService;
        this.lineSenderProvider   = lineSenderProvider;
        this.paperTradeServiceProvider    = paperTradeServiceProvider;
        this.paperTradeRepositoryProvider = paperTradeRepositoryProvider;
        this.positionServiceProvider      = positionServiceProvider;
    }

    /**
     * 審查所有 OPEN positions。
     *
     * @param reviewType DAILY / INTRADAY
     * @return 每筆 position 的審查結果
     */
    @Transactional
    public List<ReviewResult> reviewAllOpenPositions(String reviewType) {
        List<PositionEntity> openPositions = positionRepository.findByStatus("OPEN");
        if (openPositions.isEmpty()) return List.of();

        List<String> symbols = openPositions.stream().map(PositionEntity::getSymbol).distinct().toList();
        List<LiveQuoteResponse> quotes = candidateScanService.getLiveQuotesBySymbols(symbols);

        // P2.3: load current regime and theme strength once for all positions
        MarketRegimeDecision regime    = marketRegimeService.getLatestForToday().orElse(null);
        String               regimeType = regime != null ? regime.regimeType() : null;
        LocalDate            today     = LocalDate.now();

        List<ReviewResult> results = new ArrayList<>();
        for (PositionEntity pos : openPositions) {
            try {
                LiveQuoteResponse quote = quotes.stream()
                        .filter(q -> q.symbol().equals(pos.getSymbol()))
                        .findFirst().orElse(null);

                // 即時報價不可用（盤外 / TWSE MIS 失敗）→ 只寫 review log，不做任何決策行為
                boolean quoteAvailable = quote != null && quote.currentPrice() != null;
                PositionDecisionResult decision = quoteAvailable
                        ? evaluatePosition(pos, quote)
                        : new PositionDecisionResult(PositionStatus.HOLD,
                                "即時報價不可用，review 停用", null,
                                com.austin.trading.engine.PositionDecisionEngine.TrailingAction.NONE);

                // P2.3: apply regime/theme decay override
                ThemeStrengthDecision themeDecision =
                        themeStrengthService.findForSymbol(pos.getSymbol(), today).orElse(null);
                String themeStage = themeDecision != null ? themeDecision.themeStage() : null;
                PositionDecisionResult overridden = exitRegimeEngine.applyOverride(decision, regimeType, themeStage);
                if (overridden != decision) {
                    log.info("[PositionReview] P2.3 override {} {} → {} (regime={} theme={})",
                            pos.getSymbol(), decision.status(), overridden.status(),
                            regimeType, themeStage);
                    decision = overridden;
                }

                PositionReviewLogEntity logEntry = saveReviewLog(pos, quote, decision, reviewType);

                // P0.2 EXIT alert:review = EXIT 時送 LINE,讓使用者察覺持倉訊號
                maybeSendExitAlert(pos, decision);

                // P0.3 EXIT 自動平倉：先寫 paper_trade（shadow），可選真倉。
                // 必須在 setReviewStatus 之前呼叫，因為 transition gate 用「上一輪 reviewStatus」判斷。
                maybeAutoClosePosition(pos, decision, quote);

                // 更新 position 狀態
                pos.setReviewStatus(decision.status().name());
                backfillMissingTargets(pos);
                pos.setLastReviewedAt(LocalDateTime.now());
                pos.setUpdatedAt(LocalDateTime.now());

                // 只在有即時報價且 TRAIL_UP 時才更新 trailing stop（避免盤外 / stale quote 汙染）
                if (quoteAvailable && decision.status() == PositionStatus.TRAIL_UP
                        && decision.suggestedStopLoss() != null) {
                    pos.setTrailingStopPrice(decision.suggestedStopLoss());
                }

                positionRepository.save(pos);
                results.add(new ReviewResult(
                        pos,
                        decision,
                        logEntry.getId(),
                        logEntry.getCurrentPrice(),
                        logEntry.getPnlPct()
                ));

            } catch (Exception e) {
                log.warn("[PositionReview] 審查失敗 symbol={}: {}", pos.getSymbol(), e.getMessage());
            }
        }
        return results;
    }

    private void backfillMissingTargets(PositionEntity pos) {
        if (pos.getAvgCost() == null || pos.getAvgCost().signum() <= 0) return;
        if (pos.getStopLossPrice() != null && pos.getTakeProfit1() != null && pos.getTakeProfit2() != null) return;

        boolean momentum = "MOMENTUM_CHASE".equalsIgnoreCase(pos.getStrategyType());
        BigDecimal stopLossPct = momentum
                ? scoreConfigService.getDecimal("momentum.stop_loss_pct", new BigDecimal("-0.025")).abs()
                : new BigDecimal("6.0");
        BigDecimal takeProfit1Pct = momentum
                ? scoreConfigService.getDecimal("momentum.take_profit_1_pct", new BigDecimal("0.06"))
                .multiply(new BigDecimal("100"))
                : new BigDecimal("8.0");
        BigDecimal takeProfit2Pct = momentum
                ? scoreConfigService.getDecimal("momentum.take_profit_2_pct", new BigDecimal("0.10"))
                .multiply(new BigDecimal("100"))
                : new BigDecimal("13.0");

        var suggestion = stopLossTakeProfitEngine.evaluate(
                new com.austin.trading.dto.request.StopLossTakeProfitEvaluateRequest(
                        pos.getAvgCost().doubleValue(),
                        stopLossPct.doubleValue(),
                        takeProfit1Pct.doubleValue(),
                        takeProfit2Pct.doubleValue(),
                        false
                )
        );

        if (pos.getStopLossPrice() == null) {
            pos.setStopLossPrice(BigDecimal.valueOf(suggestion.stopLossPrice()));
        }
        if (pos.getTakeProfit1() == null) {
            pos.setTakeProfit1(BigDecimal.valueOf(suggestion.takeProfit1()));
        }
        if (pos.getTakeProfit2() == null) {
            pos.setTakeProfit2(BigDecimal.valueOf(suggestion.takeProfit2()));
        }
        pos.setNote(appendSystemSuggestionNote(
                pos.getNote(),
                momentum ? "系統已依追價策略自動補上停損/停利"
                        : "系統已依一般策略自動補上停損/停利"));
    }

    private String appendSystemSuggestionNote(String note, String marker) {
        if (note == null || note.isBlank()) return marker;
        if (note.contains(marker)) return note;
        return note + "\n" + marker;
    }

    public record ReviewResult(
            PositionEntity position,
            PositionDecisionResult decision,
            Long reviewLogId,
            BigDecimal currentPrice,
            BigDecimal pnlPct
    ) {}

    // ── 私有方法 ──────────────────────────────────────────────────────────

    private PositionDecisionResult evaluatePosition(PositionEntity pos, LiveQuoteResponse quote) {
        BigDecimal currentPrice = quote != null && quote.currentPrice() != null
                ? BigDecimal.valueOf(quote.currentPrice()) : null;
        BigDecimal dayHigh = quote != null && quote.dayHigh() != null
                ? BigDecimal.valueOf(quote.dayHigh()) : null;
        BigDecimal dayLow = quote != null && quote.dayLow() != null
                ? BigDecimal.valueOf(quote.dayLow()) : null;
        BigDecimal prevClose = quote != null && quote.prevClose() != null
                ? BigDecimal.valueOf(quote.prevClose()) : null;

        BigDecimal pnlPct = BigDecimal.ZERO;
        if (currentPrice != null && pos.getAvgCost() != null && pos.getAvgCost().signum() > 0) {
            pnlPct = currentPrice.subtract(pos.getAvgCost())
                    .divide(pos.getAvgCost(), 4, RoundingMode.HALF_UP)
                    .multiply(new BigDecimal("100"));
        }

        int holdingDays = pos.getOpenedAt() != null
                ? (int) ChronoUnit.DAYS.between(pos.getOpenedAt().toLocalDate(), LocalDate.now())
                : 0;

        // sessionHighPrice: v1 用 dayHigh 近似，後續可從歷史報價取持倉期間最高
        BigDecimal sessionHigh = dayHigh;

        // 目前 v1：旗標都設 false，後續由外部資料補充
        PositionDecisionInput input = new PositionDecisionInput(
                pos.getSymbol(),
                pos.getAvgCost(),
                pos.getStopLossPrice(),
                pos.getTakeProfit1(),
                pos.getTakeProfit2(),
                pos.getTrailingStopPrice(),
                pos.getSide(),
                holdingDays,
                currentPrice,
                dayHigh,
                dayLow,
                prevClose,
                sessionHigh,    // sessionHighPrice
                "B",            // 預設 B 級，後續整合時從 market snapshot 取
                null,           // themeRank - 後續整合
                null,           // finalThemeScore - 後續整合
                pnlPct,
                ExtendedLevel.NONE,
                false,          // volumeWeakening
                false,          // failedBreakout
                holdingDays <= 3 || (currentPrice != null && pos.getAvgCost() != null
                        && currentPrice.compareTo(pos.getAvgCost()) > 0),  // momentumStrong 簡化
                false,          // nearResistance
                false           // madeNewHighRecently
        );

        return positionDecisionEngine.evaluate(input);
    }

    /**
     * P0.2:Review status = EXIT 時發送 LINE 警示。
     * 透過 {@code position.review.exit_alert.enabled}(預設 TRUE)控制,LineSender 自身對 disabled / 429 已 graceful。
     *
     * <p><b>Dedupe 規則（reviewer C.1 BLOCKER 修正）：只在「上一輪非 EXIT、本輪 EXIT」的
     * transition 才送 LINE。盤中 5-minute monitor 每輪都會跑 reviewAllOpenPositions，若不 dedupe
     * 一檔停損會被連送 ~54 通；本邏輯確保同一個 EXIT 只送一次，使用者人工處理（出場或重置 reviewStatus）
     * 後若再次 EXIT 才會再送。</b></p>
     */
    void maybeSendExitAlert(PositionEntity pos, PositionDecisionResult decision) {
        if (decision == null || decision.status() != PositionStatus.EXIT) return;
        boolean alertEnabled = scoreConfigService == null
                || scoreConfigService.getBoolean("position.review.exit_alert.enabled", true);
        if (!alertEnabled) return;

        // Dedupe：只在 prev != EXIT && curr == EXIT 的 transition 才送（避免每 5 分鐘重複 spam）
        String prevStatus = pos.getReviewStatus();
        if ("EXIT".equalsIgnoreCase(prevStatus)) {
            log.debug("[PositionReview] EXIT alert skipped (already EXIT) symbol={}", pos.getSymbol());
            return;
        }

        LineSender sender = lineSenderProvider != null ? lineSenderProvider.getIfAvailable() : null;
        if (sender == null) return;
        String reason = decision.reason() != null ? decision.reason() : "EXIT signal";
        String msg = "⚠️ 持倉 " + pos.getSymbol() + " review = EXIT, reason: " + reason;
        try {
            sender.send(msg);
        } catch (Exception e) {
            // Defensive:LineSender 內部已 catch,但這裡仍多一層,絕不讓 LINE 送失敗影響 review 流程
            log.warn("[PositionReview] EXIT alert send failed symbol={}: {}", pos.getSymbol(), e.getMessage());
        }
    }

    /**
     * P0.3:Review status 從 非EXIT → EXIT 轉換時，自動平倉處理。
     *
     * <p>過去 30 天 position_review_log 累積 160+ 筆 EXIT 訊號但都沒有自動平倉動作；
     * 這個 handler 把 EXIT 訊號真的接出去。</p>
     *
     * <p>Transition gate 沿用 {@link #maybeSendExitAlert} 的 dedupe pattern：只有
     * {@code prev != EXIT && curr == EXIT} 時才動作。盤中 5-minute monitor 會持續跑
     * reviewAllOpenPositions，若不 dedupe 同一個 EXIT 會被反覆觸發 → 反覆寫 paper_trade
     * 與 LINE alert，所以 transition gate 是必須的。</p>
     *
     * <p>動作順序（feature flag 預設皆 TRUE）：
     * <ol>
     *   <li>{@code position.review.auto_close.enabled = false} → 直接 return（緊急 kill switch）。</li>
     *   <li>找出對應的 OPEN paper_trade（symbol 相同），呼叫
     *       {@link PaperTradeService#closeTradeFromAutoExit}，{@code exit_reason="POSITION_REVIEW_EXIT"}，
     *       exit_price 取本輪 live quote。</li>
     *   <li>{@code position.review.auto_close.paper_only = true}（預設）→ 不動真倉，
     *       只送 LINE「[Auto-close shadow] would have closed XXXX」。</li>
     *   <li>{@code paper_only = false} → 同步呼叫 {@link PositionService#close} 平真倉，
     *       LINE 送成功通知。</li>
     * </ol>
     * </p>
     *
     * <p>所有失敗（找不到 paper trade、LINE 失敗、PositionService close 失敗）都只 warn log，
     * 不向上拋；review 主流程不能被自動平倉的 best-effort 動作打斷。</p>
     */
    void maybeAutoClosePosition(PositionEntity pos, PositionDecisionResult decision, LiveQuoteResponse quote) {
        if (decision == null || decision.status() != PositionStatus.EXIT) return;
        if (!"OPEN".equalsIgnoreCase(pos.getStatus())) {
            log.debug("[PositionReview] auto-close skipped (position not OPEN) symbol={} status={}",
                    pos.getSymbol(), pos.getStatus());
            return;
        }

        // Feature flag (kill switch)
        boolean autoCloseEnabled = scoreConfigService == null
                || scoreConfigService.getBoolean("position.review.auto_close.enabled", true);
        if (!autoCloseEnabled) {
            log.debug("[PositionReview] auto-close disabled by flag symbol={}", pos.getSymbol());
            return;
        }

        // Transition gate（沿用 maybeSendExitAlert 的 dedupe pattern）
        String prevStatus = pos.getReviewStatus();
        if ("EXIT".equalsIgnoreCase(prevStatus)) {
            log.debug("[PositionReview] auto-close skipped (already EXIT) symbol={}", pos.getSymbol());
            return;
        }

        // 取本輪 live quote 作為 exit price
        BigDecimal exitPrice = (quote != null && quote.currentPrice() != null)
                ? BigDecimal.valueOf(quote.currentPrice()) : null;
        if (exitPrice == null || exitPrice.signum() <= 0) {
            log.warn("[PositionReview] auto-close skipped (no live quote) symbol={}", pos.getSymbol());
            return;
        }

        boolean paperOnly = scoreConfigService == null
                || scoreConfigService.getBoolean("position.review.auto_close.paper_only", true);

        // (1) Mirror 到 paper_trade
        boolean paperClosed = mirrorToPaperTrade(pos, decision, exitPrice);

        // (2) 視 flag 決定動真倉
        if (paperOnly) {
            sendShadowLineAlert(pos, paperClosed);
        } else {
            boolean realClosed = closeRealPosition(pos, decision, exitPrice);
            sendRealCloseLineAlert(pos, realClosed, paperClosed);
        }
    }

    private boolean mirrorToPaperTrade(PositionEntity pos, PositionDecisionResult decision, BigDecimal exitPrice) {
        PaperTradeService paperSvc = paperTradeServiceProvider != null
                ? paperTradeServiceProvider.getIfAvailable() : null;
        PaperTradeRepository paperRepo = paperTradeRepositoryProvider != null
                ? paperTradeRepositoryProvider.getIfAvailable() : null;
        if (paperSvc == null || paperRepo == null) {
            log.warn("[PositionReview] auto-close paper-mirror skipped (paperTradeService/Repo unavailable) symbol={}",
                    pos.getSymbol());
            return false;
        }
        try {
            List<PaperTradeEntity> openTrades = paperRepo.findBySymbolAndStatusOrderByEntryDateAscIdAsc(
                    pos.getSymbol(), "OPEN");
            if (openTrades == null || openTrades.isEmpty()) {
                log.info("[PositionReview] auto-close: no matching OPEN paper_trade for symbol={} (continuing)",
                        pos.getSymbol());
                return false;
            }
            // 同 symbol 多筆 OPEN：平最早一筆，避免一次掃多筆造成 audit 困難。
            PaperTradeEntity trade = openTrades.get(0);
            String reasonDetail = decision.reason() != null ? decision.reason() : "review=EXIT";
            String detailJson = "{\"trigger\":\"POSITION_REVIEW_EXIT\",\"reviewReason\":\""
                    + escapeJson(reasonDetail) + "\"}";
            PaperTradeService.ExitResult result = new PaperTradeService.ExitResult(
                    "POSITION_REVIEW_EXIT", exitPrice, /*triggerPriority=*/ 5, detailJson);
            paperSvc.closeTradeFromAutoExit(trade, result);
            log.info("[PositionReview] auto-close paper_trade CLOSE id={} symbol={} exit={} reason=POSITION_REVIEW_EXIT",
                    trade.getId(), trade.getSymbol(), exitPrice);
            return true;
        } catch (Exception e) {
            log.warn("[PositionReview] auto-close paper-mirror failed symbol={}: {}",
                    pos.getSymbol(), e.getMessage());
            return false;
        }
    }

    private boolean closeRealPosition(PositionEntity pos, PositionDecisionResult decision, BigDecimal exitPrice) {
        PositionService posSvc = positionServiceProvider != null
                ? positionServiceProvider.getIfAvailable() : null;
        if (posSvc == null) {
            log.warn("[PositionReview] auto-close real-close skipped (PositionService unavailable) symbol={}",
                    pos.getSymbol());
            return false;
        }
        try {
            String exitReason = "POSITION_REVIEW_EXIT";
            String note = "Auto-closed by PositionReviewExitAutoCloseHandler — "
                    + (decision.reason() != null ? decision.reason() : "review=EXIT");
            posSvc.close(pos.getId(), new PositionCloseRequest(
                    exitPrice, LocalDateTime.now(), exitReason, note));
            log.info("[PositionReview] auto-close real position CLOSE id={} symbol={} exit={} reason={}",
                    pos.getId(), pos.getSymbol(), exitPrice, exitReason);
            return true;
        } catch (Exception e) {
            log.warn("[PositionReview] auto-close real-close failed symbol={} id={}: {}",
                    pos.getSymbol(), pos.getId(), e.getMessage());
            return false;
        }
    }

    private void sendShadowLineAlert(PositionEntity pos, boolean paperClosed) {
        LineSender sender = lineSenderProvider != null ? lineSenderProvider.getIfAvailable() : null;
        if (sender == null) return;
        String tail = paperClosed ? "; paper recorded" : "; no matching paper trade";
        String msg = "[Auto-close shadow] symbol " + pos.getSymbol()
                + " would have closed real position" + tail;
        try {
            sender.send(msg);
        } catch (Exception e) {
            log.warn("[PositionReview] auto-close shadow LINE alert failed symbol={}: {}",
                    pos.getSymbol(), e.getMessage());
        }
    }

    private void sendRealCloseLineAlert(PositionEntity pos, boolean realClosed, boolean paperClosed) {
        LineSender sender = lineSenderProvider != null ? lineSenderProvider.getIfAvailable() : null;
        if (sender == null) return;
        String header = realClosed
                ? "[Auto-close OK] symbol " + pos.getSymbol() + " 已自動平倉"
                : "[Auto-close FAIL] symbol " + pos.getSymbol() + " 真倉平倉失敗，請手動確認";
        String tail = paperClosed ? "（paper 同步紀錄）" : "（無對應 paper trade）";
        try {
            sender.send(header + tail);
        } catch (Exception e) {
            log.warn("[PositionReview] auto-close real LINE alert failed symbol={}: {}",
                    pos.getSymbol(), e.getMessage());
        }
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
    }

    /**
     * P0.2:列出仍在 OPEN、且最新一筆 review 為 EXIT 的持倉。
     * 由 {@code GET /api/positions/review/pending-exits} 使用。
     */
    @Transactional(readOnly = true)
    public List<PendingExitItem> findPendingExits() {
        List<PositionEntity> openPositions = positionRepository.findByStatus("OPEN");
        List<PendingExitItem> items = new ArrayList<>();
        for (PositionEntity pos : openPositions) {
            reviewLogRepository.findTopByPositionIdOrderByCreatedAtDesc(pos.getId())
                    .filter(r -> "EXIT".equalsIgnoreCase(r.getDecisionStatus()))
                    .ifPresent(r -> items.add(new PendingExitItem(
                            pos.getSymbol(),
                            r.getReason(),
                            r.getCreatedAt() != null ? r.getCreatedAt() : null
                    )));
        }
        return items;
    }

    public record PendingExitItem(
            String symbol,
            String reason,
            LocalDateTime reviewedAt
    ) {}

    private PositionReviewLogEntity saveReviewLog(
            PositionEntity pos, LiveQuoteResponse quote,
            PositionDecisionResult decision, String reviewType) {

        BigDecimal currentPrice = quote != null && quote.currentPrice() != null
                ? BigDecimal.valueOf(quote.currentPrice()) : null;
        BigDecimal pnlPct = BigDecimal.ZERO;
        if (currentPrice != null && pos.getAvgCost() != null && pos.getAvgCost().signum() > 0) {
            pnlPct = currentPrice.subtract(pos.getAvgCost())
                    .divide(pos.getAvgCost(), 4, RoundingMode.HALF_UP)
                    .multiply(new BigDecimal("100"));
        }

        PositionReviewLogEntity entry = new PositionReviewLogEntity();
        entry.setPositionId(pos.getId());
        entry.setSymbol(pos.getSymbol());
        entry.setReviewDate(LocalDate.now());
        entry.setReviewTime(LocalTime.now());
        entry.setReviewType(reviewType);
        entry.setDecisionStatus(decision.status().name());
        entry.setCurrentPrice(currentPrice);
        entry.setEntryPrice(pos.getAvgCost());
        entry.setPnlPct(pnlPct.setScale(2, RoundingMode.HALF_UP));
        entry.setPrevStopLoss(pos.getTrailingStopPrice() != null
                ? pos.getTrailingStopPrice() : pos.getStopLossPrice());
        entry.setSuggestedStop(decision.suggestedStopLoss());
        entry.setReason(decision.reason());
        return reviewLogRepository.save(entry);
    }
}
