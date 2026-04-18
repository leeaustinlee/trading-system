package com.austin.trading.service;

import com.austin.trading.dto.response.LiveQuoteResponse;
import com.austin.trading.engine.PositionDecisionEngine;
import com.austin.trading.engine.PositionDecisionEngine.*;
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

    private final PositionRepository positionRepository;
    private final PositionReviewLogRepository reviewLogRepository;
    private final PositionDecisionEngine positionDecisionEngine;
    private final CandidateScanService candidateScanService;

    public PositionReviewService(
            PositionRepository positionRepository,
            PositionReviewLogRepository reviewLogRepository,
            PositionDecisionEngine positionDecisionEngine,
            CandidateScanService candidateScanService
    ) {
        this.positionRepository = positionRepository;
        this.reviewLogRepository = reviewLogRepository;
        this.positionDecisionEngine = positionDecisionEngine;
        this.candidateScanService = candidateScanService;
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

        List<ReviewResult> results = new ArrayList<>();
        for (PositionEntity pos : openPositions) {
            try {
                LiveQuoteResponse quote = quotes.stream()
                        .filter(q -> q.symbol().equals(pos.getSymbol()))
                        .findFirst().orElse(null);

                PositionDecisionResult decision = evaluatePosition(pos, quote);
                PositionReviewLogEntity logEntry = saveReviewLog(pos, quote, decision, reviewType);

                // 更新 position 狀態
                pos.setReviewStatus(decision.status().name());
                pos.setLastReviewedAt(LocalDateTime.now());
                pos.setUpdatedAt(LocalDateTime.now());

                if (decision.status() == PositionStatus.TRAIL_UP && decision.suggestedStopLoss() != null) {
                    pos.setTrailingStopPrice(decision.suggestedStopLoss());
                }

                positionRepository.save(pos);
                results.add(new ReviewResult(pos, decision, logEntry.getId()));

            } catch (Exception e) {
                log.warn("[PositionReview] 審查失敗 symbol={}: {}", pos.getSymbol(), e.getMessage());
            }
        }
        return results;
    }

    public record ReviewResult(
            PositionEntity position,
            PositionDecisionResult decision,
            Long reviewLogId
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
