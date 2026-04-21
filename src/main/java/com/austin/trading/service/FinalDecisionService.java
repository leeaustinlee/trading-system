package com.austin.trading.service;

import com.austin.trading.dto.request.FinalDecisionCandidateRequest;
import com.austin.trading.dto.request.FinalDecisionEvaluateRequest;
import com.austin.trading.domain.enums.StrategyType;
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
import com.austin.trading.entity.AiTaskEntity;
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
import java.time.LocalDateTime;
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
    private final AiTaskService             aiTaskService;
    private final ObjectMapper              objectMapper;
    private final MomentumDecisionService   momentumDecisionService;

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
            AiTaskService aiTaskService,
            ObjectMapper objectMapper,
            MomentumDecisionService momentumDecisionService
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
        this.aiTaskService              = aiTaskService;
        this.objectMapper               = objectMapper;
        this.momentumDecisionService    = momentumDecisionService;
    }

    @Transactional
    public FinalDecisionResponse evaluateAndPersist(LocalDate tradingDate) {
        return evaluateAndPersist(tradingDate, null);
    }

    /**
     * v2.2: 指定 preferTaskType 讓各時段 Job 能消化自己的 AI task。
     * 例：PostmarketAnalysis1530Job 傳 "POSTMARKET"；T86/TomorrowPlan 傳 "T86_TOMORROW"。
     * 傳 null 時維持 v2.1 行為（OPENING 優先、PREMARKET fallback）。
     */
    @Transactional
    public FinalDecisionResponse evaluateAndPersist(LocalDate tradingDate, String preferTaskType) {
        // ── Step 0: v2.1 AI Readiness 判定（OPENING 優先，fallback PREMARKET）─────
        // 預設：require_codex=true，Codex 未完成時不得輸出正式 ENTER。
        AiReadiness readiness = resolveAiReadiness(tradingDate, preferTaskType);
        log.info("[FinalDecision] AI readiness: mode={} sourceTaskType={} taskId={} fallback={}",
                readiness.mode(), readiness.sourceTaskType(), readiness.aiTaskId(), readiness.fallbackReason());

        boolean downgradeEnabled = scoreConfigService.getBoolean("final_decision.ai_downgrade_enabled", true);
        if (downgradeEnabled) {
            if (readiness.mode() == AiReadinessMode.AI_NOT_READY) {
                String reason = readiness.fallbackReason() == null ? "AI_NOT_READY" : readiness.fallbackReason();
                log.warn("[FinalDecision] AI_NOT_READY → REST (reason={})", reason);
                return persistAndReturn(tradingDate,
                        new FinalDecisionResponse("REST", List.of(),
                                List.of(reason),
                                "AI 未完成，今日保守休息（" + reason + "）"),
                        readiness);
            }
            if (readiness.mode() == AiReadinessMode.PARTIAL_AI_READY) {
                String partialMode = scoreConfigService.getString("final_decision.partial_ai_mode", "WATCH");
                String decisionLabel = "REST".equalsIgnoreCase(partialMode) ? "REST" : "WATCH";
                String reasonCode = readiness.fallbackReason() == null ? "CODEX_MISSING" : readiness.fallbackReason();
                log.warn("[FinalDecision] PARTIAL_AI_READY → {} (reason={})", decisionLabel, reasonCode);
                return persistAndReturn(tradingDate,
                        new FinalDecisionResponse(decisionLabel, List.of(),
                                List.of(reasonCode),
                                "Claude 已完成但 Codex 尚未完成，本輪降級為"
                                        + ("WATCH".equals(decisionLabel) ? "只可觀察" : "休息")
                                        + "，不輸出正式進場標的"),
                        readiness);
            }
        }

        MarketCurrentResponse market = marketDataService.getCurrentMarket().orElse(null);
        // v2.4：只讀今日 state，避免跨日污染
        TradingStateResponse state = tradingStateService.getStateByDate(tradingDate).orElse(null);

        String marketGrade  = market == null ? "B" : safe(market.marketGrade(), "B");
        String decisionLock = state == null ? "NONE" : safe(state.decisionLock(), "NONE");
        // v2.4：timeDecay 一律依現在時間算，不採用 state 帶入值（避免 stale）
        String timeDecay    = TradingStateService.resolveTimeDecayForNow();
        log.info("[FinalDecision] marketGrade={} decisionLock={} timeDecay={} (state={})",
                marketGrade, decisionLock, timeDecay,
                state == null ? "NONE_TODAY" : "today-" + state.updatedAt());

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
                        "持倉已滿，不開新倉。"), readiness);
            }
        }

        // ── Step 0.1: 全市場冷卻檢查（連續虧損 / 當日虧損上限）──────────────
        MarketCooldownService.MarketCooldownResult marketCooldown = marketCooldownService.check();
        if (marketCooldown.blocked()) {
            log.info("[FinalDecision] 全市場冷卻: {}", marketCooldown.reason());
            return persistAndReturn(tradingDate, new FinalDecisionResponse(
                    "REST", List.of(), List.of(marketCooldown.reason()), marketCooldown.reason()),
                    readiness);
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
        List<FinalDecisionSelectedStockResponse> setupEnriched = decision.selectedStocks().stream()
                .map(s -> enrichWithSizing(s, marketGrade, candidateMap.get(s.stockCode()),
                        baseCapital, maxSingle))
                .toList();

        // ── v2.3 Momentum Chase 平行 pipeline ─────────────────────────────────
        java.util.Set<String> setupSymbols = new java.util.HashSet<>();
        for (FinalDecisionSelectedStockResponse s : setupEnriched) setupSymbols.add(s.stockCode());

        int remainingSlots = Math.max(0, maxPos - openPositions.size() - setupEnriched.size());
        List<FinalDecisionSelectedStockResponse> momentumEnriched = new java.util.ArrayList<>();
        List<String> momentumSummary = new java.util.ArrayList<>();

        if (remainingSlots > 0) {
            MomentumDecisionService.MomentumResultBundle mb = momentumDecisionService.evaluate(
                    tradingDate, scoredCandidates, marketGrade, decisionLock, timeDecay);
            for (MomentumDecisionService.MomentumPick pick : mb.picks()) {
                if (momentumEnriched.size() >= remainingSlots) break;
                if (setupSymbols.contains(pick.selected().stockCode())) continue; // SETUP 優先，去重
                FinalDecisionSelectedStockResponse enriched = enrichMomentumSizing(
                        pick.selected(), candidateMap.get(pick.selected().stockCode()),
                        baseCapital, maxSingle, pick);
                momentumEnriched.add(enriched);
                momentumSummary.add(String.format("%s(score=%s)",
                        pick.selected().stockCode(), pick.momentumScore().toPlainString()));
            }
            if (mb.observationMode() && mb.enterThresholdCount() > 0) {
                log.info("[FinalDecision] Momentum observation mode: {} stocks ≥ entry threshold but held as WATCH only",
                        mb.enterThresholdCount());
            }
        }

        // 合併 selected_stocks（Setup 在前、Momentum 在後）
        List<FinalDecisionSelectedStockResponse> merged = new java.util.ArrayList<>(setupEnriched);
        merged.addAll(momentumEnriched);

        // 決定最終 decision 與 strategy_type
        String finalDecisionCode = decision.decision();
        String strategyType;
        if (!setupEnriched.isEmpty() && !momentumEnriched.isEmpty()) {
            strategyType = "MIXED";
            finalDecisionCode = "ENTER";
        } else if (!setupEnriched.isEmpty()) {
            strategyType = "SETUP";
        } else if (!momentumEnriched.isEmpty()) {
            strategyType = "MOMENTUM_CHASE";
            finalDecisionCode = "ENTER";
        } else {
            strategyType = "SETUP"; // REST/WATCH 預設 SETUP
        }

        // 擴充 summary：Momentum 加註追價提示
        String summary = decision.summary();
        if (!momentumEnriched.isEmpty()) {
            summary = (summary == null ? "" : summary + "  ") +
                    "含 " + momentumEnriched.size() + " 檔 Momentum 追價單（" +
                    String.join(",", momentumSummary) + "）；倉位已壓低、停損收緊。";
        }

        FinalDecisionResponse enrichedDecision = new FinalDecisionResponse(
                finalDecisionCode, merged, decision.rejectedReasons(), summary);

        // v2.2: 走 persistAndReturn 確保「產生 FinalDecision → finalize AI task」原子執行
        return persistAndReturnWithStrategy(tradingDate, enrichedDecision, readiness, strategyType);
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
    private FinalDecisionResponse persistAndReturn(LocalDate tradingDate, FinalDecisionResponse response,
                                                    AiReadiness readiness) {
        return persistAndReturnWithStrategy(tradingDate, response, readiness, "SETUP");
    }

    /** v2.3：帶 strategyType 的持久化版本 */
    private FinalDecisionResponse persistAndReturnWithStrategy(LocalDate tradingDate,
                                                                FinalDecisionResponse response,
                                                                AiReadiness readiness,
                                                                String strategyType) {
        FinalDecisionEntity entity = new FinalDecisionEntity();
        entity.setTradingDate(tradingDate);
        entity.setDecision(response.decision());
        entity.setSummary(response.summary());
        entity.setPayloadJson(toPayload(response));
        entity.setStrategyType(strategyType);
        fillAiContext(entity, readiness);
        finalDecisionRepository.save(entity);

        // v2.1/v2.2: 最終決策產出後，標記來源 AI task 為 FINALIZED（只對 CODEX_DONE 有效）
        if (readiness != null && readiness.aiTaskId() != null
                && readiness.mode() == AiReadinessMode.FULL_AI_READY) {
            try {
                aiTaskService.finalizeTask(readiness.aiTaskId(), "final-decision-consumed");
                log.info("[FinalDecisionService] finalized AI task id={} type={}",
                        readiness.aiTaskId(), readiness.sourceTaskType());
            } catch (Exception e) {
                log.warn("[FinalDecisionService] finalizeTask({}) skipped: {}",
                        readiness.aiTaskId(), e.getMessage());
            }
        }
        return response;
    }

    /** 把 AiReadiness 資訊寫入 FinalDecisionEntity 以便追溯 */
    private void fillAiContext(FinalDecisionEntity entity, AiReadiness readiness) {
        if (readiness == null) return;
        entity.setAiTaskId(readiness.aiTaskId());
        entity.setAiStatus(readiness.mode() == null ? null : readiness.mode().name());
        entity.setFallbackReason(readiness.fallbackReason());
        entity.setSourceTaskType(readiness.sourceTaskType());
        entity.setClaudeDoneAt(readiness.claudeDoneAt());
        entity.setCodexDoneAt(readiness.codexDoneAt());
    }

    // ── v2.1 AI Readiness ────────────────────────────────────────────────────

    /** AI 準備度結果 */
    public record AiReadiness(
            AiReadinessMode mode,
            Long aiTaskId,
            String sourceTaskType,
            LocalDateTime claudeDoneAt,
            LocalDateTime codexDoneAt,
            String fallbackReason
    ) {}

    public enum AiReadinessMode { FULL_AI_READY, PARTIAL_AI_READY, AI_NOT_READY }

    /**
     * v2.1 AI Readiness 判定。09:30 FinalDecision 優先讀 OPENING，fallback PREMARKET。
     * <ul>
     *     <li>兩者都 CODEX_DONE → FULL_AI_READY</li>
     *     <li>僅 CLAUDE_DONE → PARTIAL_AI_READY（Codex missing）</li>
     *     <li>都沒有或 Claude 未完成 → AI_NOT_READY</li>
     * </ul>
     */
    private AiReadiness resolveAiReadiness(LocalDate tradingDate) {
        return resolveAiReadiness(tradingDate, null);
    }

    /**
     * v2.2: 支援 preferTaskType。
     * 傳 null → 維持原預設（OPENING 優先、PREMARKET fallback）。
     * 傳 "POSTMARKET"/"T86_TOMORROW"/"MIDDAY"/"PREMARKET"/"OPENING" → 指定來源；若該 taskType 無 task
     * 則按預設順序 fallback。
     */
    private AiReadiness resolveAiReadiness(LocalDate tradingDate, String preferTaskType) {
        String[] order;
        if (preferTaskType != null && !preferTaskType.isBlank()) {
            order = new String[]{ preferTaskType.toUpperCase(),
                    "OPENING", "PREMARKET", "POSTMARKET", "MIDDAY", "T86_TOMORROW" };
        } else {
            order = new String[]{ "OPENING", "PREMARKET" };
        }

        AiTaskEntity task = null;
        String sourceType = null;
        for (String type : order) {
            task = findPrimaryTask(tradingDate, type);
            if (task != null) { sourceType = type; break; }
        }

        if (task == null) {
            return new AiReadiness(AiReadinessMode.AI_NOT_READY, null, null,
                    null, null, AiTaskErrorCode.AI_NOT_READY.name());
        }

        String status = task.getStatus();
        boolean claudeDone = AiTaskService.STATUS_CLAUDE_DONE.equals(status)
                || AiTaskService.STATUS_CODEX_RUNNING.equals(status)
                || AiTaskService.STATUS_CODEX_DONE.equals(status)
                || AiTaskService.STATUS_FINALIZED.equals(status);
        boolean codexDone = AiTaskService.STATUS_CODEX_DONE.equals(status)
                || AiTaskService.STATUS_FINALIZED.equals(status);

        if (codexDone) {
            return new AiReadiness(AiReadinessMode.FULL_AI_READY, task.getId(), sourceType,
                    task.getClaudeDoneAt(), task.getCodexDoneAt(), null);
        }
        if (claudeDone) {
            return new AiReadiness(AiReadinessMode.PARTIAL_AI_READY, task.getId(), sourceType,
                    task.getClaudeDoneAt(), null, AiTaskErrorCode.CODEX_MISSING.name());
        }
        return new AiReadiness(AiReadinessMode.AI_NOT_READY, task.getId(), sourceType,
                null, null, AiTaskErrorCode.AI_NOT_READY.name());
    }

    /** 找當日指定 taskType 最新的 non-terminal task */
    private AiTaskEntity findPrimaryTask(LocalDate date, String taskType) {
        return aiTaskService.getByDate(date).stream()
                .filter(t -> taskType.equalsIgnoreCase(t.getTaskType()))
                .filter(t -> !AiTaskService.STATUS_EXPIRED.equals(t.getStatus())
                          && !AiTaskService.STATUS_FAILED.equals(t.getStatus()))
                .findFirst()  // getByDate 已 order by createdAt desc
                .orElse(null);
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
     * v2.3 Momentum 專屬 sizing：
     * 1. 倉位 = baseCapital × momentum.position_size_ratio（或 strong_position_ratio）
     * 2. 停損：entry × (1 + momentum.stop_loss_pct)；比 Setup 停損更緊則覆寫
     * 3. TP1/TP2 由 momentum.take_profit_*_pct 計算
     */
    private FinalDecisionSelectedStockResponse enrichMomentumSizing(
            FinalDecisionSelectedStockResponse stock,
            FinalDecisionCandidateRequest candidate,
            double baseCapital, double maxSingle,
            MomentumDecisionService.MomentumPick pick
    ) {
        double multiplier = stock.positionMultiplier() != null
                ? stock.positionMultiplier()
                : scoreConfigService.getDecimal("momentum.position_size_ratio",
                        new BigDecimal("0.5")).doubleValue();
        double suggested = Math.min(baseCapital * multiplier, maxSingle * multiplier);
        // 保守下限：不低於 1 萬（避免買不到 1 張）
        if (suggested < 10_000 && baseCapital >= 20_000) suggested = 10_000;

        // 從 entryPriceZone 或 stopLoss 推 entry
        double entry = parseEntryFromZone(stock.entryPriceZone());
        double stopLossPct = scoreConfigService.getDecimal("momentum.stop_loss_pct",
                new BigDecimal("-0.025")).doubleValue();
        double tp1Pct = scoreConfigService.getDecimal("momentum.take_profit_1_pct",
                new BigDecimal("0.06")).doubleValue();
        double tp2Pct = scoreConfigService.getDecimal("momentum.take_profit_2_pct",
                new BigDecimal("0.10")).doubleValue();

        Double finalSl = stock.stopLossPrice();
        Double finalTp1 = stock.takeProfit1();
        Double finalTp2 = stock.takeProfit2();
        if (entry > 0) {
            double momentumSl = round2(entry * (1 + stopLossPct));
            // 比 Setup 停損更緊則覆寫（讓 Momentum 更快認錯）
            if (finalSl == null || finalSl < momentumSl) finalSl = momentumSl;
            if (finalTp1 == null) finalTp1 = round2(entry * (1 + tp1Pct));
            if (finalTp2 == null) finalTp2 = round2(entry * (1 + tp2Pct));
        }

        return new FinalDecisionSelectedStockResponse(
                stock.stockCode(), stock.stockName(),
                "MOMENTUM",
                stock.entryPriceZone(),
                finalSl, finalTp1, finalTp2,
                stock.riskRewardRatio(),
                stock.rationale(),
                suggested,
                multiplier,
                StrategyType.MOMENTUM_CHASE.name(),
                stock.momentumScore()
        );
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
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
                entity.getCreatedAt(),
                entity.getAiTaskId(),
                entity.getAiStatus(),
                entity.getFallbackReason(),
                entity.getSourceTaskType(),
                entity.getClaudeDoneAt(),
                entity.getCodexDoneAt()
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
