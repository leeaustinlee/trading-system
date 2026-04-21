package com.austin.trading.service;

import com.austin.trading.engine.TradeReviewEngine;
import com.austin.trading.engine.TradeReviewEngine.*;
import com.austin.trading.entity.PositionEntity;
import com.austin.trading.entity.StockEvaluationEntity;
import com.austin.trading.entity.TradeReviewEntity;
import com.austin.trading.repository.PositionRepository;
import com.austin.trading.repository.StockEvaluationRepository;
import com.austin.trading.repository.TradeReviewRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

@Service
public class TradeReviewService {

    private static final Logger log = LoggerFactory.getLogger(TradeReviewService.class);

    private final TradeReviewEngine tradeReviewEngine;
    private final TradeReviewRepository tradeReviewRepository;
    private final PositionRepository positionRepository;
    private final StockEvaluationRepository stockEvaluationRepository;
    private final ScoreConfigService config;
    private final ObjectMapper objectMapper;
    private final TradeAttributionService tradeAttributionService;

    public TradeReviewService(TradeReviewEngine tradeReviewEngine,
                               TradeReviewRepository tradeReviewRepository,
                               PositionRepository positionRepository,
                               StockEvaluationRepository stockEvaluationRepository,
                               ScoreConfigService config,
                               ObjectMapper objectMapper,
                               TradeAttributionService tradeAttributionService) {
        this.tradeReviewEngine = tradeReviewEngine;
        this.tradeReviewRepository = tradeReviewRepository;
        this.positionRepository = positionRepository;
        this.stockEvaluationRepository = stockEvaluationRepository;
        this.config = config;
        this.objectMapper = objectMapper;
        this.tradeAttributionService = tradeAttributionService;
    }

    /** 為指定 position 產生 trade review，並觸發 attribution 計算。 */
    @Transactional
    public Optional<TradeReviewEntity> generateForPosition(PositionEntity pos) {
        if (!"CLOSED".equals(pos.getStatus())) return Optional.empty();

        try {
            TradeReviewInput input = buildInput(pos);
            TradeReviewResult result = tradeReviewEngine.evaluate(input);
            TradeReviewEntity entity = toEntity(pos, input, result);
            TradeReviewEntity saved = tradeReviewRepository.save(entity);
            log.info("[TradeReview] {} → {} grade={} tag={}", pos.getSymbol(),
                    input.pnlPct(), result.reviewGrade(), result.primaryTag());

            // P1.2: trigger attribution after review (mfe/mae now available)
            tradeAttributionService.computeForPosition(pos);

            return Optional.of(saved);
        } catch (Exception e) {
            log.warn("[TradeReview] 產生失敗 posId={}: {}", pos.getId(), e.getMessage());
            return Optional.empty();
        }
    }

    /** 批次為所有未 review 的已關閉 position 產生 review */
    @Transactional
    public int generateForAllUnreviewed() {
        List<PositionEntity> closed = positionRepository.findByStatus("CLOSED");
        int count = 0;
        for (PositionEntity pos : closed) {
            if (tradeReviewRepository.findTopByPositionIdOrderByReviewVersionDesc(pos.getId()).isPresent()) {
                continue; // 已有 review
            }
            generateForPosition(pos);
            count++;
        }
        log.info("[TradeReview] 批次 review 完成，新增 {} 筆", count);
        return count;
    }

    public List<TradeReviewEntity> getAll() {
        return tradeReviewRepository.findAllByOrderByCreatedAtDesc();
    }

    public List<TradeReviewEntity> getByPositionId(Long positionId) {
        return tradeReviewRepository.findByPositionIdOrderByReviewVersionDesc(positionId);
    }

    // ── 私有方法 ──────────────────────────────────────────────────────────

    private TradeReviewInput buildInput(PositionEntity pos) {
        BigDecimal pnlPct = BigDecimal.ZERO;
        if (pos.getClosePrice() != null && pos.getAvgCost() != null && pos.getAvgCost().signum() > 0) {
            pnlPct = pos.getClosePrice().subtract(pos.getAvgCost())
                    .divide(pos.getAvgCost(), 4, RoundingMode.HALF_UP)
                    .multiply(new BigDecimal("100"));
        }

        int holdingDays = pos.getOpenedAt() != null && pos.getClosedAt() != null
                ? (int) ChronoUnit.DAYS.between(pos.getOpenedAt().toLocalDate(), pos.getClosedAt().toLocalDate())
                : 0;

        // 從 stock_evaluation 取進場時的 snapshot
        LocalDate entryDate = pos.getOpenedAt() != null ? pos.getOpenedAt().toLocalDate() : LocalDate.now();
        var evalOpt = stockEvaluationRepository.findByTradingDateAndSymbol(entryDate, pos.getSymbol());

        BigDecimal finalRank = evalOpt.map(StockEvaluationEntity::getFinalRankScore).orElse(null);
        BigDecimal javaScore = evalOpt.map(StockEvaluationEntity::getJavaStructureScore).orElse(null);
        BigDecimal claudeScore = evalOpt.map(StockEvaluationEntity::getClaudeScore).orElse(null);

        return new TradeReviewInput(
                pos.getSymbol(), pos.getAvgCost(), pos.getClosePrice(),
                pnlPct, holdingDays,
                null, null,  // MFE/MAE: v1 暫不計算，後續整合
                pos.getNote(), pos.getExitReason(),
                finalRank, javaScore, claudeScore,
                null, null,  // themeRank, finalThemeScore
                false, 0, null, "B",  // wasExtended, consecutiveStrongDays, watchlistStatus, marketGrade
                pos.getStopLossPrice(), pos.getTrailingStopPrice(),
                false, false,  // wasFailedBreakout, wasWeakTheme
                MarketCondition.RANGE  // v1 預設 RANGE，後續從 market snapshot 取
        );
    }

    private TradeReviewEntity toEntity(PositionEntity pos, TradeReviewInput input, TradeReviewResult result) {
        TradeReviewEntity e = new TradeReviewEntity();
        e.setPositionId(pos.getId());
        e.setSymbol(pos.getSymbol());
        e.setEntryDate(pos.getOpenedAt() != null ? pos.getOpenedAt().toLocalDate() : null);
        e.setExitDate(pos.getClosedAt() != null ? pos.getClosedAt().toLocalDate() : null);
        e.setEntryPrice(pos.getAvgCost());
        e.setExitPrice(pos.getClosePrice());
        e.setPnlPct(input.pnlPct());
        e.setHoldingDays(input.holdingDays());
        e.setMarketCondition(result.marketCondition() != null ? result.marketCondition().name() : null);
        e.setReviewGrade(result.reviewGrade());
        e.setPrimaryTag(result.primaryTag());
        e.setSecondaryTagsJson(toJson(result.secondaryTags()));
        e.setStrengthsJson(toJson(result.strengths()));
        e.setWeaknessesJson(toJson(result.weaknesses()));
        e.setImprovementSuggestionsJson(toJson(result.improvementSuggestions()));
        e.setAiSummary(result.aiSummary());
        e.setReviewerType("RULE_ENGINE");
        e.setReviewVersion(1);
        return e;
    }

    private String toJson(Object obj) {
        if (obj == null) return null;
        try { return objectMapper.writeValueAsString(obj); }
        catch (JsonProcessingException e) { return null; }
    }
}
