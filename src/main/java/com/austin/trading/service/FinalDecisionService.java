package com.austin.trading.service;

import com.austin.trading.dto.request.FinalDecisionCandidateRequest;
import com.austin.trading.dto.request.FinalDecisionEvaluateRequest;
import com.austin.trading.dto.request.PositionSizingEvaluateRequest;
import com.austin.trading.dto.request.StopLossTakeProfitEvaluateRequest;
import com.austin.trading.dto.response.FinalDecisionRecordResponse;
import com.austin.trading.dto.response.FinalDecisionResponse;
import com.austin.trading.dto.response.FinalDecisionSelectedStockResponse;
import com.austin.trading.dto.response.MarketCurrentResponse;
import com.austin.trading.dto.response.PositionSizingResponse;
import com.austin.trading.dto.response.StopLossTakeProfitResponse;
import com.austin.trading.dto.response.TradingStateResponse;
import com.austin.trading.engine.ConsensusScoringEngine;
import com.austin.trading.engine.FinalDecisionEngine;
import com.austin.trading.engine.JavaStructureScoringEngine;
import com.austin.trading.engine.PositionSizingEngine;
import com.austin.trading.engine.StopLossTakeProfitEngine;
import com.austin.trading.engine.VetoEngine;
import com.austin.trading.engine.WeightedScoringEngine;
import com.austin.trading.entity.CapitalConfigEntity;
import com.austin.trading.entity.FinalDecisionEntity;
import com.austin.trading.entity.PositionEntity;
import com.austin.trading.entity.StockEvaluationEntity;
import com.austin.trading.entity.WatchlistStockEntity;
import com.austin.trading.repository.FinalDecisionRepository;
import com.austin.trading.repository.PositionRepository;
import com.austin.trading.repository.StockEvaluationRepository;
import com.austin.trading.repository.WatchlistStockRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class FinalDecisionService {

    private static final Logger log = LoggerFactory.getLogger(FinalDecisionService.class);

    // 資金參數回退值（capital_config 未設定時使用）
    private static final double DEFAULT_BASE_CAPITAL     = 50_000.0;
    private static final double DEFAULT_MAX_SINGLE       = 50_000.0;
    private static final double DEFAULT_RISK_BUDGET_RATIO = 1.0;

    // 預設停損停利百分比（若候選股缺資料時使用）
    private static final double DEFAULT_SL_PCT  = 6.0;
    private static final double DEFAULT_TP1_PCT = 8.0;
    private static final double DEFAULT_TP2_PCT = 13.0;

    private final ConsensusScoringEngine    consensusScoringEngine;
    private final FinalDecisionEngine       finalDecisionEngine;
    private final JavaStructureScoringEngine javaStructureScoringEngine;
    private final VetoEngine                vetoEngine;
    private final WeightedScoringEngine     weightedScoringEngine;
    private final PositionSizingEngine      positionSizingEngine;
    private final StopLossTakeProfitEngine  stopLossTakeProfitEngine;
    private final FinalDecisionRepository   finalDecisionRepository;
    private final StockEvaluationRepository stockEvaluationRepository;
    private final PositionRepository        positionRepository;
    private final WatchlistStockRepository  watchlistStockRepository;
    private final MarketDataService         marketDataService;
    private final TradingStateService       tradingStateService;
    private final CandidateScanService      candidateScanService;
    private final CapitalService            capitalService;
    private final CooldownService           cooldownService;
    private final MarketCooldownService     marketCooldownService;
    private final ScoreConfigService        scoreConfigService;
    private final ObjectMapper              objectMapper;

    public FinalDecisionService(
            ConsensusScoringEngine consensusScoringEngine,
            FinalDecisionEngine finalDecisionEngine,
            JavaStructureScoringEngine javaStructureScoringEngine,
            VetoEngine vetoEngine,
            WeightedScoringEngine weightedScoringEngine,
            PositionSizingEngine positionSizingEngine,
            StopLossTakeProfitEngine stopLossTakeProfitEngine,
            FinalDecisionRepository finalDecisionRepository,
            StockEvaluationRepository stockEvaluationRepository,
            PositionRepository positionRepository,
            WatchlistStockRepository watchlistStockRepository,
            MarketDataService marketDataService,
            TradingStateService tradingStateService,
            CandidateScanService candidateScanService,
            CapitalService capitalService,
            CooldownService cooldownService,
            MarketCooldownService marketCooldownService,
            ScoreConfigService scoreConfigService,
            ObjectMapper objectMapper
    ) {
        this.consensusScoringEngine     = consensusScoringEngine;
        this.finalDecisionEngine        = finalDecisionEngine;
        this.javaStructureScoringEngine = javaStructureScoringEngine;
        this.vetoEngine                 = vetoEngine;
        this.weightedScoringEngine      = weightedScoringEngine;
        this.positionSizingEngine       = positionSizingEngine;
        this.stopLossTakeProfitEngine   = stopLossTakeProfitEngine;
        this.finalDecisionRepository    = finalDecisionRepository;
        this.stockEvaluationRepository  = stockEvaluationRepository;
        this.positionRepository         = positionRepository;
        this.watchlistStockRepository   = watchlistStockRepository;
        this.marketDataService          = marketDataService;
        this.tradingStateService        = tradingStateService;
        this.candidateScanService       = candidateScanService;
        this.capitalService             = capitalService;
        this.cooldownService            = cooldownService;
        this.marketCooldownService      = marketCooldownService;
        this.scoreConfigService         = scoreConfigService;
        this.objectMapper               = objectMapper;
    }

    @Transactional
    public FinalDecisionResponse evaluateAndPersist(LocalDate tradingDate) {
        MarketCurrentResponse market = marketDataService.getCurrentMarket().orElse(null);
        TradingStateResponse state = tradingStateService.getCurrentState().orElse(null);

        String marketGrade  = market == null ? "B" : safe(market.marketGrade(), "B");
        String decisionLock = state == null ? "NONE" : safe(state.decisionLock(), "NONE");
        String timeDecay    = state == null ? "EARLY" : safe(state.timeDecayStage(), "EARLY");

        // ── Step 0: 持倉滿倉檢查 ──────────────────────────────────────────────
        List<PositionEntity> openPositions = positionRepository.findByStatus("OPEN");
        int maxPos = scoreConfigService.getInt("portfolio.max_open_positions", 3);
        boolean hasPosition = !openPositions.isEmpty();

        if (openPositions.size() >= maxPos) {
            boolean allowWhenFullStrong = scoreConfigService.getBoolean(
                    "portfolio.allow_new_when_full_strong", false);
            boolean allStrong = openPositions.stream().allMatch(p ->
                    "STRONG".equals(p.getReviewStatus()) || "HOLD".equals(p.getReviewStatus()));
            if (!allowWhenFullStrong || !allStrong) {
                log.info("[FinalDecision] 持倉已滿 ({}/{}), 不開新倉", openPositions.size(), maxPos);
                return persistAndReturn(tradingDate, new FinalDecisionResponse(
                        "REST", List.of(), List.of("持倉已滿 (" + openPositions.size() + "/" + maxPos + ")"),
                        "持倉已滿，不開新倉。"));
            }
        }

        // ── Step 0.1: 全市場冷卻檢查（連續虧損 / 當日虧損上限）──────────────
        MarketCooldownService.MarketCooldownResult marketCooldown = marketCooldownService.check();
        if (marketCooldown.blocked()) {
            log.info("[FinalDecision] 全市場冷卻: {}", marketCooldown.reason());
            return persistAndReturn(tradingDate, new FinalDecisionResponse(
                    "REST", List.of(), List.of(marketCooldown.reason()), marketCooldown.reason()));
        }

        int maxCount = scoreConfigService.getInt("candidate.scan.maxCount", 8);
        List<FinalDecisionCandidateRequest> rawCandidates =
                candidateScanService.loadFinalDecisionCandidates(tradingDate, maxCount);

        // ── 評分管線：JavaStructure → Veto → WeightedScore ────────────────────
        List<FinalDecisionCandidateRequest> scoredCandidates =
                applyScoringPipeline(rawCandidates, tradingDate, marketGrade, decisionLock, timeDecay);

        // ── Step 0.5: 同 symbol 重複持倉 + cooldown + 同題材集中度 veto ──────────
        List<String> heldSymbols = openPositions.stream().map(PositionEntity::getSymbol).toList();
        int sameThemeMax = scoreConfigService.getInt("portfolio.same_theme_max", 1);

        scoredCandidates = scoredCandidates.stream().filter(c -> {
            if (heldSymbols.contains(c.stockCode())) {
                log.info("[FinalDecision] 排除 {} — 已有 OPEN 持倉", c.stockCode());
                return false;
            }
            if (cooldownService.isInCooldown(c.stockCode(), null)) {
                log.info("[FinalDecision] 排除 {} — 冷卻期中", c.stockCode());
                return false;
            }
            return true;
        }).toList();

        // ── Step 0.6: Score gap 保護 — 新倉需高出 STRONG 持股最低分 ──────────────
        BigDecimal scoreGap = scoreConfigService.getDecimal("portfolio.replace_strong_score_gap", new BigDecimal("1.5"));
        BigDecimal strongMinScore = openPositions.stream()
                .filter(p -> "STRONG".equals(p.getReviewStatus()))
                .map(p -> {
                    // 從 stock_evaluation 取 finalRankScore
                    return stockEvaluationRepository.findByTradingDateAndSymbol(tradingDate, p.getSymbol())
                            .map(StockEvaluationEntity::getFinalRankScore).orElse(BigDecimal.ZERO);
                })
                .min(BigDecimal::compareTo).orElse(BigDecimal.ZERO);

        if (strongMinScore.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal requiredMin = strongMinScore.add(scoreGap);
            scoredCandidates = scoredCandidates.stream().filter(c -> {
                BigDecimal fs = c.finalRankScore();
                if (fs != null && fs.compareTo(requiredMin) < 0) {
                    log.info("[FinalDecision] 排除 {} — finalRank {} 未超過 STRONG 持股最低分+gap ({})",
                            c.stockCode(), fs, requiredMin);
                    return false;
                }
                return true;
            }).toList();
        }

        FinalDecisionEvaluateRequest request = new FinalDecisionEvaluateRequest(
                marketGrade,
                decisionLock,
                timeDecay,
                hasPosition,
                scoredCandidates
        );

        FinalDecisionResponse decision = finalDecisionEngine.evaluate(request);

        // 建立 candidateMap 以便回查估值模式
        Map<String, FinalDecisionCandidateRequest> candidateMap = scoredCandidates.stream()
                .collect(Collectors.toMap(FinalDecisionCandidateRequest::stockCode, c -> c, (a, b) -> a));

        // 從 capital_config 取得可動用現金，計算倉位上限
        CapitalConfigEntity capitalCfg = capitalService.getConfig();
        double availCash = capitalCfg.getAvailableCash() != null
                ? capitalCfg.getAvailableCash().doubleValue() : 0.0;
        double baseCapital = availCash > 0 ? availCash : DEFAULT_BASE_CAPITAL;
        // Level 4 規則：單檔最多 3-5 萬，且不超過可動用現金 35%
        double maxSingle = availCash > 0
                ? Math.min(availCash * 0.35, 50_000.0)
                : DEFAULT_MAX_SINGLE;

        // 為每檔入選股補上倉位建議與停損停利
        List<FinalDecisionSelectedStockResponse> enriched = decision.selectedStocks().stream()
                .map(s -> enrichWithSizing(s, marketGrade, candidateMap.get(s.stockCode()),
                        baseCapital, maxSingle))
                .toList();

        FinalDecisionResponse enrichedDecision = new FinalDecisionResponse(
                decision.decision(),
                enriched,
                decision.rejectedReasons(),
                decision.summary()
        );

        FinalDecisionEntity entity = new FinalDecisionEntity();
        entity.setTradingDate(tradingDate);
        entity.setDecision(enrichedDecision.decision());
        entity.setSummary(enrichedDecision.summary());
        entity.setPayloadJson(toPayload(enrichedDecision));
        finalDecisionRepository.save(entity);

        return enrichedDecision;
    }

    public Optional<FinalDecisionRecordResponse> getCurrent() {
        return finalDecisionRepository.findTopByOrderByTradingDateDescCreatedAtDesc().map(this::toResponse);
    }

    public List<FinalDecisionRecordResponse> getHistory(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        return finalDecisionRepository.findAllByOrderByTradingDateDescCreatedAtDesc(PageRequest.of(0, safeLimit))
                .stream().map(this::toResponse).toList();
    }

    // ── 私有方法 ───────────────────────────────────────────────────────────────

    /** 快速回傳 REST 並持久化（用於滿倉等提前中斷情境） */
    private FinalDecisionResponse persistAndReturn(LocalDate tradingDate, FinalDecisionResponse response) {
        FinalDecisionEntity entity = new FinalDecisionEntity();
        entity.setTradingDate(tradingDate);
        entity.setDecision(response.decision());
        entity.setSummary(response.summary());
        entity.setPayloadJson(toPayload(response));
        finalDecisionRepository.save(entity);
        return response;
    }

    private FinalDecisionSelectedStockResponse enrichWithSizing(
            FinalDecisionSelectedStockResponse stock,
            String marketGrade,
            FinalDecisionCandidateRequest candidate,
            double baseCapital,
            double maxSingle
    ) {
        String valuationMode = candidate == null ? "VALUE_STORY" : safe(candidate.valuationMode(), "VALUE_STORY");
        boolean nearHigh     = candidate != null && Boolean.TRUE.equals(candidate.nearDayHigh());

        // 停損停利：若原本就有，直接用；否則從 entryPriceZone 估算或使用預設百分比
        double sl  = stock.stopLossPrice()  != null ? stock.stopLossPrice()  : computeDefaultSl(stock);
        double tp1 = stock.takeProfit1()    != null ? stock.takeProfit1()    : computeDefaultTp1(stock);
        double tp2 = stock.takeProfit2()    != null ? stock.takeProfit2()    : computeDefaultTp2(stock);

        // 若還是沒有資料（沒有 entryPrice 可算），保持 null
        Double finalSl  = sl  == 0.0 ? stock.stopLossPrice()  : sl;
        Double finalTp1 = tp1 == 0.0 ? stock.takeProfit1()    : tp1;
        Double finalTp2 = tp2 == 0.0 ? stock.takeProfit2()    : tp2;

        // 倉位建議（使用真實可動用現金）
        PositionSizingResponse sizing = positionSizingEngine.evaluate(
                new PositionSizingEvaluateRequest(
                        marketGrade,
                        valuationMode,
                        baseCapital,
                        maxSingle,
                        DEFAULT_RISK_BUDGET_RATIO,
                        nearHigh
                )
        );

        return new FinalDecisionSelectedStockResponse(
                stock.stockCode(),
                stock.stockName(),
                stock.entryType(),
                stock.entryPriceZone(),
                finalSl,
                finalTp1,
                finalTp2,
                stock.riskRewardRatio(),
                stock.rationale(),
                sizing.suggestedPositionSize(),
                sizing.positionSizeMultiplier()
        );
    }

    /**
     * 若 SL/TP/entry 皆無，嘗試從 entryPriceZone 取中間價計算預設值。
     * entryPriceZone 格式 "100.00-102.00"。若解析失敗，回傳 0.0（呼叫方判斷）。
     */
    private double parseEntryFromZone(String zone) {
        if (zone == null || zone.isBlank()) return 0.0;
        String[] parts = zone.split("-");
        if (parts.length < 2) return 0.0;
        try {
            double lo = Double.parseDouble(parts[0].trim());
            double hi = Double.parseDouble(parts[parts.length - 1].trim());
            return (lo + hi) / 2.0;
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private double computeDefaultSl(FinalDecisionSelectedStockResponse s) {
        double entry = parseEntryFromZone(s.entryPriceZone());
        return entry > 0 ? Math.round(entry * (1.0 - DEFAULT_SL_PCT / 100.0) * 10000.0) / 10000.0 : 0.0;
    }

    private double computeDefaultTp1(FinalDecisionSelectedStockResponse s) {
        double entry = parseEntryFromZone(s.entryPriceZone());
        return entry > 0 ? Math.round(entry * (1.0 + DEFAULT_TP1_PCT / 100.0) * 10000.0) / 10000.0 : 0.0;
    }

    private double computeDefaultTp2(FinalDecisionSelectedStockResponse s) {
        double entry = parseEntryFromZone(s.entryPriceZone());
        return entry > 0 ? Math.round(entry * (1.0 + DEFAULT_TP2_PCT / 100.0) * 10000.0) / 10000.0 : 0.0;
    }

    private FinalDecisionRecordResponse toResponse(FinalDecisionEntity entity) {
        return new FinalDecisionRecordResponse(
                entity.getId(),
                entity.getTradingDate(),
                entity.getDecision(),
                entity.getSummary(),
                entity.getPayloadJson(),
                entity.getCreatedAt()
        );
    }

    private String toPayload(FinalDecisionResponse decision) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "decision",        decision.decision() == null ? "" : decision.decision(),
                    "selected_count",  decision.selectedStocks().size(),
                    "rejected_count",  decision.rejectedReasons().size(),
                    "selected_stocks", decision.selectedStocks(),
                    "rejected_reasons",decision.rejectedReasons(),
                    "summary",         decision.summary() == null ? "" : decision.summary()
            ));
        } catch (JsonProcessingException e) {
            log.warn("toPayload serialization failed", e);
            return "{\"decision\":\"" + decision.decision() + "\"," +
                   "\"selected_count\":" + decision.selectedStocks().size() + "," +
                   "\"rejected_count\":" + decision.rejectedReasons().size() + "}";
        }
    }

    // ── 評分管線 ───────────────────────────────────────────────────────────────

    /**
     * 對所有候選股套用 JavaStructure → Consensus → Veto → WeightedScore 管線（v2.0 BC Sniper）。
     * 計算結果回寫 stock_evaluation。
     *
     * @return 攜帶完整分數欄位的新 request list
     */
    private List<FinalDecisionCandidateRequest> applyScoringPipeline(
            List<FinalDecisionCandidateRequest> candidates,
            LocalDate tradingDate,
            String marketGrade, String decisionLock, String timeDecay
    ) {
        List<FinalDecisionCandidateRequest> result = new ArrayList<>();
        int rankingOrder = 0;

        for (FinalDecisionCandidateRequest c : candidates) {
            rankingOrder++;
            // 1. Java 結構評分
            BigDecimal javaScore = javaStructureScoringEngine.compute(
                    new JavaStructureScoringEngine.JavaStructureInput(
                            c.riskRewardRatio() == null ? null : BigDecimal.valueOf(c.riskRewardRatio()),
                            c.includeInFinalPlan(),
                            c.stopLossPrice() == null ? null : BigDecimal.valueOf(c.stopLossPrice()),
                            c.valuationMode(),
                            c.entryType(),
                            c.baseScore(),
                            Boolean.TRUE.equals(c.hasTheme())
                    )
            );

            // 讀取既有的 claudeScore / codexScore（Claude/Codex 可能已由外部回填）
            BigDecimal claudeScore = c.claudeScore();
            BigDecimal codexScore  = c.codexScore();

            // 2. 共識評分（BC Sniper v2.0）
            ConsensusScoringEngine.ConsensusResult consensusResult = consensusScoringEngine.compute(
                    new ConsensusScoringEngine.ConsensusInput(javaScore, claudeScore, codexScore)
            );
            BigDecimal consensusScore      = consensusResult.consensusScore();
            BigDecimal disagreementPenalty = consensusResult.disagreementPenalty();

            // 3. Veto 評估（v2.0 含題材/Codex/分歧等新規則）
            VetoEngine.VetoResult veto = vetoEngine.evaluate(
                    new VetoEngine.VetoInput(
                            marketGrade,
                            decisionLock,
                            timeDecay,
                            c.riskRewardRatio() == null ? null : BigDecimal.valueOf(c.riskRewardRatio()),
                            c.includeInFinalPlan(),
                            c.stopLossPrice() == null ? null : BigDecimal.valueOf(c.stopLossPrice()),
                            c.valuationMode(),
                            c.hasTheme(),
                            c.themeRank(),
                            c.finalThemeScore(),
                            codexScore,
                            javaScore,
                            claudeScore,
                            c.volumeSpike(),
                            c.priceNotBreakHigh(),
                            c.entryTooExtended()
                    )
            );

            // 4. 加權評分與最終排序分（final = min(weighted, consensus) unless vetoed）
            BigDecimal aiWeighted = weightedScoringEngine.computeAiWeightedScore(javaScore, claudeScore, codexScore);
            BigDecimal finalRank  = weightedScoringEngine.computeFinalRankScore(aiWeighted, consensusScore, veto.vetoed());

            // 5. 回寫 stock_evaluation
            persistScores(tradingDate, c.stockCode(), javaScore, claudeScore, codexScore,
                    aiWeighted, finalRank, consensusScore, disagreementPenalty,
                    veto.vetoed(),
                    veto.reasons().isEmpty() ? null : String.join(",", veto.reasons()),
                    rankingOrder);

            // 6. 產生帶完整分數的新 request
            result.add(new FinalDecisionCandidateRequest(
                    c.stockCode(), c.stockName(), c.valuationMode(), c.entryType(),
                    c.riskRewardRatio(), c.includeInFinalPlan(), c.mainStream(),
                    c.falseBreakout(), c.belowOpen(), c.belowPrevClose(),
                    c.nearDayHigh(), c.stopLossReasonable(),
                    c.rationale(), c.entryPriceZone(),
                    c.stopLossPrice(), c.takeProfit1(), c.takeProfit2(),
                    javaScore, claudeScore, codexScore, finalRank, veto.vetoed(),
                    c.baseScore(), c.hasTheme(),
                    c.themeRank(), c.finalThemeScore(),
                    consensusScore, disagreementPenalty,
                    c.volumeSpike(), c.priceNotBreakHigh(), c.entryTooExtended(),
                    c.entryTriggered()
            ));
        }
        return result;
    }

    /** 將本次計算的評分回寫 stock_evaluation 表（v2.0 含 consensus/disagreement） */
    private void persistScores(LocalDate date, String symbol,
                               BigDecimal javaScore, BigDecimal claudeScore, BigDecimal codexScore,
                               BigDecimal aiWeighted, BigDecimal finalRank,
                               BigDecimal consensusScore, BigDecimal disagreementPenalty,
                               boolean isVetoed, String vetoReasons,
                               int rankingOrder) {
        try {
            stockEvaluationRepository.findByTradingDateAndSymbol(date, symbol).ifPresent(eval -> {
                eval.setJavaStructureScore(javaScore);
                if (claudeScore         != null) eval.setClaudeScore(claudeScore);
                if (codexScore          != null) eval.setCodexScore(codexScore);
                eval.setAiWeightedScore(aiWeighted);
                eval.setFinalRankScore(finalRank);
                eval.setConsensusScore(consensusScore);
                eval.setDisagreementPenalty(disagreementPenalty);
                eval.setIsVetoed(isVetoed);
                eval.setRankingOrder(rankingOrder);
                eval.setScoreVersion(scoreConfigService.getString("scoring.version", "v2.0-bc-sniper"));
                if (vetoReasons != null) {
                    String vetoJson = "[\"" + vetoReasons.replace(",", "\",\"") + "\"]";
                    eval.setVetoReasonsJson(vetoJson);
                    eval.setJavaVetoFlags(vetoJson);
                }
                stockEvaluationRepository.save(eval);
            });
        } catch (Exception e) {
            log.warn("[FinalDecisionService] persistScores failed for {}: {}", symbol, e.getMessage());
        }
    }

    // ── 工具 ──────────────────────────────────────────────────────────────────

    private String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
