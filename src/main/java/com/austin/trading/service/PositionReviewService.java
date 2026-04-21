package com.austin.trading.service;

import com.austin.trading.dto.internal.MarketRegimeDecision;
import com.austin.trading.dto.internal.ThemeStrengthDecision;
import com.austin.trading.dto.response.LiveQuoteResponse;
import com.austin.trading.engine.ExitRegimeIntegrationEngine;
import com.austin.trading.engine.PositionDecisionEngine;
import com.austin.trading.engine.PositionDecisionEngine.*;
import com.austin.trading.engine.StopLossTakeProfitEngine;
import com.austin.trading.entity.PositionEntity;
import com.austin.trading.entity.PositionReviewLogEntity;
import com.austin.trading.repository.PositionReviewLogRepository;
import com.austin.trading.repository.PositionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    public PositionReviewService(
            PositionRepository positionRepository,
            PositionReviewLogRepository reviewLogRepository,
            PositionDecisionEngine positionDecisionEngine,
            ExitRegimeIntegrationEngine exitRegimeEngine,
            CandidateScanService candidateScanService,
            StopLossTakeProfitEngine stopLossTakeProfitEngine,
            ScoreConfigService scoreConfigService,
            MarketRegimeService marketRegimeService,
            ThemeStrengthService themeStrengthService
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
                momentum ? "AI auto-filled SL/TP from momentum defaults"
                        : "AI auto-filled SL/TP from setup defaults"));
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
