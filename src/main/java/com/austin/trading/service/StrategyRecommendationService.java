package com.austin.trading.service;

import com.austin.trading.dto.internal.TradeAttributionOutput;
import com.austin.trading.engine.StrategyRecommendationEngine;
import com.austin.trading.engine.StrategyRecommendationEngine.*;
import com.austin.trading.entity.StrategyRecommendationEntity;
import com.austin.trading.entity.TradeReviewEntity;
import com.austin.trading.repository.StrategyRecommendationRepository;
import com.austin.trading.repository.TradeReviewRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

@Service
public class StrategyRecommendationService {

    private static final Logger log = LoggerFactory.getLogger(StrategyRecommendationService.class);

    private final StrategyRecommendationEngine engine;
    private final StrategyRecommendationRepository repository;
    private final TradeReviewRepository tradeReviewRepository;
    private final TradeAttributionService tradeAttributionService;
    private final ScoreConfigService configService;
    private final ObjectMapper objectMapper;

    public StrategyRecommendationService(StrategyRecommendationEngine engine,
                                          StrategyRecommendationRepository repository,
                                          TradeReviewRepository tradeReviewRepository,
                                          TradeAttributionService tradeAttributionService,
                                          ScoreConfigService configService,
                                          ObjectMapper objectMapper) {
        this.engine = engine;
        this.repository = repository;
        this.tradeReviewRepository = tradeReviewRepository;
        this.tradeAttributionService = tradeAttributionService;
        this.configService = configService;
        this.objectMapper = objectMapper;
    }

    /** 從所有 trade review + attribution 聚合統計，產生建議 (P2.1) */
    @Transactional
    public List<StrategyRecommendationEntity> generate(Long sourceRunId) {
        List<TradeReviewEntity> reviews = tradeReviewRepository.findAllByOrderByCreatedAtDesc();
        if (reviews.isEmpty()) {
            log.info("[StrategyRec] 無 trade review 資料，跳過");
            return List.of();
        }

        AggregatedStats stats = aggregate(reviews);
        List<TradeAttributionOutput> attributions = tradeAttributionService.findAll();
        AttributionStats attrStats = buildAttributionStats(attributions);
        log.info("[StrategyRec] attribution records={}", attrStats.attributionCount());
        List<Recommendation> recs = engine.analyze(stats, attrStats);

        List<StrategyRecommendationEntity> saved = new ArrayList<>();
        for (Recommendation rec : recs) {
            StrategyRecommendationEntity e = new StrategyRecommendationEntity();
            e.setRecommendationType(rec.recommendationType());
            e.setTargetKey(rec.targetKey());
            e.setCurrentValue(rec.currentValue());
            e.setSuggestedValue(rec.suggestedValue());
            e.setConfidenceLevel(rec.confidenceLevel());
            e.setReason(rec.reason());
            e.setSupportingMetricsJson(toJson(rec.supportingMetrics()));
            e.setSourceRunId(sourceRunId);
            e.setStatus("NEW");
            saved.add(repository.save(e));
        }

        log.info("[StrategyRec] 產生 {} 筆建議", saved.size());
        return saved;
    }

    public List<StrategyRecommendationEntity> getAll() {
        return repository.findAllByOrderByCreatedAtDesc();
    }

    public List<StrategyRecommendationEntity> getByStatus(String status) {
        return repository.findByStatus(status);
    }

    @Transactional
    public StrategyRecommendationEntity updateStatus(Long id, String newStatus) {
        StrategyRecommendationEntity e = repository.findById(id)
                .orElseThrow(() -> new RuntimeException("Recommendation not found: " + id));
        e.setStatus(newStatus);
        return repository.save(e);
    }

    // ── 私有方法 ──────────────────────────────────────────────────────────

    private AggregatedStats aggregate(List<TradeReviewEntity> reviews) {
        int total = reviews.size();
        int wins = 0;
        BigDecimal sumReturn = BigDecimal.ZERO;
        BigDecimal sumMfe = BigDecimal.ZERO;
        BigDecimal sumMae = BigDecimal.ZERO;
        BigDecimal sumDays = BigDecimal.ZERO;
        Map<String, Integer> countByTag = new HashMap<>();
        Map<String, BigDecimal> winRateByTag = new HashMap<>();
        Map<String, int[]> winLossByTag = new HashMap<>();

        int consecutiveLoss = 0, maxConsecutiveLoss = 0;

        for (TradeReviewEntity r : reviews) {
            BigDecimal pnl = r.getPnlPct() != null ? r.getPnlPct() : BigDecimal.ZERO;
            boolean isWin = pnl.signum() > 0;
            if (isWin) { wins++; consecutiveLoss = 0; }
            else { consecutiveLoss++; maxConsecutiveLoss = Math.max(maxConsecutiveLoss, consecutiveLoss); }

            sumReturn = sumReturn.add(pnl);
            if (r.getMfePct() != null) sumMfe = sumMfe.add(r.getMfePct());
            if (r.getMaePct() != null) sumMae = sumMae.add(r.getMaePct());
            if (r.getHoldingDays() != null) sumDays = sumDays.add(new BigDecimal(r.getHoldingDays()));

            String tag = r.getPrimaryTag();
            countByTag.merge(tag, 1, Integer::sum);
            winLossByTag.computeIfAbsent(tag, k -> new int[2]);
            if (isWin) winLossByTag.get(tag)[0]++;
            else winLossByTag.get(tag)[1]++;
        }

        BigDecimal totalBd = new BigDecimal(total);
        BigDecimal overallWinRate = total > 0
                ? new BigDecimal(wins).divide(totalBd, 4, RoundingMode.HALF_UP).multiply(new BigDecimal("100"))
                : BigDecimal.ZERO;
        BigDecimal avgReturn = total > 0 ? sumReturn.divide(totalBd, 4, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        BigDecimal avgMfe = total > 0 ? sumMfe.divide(totalBd, 4, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        BigDecimal avgMae = total > 0 ? sumMae.divide(totalBd, 4, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        BigDecimal avgDays = total > 0 ? sumDays.divide(totalBd, 2, RoundingMode.HALF_UP) : BigDecimal.ZERO;

        // 各 tag 勝率
        for (var entry : winLossByTag.entrySet()) {
            int w = entry.getValue()[0], l = entry.getValue()[1];
            int t2 = w + l;
            if (t2 > 0) {
                winRateByTag.put(entry.getKey(),
                        new BigDecimal(w).divide(new BigDecimal(t2), 4, RoundingMode.HALF_UP)
                                .multiply(new BigDecimal("100")));
            }
        }

        // config snapshot
        Map<String, String> configSnapshot = new LinkedHashMap<>();
        configService.getAll().forEach(c -> configSnapshot.put(c.configKey(), c.configValue()));

        return new AggregatedStats(overallWinRate, avgReturn, total,
                winRateByTag, Map.of(), countByTag,
                avgMfe, avgMae, avgDays, maxConsecutiveLoss, configSnapshot);
    }

    /**
     * Builds AttributionStats from TradeAttribution records for P2.1 analysis.
     */
    private AttributionStats buildAttributionStats(List<TradeAttributionOutput> attributions) {
        if (attributions.isEmpty()) {
            return new AttributionStats(null, null, null, Map.of(), 0);
        }

        int total = attributions.size();
        int timingPoor = 0;
        int exitPoor = 0;
        BigDecimal sumDelay = BigDecimal.ZERO;
        int delayCount = 0;

        Map<String, int[]> setupWinLoss = new LinkedHashMap<>();

        for (TradeAttributionOutput a : attributions) {
            if ("POOR".equals(a.timingQuality())) timingPoor++;
            if ("POOR".equals(a.exitQuality())) exitPoor++;
            if (a.delayPct() != null) {
                sumDelay = sumDelay.add(a.delayPct());
                delayCount++;
            }
            if (a.setupType() != null && a.pnlPct() != null) {
                int[] wl = setupWinLoss.computeIfAbsent(a.setupType(), k -> new int[2]);
                if (a.pnlPct().signum() > 0) wl[0]++;
                else wl[1]++;
            }
        }

        BigDecimal totalBd = new BigDecimal(total);
        BigDecimal timingPoorRate = new BigDecimal(timingPoor)
                .divide(totalBd, 4, RoundingMode.HALF_UP).multiply(new BigDecimal("100"));
        BigDecimal exitPoorRate = new BigDecimal(exitPoor)
                .divide(totalBd, 4, RoundingMode.HALF_UP).multiply(new BigDecimal("100"));
        BigDecimal avgDelayPct = delayCount > 0
                ? sumDelay.divide(new BigDecimal(delayCount), 4, RoundingMode.HALF_UP) : null;

        Map<String, BigDecimal> setupWinRates = new LinkedHashMap<>();
        for (var entry : setupWinLoss.entrySet()) {
            int w = entry.getValue()[0], l = entry.getValue()[1];
            int t = w + l;
            if (t > 0) {
                setupWinRates.put(entry.getKey(),
                        new BigDecimal(w).divide(new BigDecimal(t), 4, RoundingMode.HALF_UP)
                                .multiply(new BigDecimal("100")));
            }
        }

        return new AttributionStats(timingPoorRate, exitPoorRate, avgDelayPct, setupWinRates, total);
    }

    private String toJson(Object obj) {
        if (obj == null) return null;
        try { return objectMapper.writeValueAsString(obj); }
        catch (JsonProcessingException e) { return null; }
    }
}
