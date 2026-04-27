package com.austin.trading.service;

import com.austin.trading.dto.internal.ExecutionDecisionInput;
import com.austin.trading.dto.internal.ExecutionTimingDecision;
import com.austin.trading.dto.internal.ThemeStrengthDecision;
import com.austin.trading.dto.internal.MarketRegimeDecision;
import com.austin.trading.dto.internal.PortfolioRiskDecision;
import com.austin.trading.dto.internal.RankedCandidate;
import com.austin.trading.dto.internal.SetupDecision;
import com.austin.trading.dto.internal.TimingEvaluationInput;
import com.austin.trading.dto.request.CodexResultPayloadRequest;
import com.austin.trading.dto.request.CodexReviewedSymbolRequest;
import com.austin.trading.dto.request.FinalDecisionCandidateRequest;
import com.austin.trading.dto.request.FinalDecisionEvaluateRequest;
import com.austin.trading.dto.request.MarketSnapshotCreateRequest;
import com.austin.trading.domain.enums.MarketSession;
import com.austin.trading.domain.enums.StrategyType;
import com.austin.trading.dto.request.PositionSizingEvaluateRequest;
import com.austin.trading.dto.request.StopLossTakeProfitEvaluateRequest;
import com.austin.trading.dto.request.TradingStateUpsertRequest;
import com.austin.trading.dto.response.FinalDecisionRecordResponse;
import com.austin.trading.dto.response.FinalDecisionResponse;
import com.austin.trading.dto.response.FinalDecisionSelectedStockResponse;
import com.austin.trading.dto.response.MarketCurrentResponse;
import com.austin.trading.dto.response.PositionSizingResponse;
import com.austin.trading.dto.response.StopLossTakeProfitResponse;
import com.austin.trading.dto.response.TradingStateResponse;
import com.austin.trading.dto.internal.CapitalAllocationResult;
import com.austin.trading.dto.internal.PriceGateDecision;
import com.austin.trading.engine.ConsensusScoringEngine;
import com.austin.trading.engine.FinalDecisionEngine;
import com.austin.trading.engine.JavaStructureScoringEngine;
import com.austin.trading.engine.PositionSizingEngine;
import com.austin.trading.engine.PriceGateEvaluator;
import com.austin.trading.engine.StopLossTakeProfitEngine;
import com.austin.trading.engine.VetoEngine;
import com.austin.trading.engine.WeightedScoringEngine;
import com.austin.trading.entity.AiTaskEntity;
import com.austin.trading.entity.FinalDecisionEntity;
import com.austin.trading.entity.PositionEntity;
import com.austin.trading.entity.StockEvaluationEntity;
import com.austin.trading.entity.WatchlistStockEntity;
import com.austin.trading.repository.FinalDecisionRepository;
import com.austin.trading.repository.PositionRepository;
import com.austin.trading.repository.StockEvaluationRepository;
import com.austin.trading.repository.WatchlistStockRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.austin.trading.event.FinalDecisionPersistedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class FinalDecisionService {

    private static final Logger log = LoggerFactory.getLogger(FinalDecisionService.class);
    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Taipei");

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
    private final MarketRegimeService       marketRegimeService;
    private final StockRankingService       stockRankingService;
    private final SetupValidationService    setupValidationService;
    private final ExecutionTimingService    executionTimingService;
    private final PortfolioRiskService      portfolioRiskService;
    private final ExecutionDecisionService  executionDecisionService;
    private final ThemeStrengthService      themeStrengthService;
    private final PriceGateEvaluator        priceGateEvaluator;
    private final IntradayVwapService       intradayVwapService;
    private final VolumeProfileService      volumeProfileService;
    private final CapitalAllocationService  capitalAllocationService;
    private final ThemeGateOrchestrator     themeGateOrchestrator;
    private final ThemeShadowModeService    themeShadowModeService;
    private final ThemeShadowReportService  themeShadowReportService;
    private final ThemeLiveDecisionService  themeLiveDecisionService;
    private final ThemeLineSummaryService   themeLineSummaryService;
    private final ApplicationEventPublisher events;

    // v2.8 Grace Period：cron 觸發後，給 Claude/Codex 一個等待窗口完成研究，
    // 避免 09:30/15:30 立即判 AI_NOT_READY 發出假警報。
    @Value("${trading.ai.grace-period-seconds:300}")
    private long aiGracePeriodSeconds;
    @Value("${trading.ai.grace-poll-interval-seconds:30}")
    private long aiGracePollIntervalSeconds;

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
            MomentumDecisionService momentumDecisionService,
            MarketRegimeService marketRegimeService,
            StockRankingService stockRankingService,
            SetupValidationService setupValidationService,
            ExecutionTimingService executionTimingService,
            PortfolioRiskService portfolioRiskService,
            ExecutionDecisionService executionDecisionService,
            ThemeStrengthService themeStrengthService,
            PriceGateEvaluator priceGateEvaluator,
            IntradayVwapService intradayVwapService,
            VolumeProfileService volumeProfileService,
            CapitalAllocationService capitalAllocationService,
            ThemeGateOrchestrator themeGateOrchestrator,
            ThemeShadowModeService themeShadowModeService,
            ThemeShadowReportService themeShadowReportService,
            ThemeLiveDecisionService themeLiveDecisionService,
            ThemeLineSummaryService themeLineSummaryService,
            ApplicationEventPublisher events
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
        this.marketRegimeService        = marketRegimeService;
        this.stockRankingService        = stockRankingService;
        this.setupValidationService     = setupValidationService;
        this.executionTimingService     = executionTimingService;
        this.portfolioRiskService       = portfolioRiskService;
        this.executionDecisionService   = executionDecisionService;
        this.themeStrengthService       = themeStrengthService;
        this.priceGateEvaluator         = priceGateEvaluator;
        this.intradayVwapService        = intradayVwapService;
        this.volumeProfileService       = volumeProfileService;
        this.capitalAllocationService   = capitalAllocationService;
        this.themeGateOrchestrator      = themeGateOrchestrator;
        this.themeShadowModeService     = themeShadowModeService;
        this.themeShadowReportService   = themeShadowReportService;
        this.themeLiveDecisionService   = themeLiveDecisionService;
        this.themeLineSummaryService    = themeLineSummaryService;
        this.events                     = events;
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
        CodexExecutionContext codexContext = loadCodexExecutionContext(readiness);

        if (shouldBlockPremarketTrade(readiness, codexContext)) {
            DecisionTrace trace = buildDecisionTrace(
                    codexContext, "WAIT", "PREMARKET_BIAS_ONLY", false, false);
            return persistAndReturn(tradingDate,
                    withDecisionTrace(new FinalDecisionResponse(
                            "WAIT",
                            List.of(),
                            List.of("PREMARKET_BIAS_ONLY"),
                            "PREMARKET 只提供市場偏向與候選觀察，不可作為正式進場判斷。"),
                            trace),
                    readiness);
        }

        boolean requireCodex = scoreConfigService.getBoolean("final_decision.require_codex", true);
        boolean downgradeEnabled = scoreConfigService.getBoolean("final_decision.ai_downgrade_enabled", true);
        if (requireCodex || downgradeEnabled) {
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
                log.warn("[FinalDecision] PARTIAL_AI_READY → {} (reason={} requireCodex={})",
                        decisionLabel, reasonCode, requireCodex);
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

        // ── Step 0: Portfolio-gate Risk Check (P0.5) ─────────────────────────
        List<PositionEntity> openPositions = positionRepository.findByStatus("OPEN");
        int maxPos = scoreConfigService.getInt("portfolio.max_open_positions", 3);
        boolean hasPosition = !openPositions.isEmpty();

        PortfolioRiskDecision portfolioGate =
                portfolioRiskService.evaluatePortfolioGate(openPositions, tradingDate);
        if (!portfolioGate.approved()) {
            String reason = portfolioGate.blockReason() + " (" + portfolioGate.openPositionCount()
                    + "/" + portfolioGate.maxPositions() + ")";
            log.info("[FinalDecision] portfolio gate blocked: {}", reason);
            DecisionTrace trace = buildDecisionTrace(
                    codexContext, !codexContext.buyNowSymbols().isEmpty() ? "BLOCKED" : "REST",
                    reason, !codexContext.buyNowSymbols().isEmpty(), false);
            return persistAndReturn(tradingDate, new FinalDecisionResponse(
                    "REST", List.of(), List.of(reason), reason, Map.of("decisionTrace", trace.payload())), readiness);
        }

        // ── Step 0.1: 全市場冷卻檢查（連續虧損 / 當日虧損上限）──────────────
        MarketCooldownService.MarketCooldownResult marketCooldown = marketCooldownService.check();
        if (marketCooldown.blocked()) {
            log.info("[FinalDecision] 全市場冷卻: {}", marketCooldown.reason());
            DecisionTrace trace = buildDecisionTrace(
                    codexContext, "REST", marketCooldown.reason(), !codexContext.buyNowSymbols().isEmpty(), false);
            return persistAndReturn(tradingDate, new FinalDecisionResponse(
                    "REST", List.of(), List.of(marketCooldown.reason()), marketCooldown.reason(),
                    Map.of("decisionTrace", trace.payload())),
                    readiness);
        }

        // ── Step 0.2: Regime Gate (P0.1) — PANIC_VOLATILITY blocks all new entries ──
        MarketRegimeDecision regime = marketRegimeService.getLatestForToday()
                .or(marketRegimeService::getLatest)
                .orElse(null);
        if (regime != null && !regime.tradeAllowed()) {
            String reason = "REGIME_BLOCKED: " + regime.regimeType()
                    + " (riskMultiplier=" + regime.riskMultiplier() + ")";
            log.info("[FinalDecision] {}", reason);
            DecisionTrace trace = buildDecisionTrace(
                    codexContext, "REST", reason, !codexContext.buyNowSymbols().isEmpty(), false);
            return persistAndReturn(tradingDate, new FinalDecisionResponse(
                    "REST", List.of(), List.of(reason), regime.summary(),
                    Map.of("decisionTrace", trace.payload())), readiness);
        }
        if (regime != null) {
            log.info("[FinalDecision] regime={} riskMultiplier={} tradeAllowed={}",
                    regime.regimeType(), regime.riskMultiplier(), regime.tradeAllowed());
        }

        int maxCount = scoreConfigService.getInt("candidate.scan.maxCount", 8);
        List<FinalDecisionCandidateRequest> rawCandidates =
                candidateScanService.loadFinalDecisionCandidates(tradingDate, maxCount);
        // v2.9.1：overlay 期間同步把 VWAP/volume extras 累加到 symbol->map，trace 層合併用。
        Map<String, Map<String, Object>> priceGateExtrasBySymbol = new LinkedHashMap<>();
        rawCandidates = applyCodexExecutionOverlay(rawCandidates, codexContext, priceGateExtrasBySymbol);
        rawCandidates = applyMarketRegime(rawCandidates, regime);
        rawCandidates = applyCodexActionableGate(rawCandidates, readiness, codexContext);

        // v2.9 Gate 6/7：預先算每檔的 priceGate trace，buildDecisionTrace 時就能直接塞回 trace。
        MarketSession currentSession = MarketSession.fromTime(LocalTime.now(MARKET_ZONE));
        Map<String, Map<String, Object>> priceGateTraceMap = new LinkedHashMap<>();
        for (FinalDecisionCandidateRequest c : rawCandidates) {
            PriceGateDecision pg = priceGateEvaluator.evaluate(c, currentSession);
            Map<String, Object> baseTrace = new LinkedHashMap<>(pg.trace());
            Map<String, Object> extras = priceGateExtrasBySymbol.get(c.stockCode());
            if (extras != null) {
                baseTrace.putAll(extras);
            }
            priceGateTraceMap.put(c.stockCode(), baseTrace);
        }

        FinalDecisionResponse codexShortCircuit = buildCodexShortCircuitDecision(
                readiness, codexContext, rawCandidates);
        if (codexShortCircuit != null) {
            return persistAndReturn(tradingDate, codexShortCircuit, readiness);
        }

        // ── 評分管線：JavaStructure → Veto → WeightedScore ────────────────────
        List<FinalDecisionCandidateRequest> scoredCandidates =
                applyScoringPipeline(rawCandidates, tradingDate, marketGrade, decisionLock, timeDecay);

        // ── Step 0.55: Theme Strength Layer (P1.1) — tradability / stage / decay ──
        Map<String, ThemeStrengthDecision> themeDecisions =
                themeStrengthService.evaluateAll(tradingDate, regime);

        // ── Step 0.5: Ranking Layer (P0.2→P1.1) — held / cooldown / veto / selectionScore ──
        List<RankedCandidate> ranked = stockRankingService.rank(scoredCandidates, tradingDate, regime, themeDecisions);
        Map<String, RankedCandidate> rankMap = ranked.stream()
                .collect(Collectors.toMap(RankedCandidate::symbol, r -> r, (a, b) -> a));

        scoredCandidates = scoredCandidates.stream()
                .filter(c -> {
                    RankedCandidate r = rankMap.get(c.stockCode());
                    if (r == null || !r.eligibleForSetup()) {
                        if (codexContext.buyNowSymbols().contains(c.stockCode())) {
                            return true;
                        }
                        String why = r == null ? "NOT_IN_RANKING" : r.rejectionReason();
                        log.info("[FinalDecision] 排除 {} — {}", c.stockCode(), why);
                        return false;
                    }
                    return true;
                })
                .toList();

        // ── Step 0.6: Score gap 保護 — 新倉需高出 STRONG 持股最低分 ──────────────
        // NOTE: STRONG positions are alreadyHeld=true → StockRankingEngine sets their
        // selectionScore to 0. Must NOT look them up in rankMap; use stockEvaluation DB
        // (which holds finalRankScore from the scoring pipeline) as the reference score.
        BigDecimal scoreGap = scoreConfigService.getDecimal("portfolio.replace_strong_score_gap", new BigDecimal("1.5"));
        BigDecimal strongMinScore = openPositions.stream()
                .filter(p -> "STRONG".equals(p.getReviewStatus()))
                .map(p -> stockEvaluationRepository
                        .findByTradingDateAndSymbol(tradingDate, p.getSymbol())
                        .map(StockEvaluationEntity::getFinalRankScore).orElse(BigDecimal.ZERO))
                .min(BigDecimal::compareTo).orElse(BigDecimal.ZERO);

        if (strongMinScore.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal requiredMin = strongMinScore.add(scoreGap);
            scoredCandidates = scoredCandidates.stream().filter(c -> {
                if (codexContext.buyNowSymbols().contains(c.stockCode())) {
                    return true;
                }
                RankedCandidate r = rankMap.get(c.stockCode());
                BigDecimal sel = r != null ? r.selectionScore() : c.finalRankScore();
                if (sel != null && sel.compareTo(requiredMin) < 0) {
                    log.info("[FinalDecision] 排除 {} — selectionScore {} 未超過 STRONG 持股最低分+gap ({})",
                            c.stockCode(), sel, requiredMin);
                    return false;
                }
                return true;
            }).toList();
        }

        // ── Step 0.7: Timing Layer (P0.4) — score alone cannot bypass timing ──────
        // 1. Generate setup for any candidate that doesn't have one in DB yet.
        //    (If a pre-market job already ran SetupValidationService, this is a no-op for those symbols.)
        // 2. Evaluate timing window using setup + intraday signal flags.
        Map<String, SetupDecision> setupMap =
                buildOrLoadSetups(scoredCandidates, rankMap, regime, tradingDate, themeDecisions);

        List<TimingEvaluationInput> timingInputs = scoredCandidates.stream()
                .map(c -> new TimingEvaluationInput(
                        rankMap.get(c.stockCode()),
                        setupMap.get(c.stockCode()),        // null → timing will block (NO_SETUP)
                        regime,
                        Boolean.TRUE.equals(c.nearDayHigh()),
                        Boolean.TRUE.equals(c.belowOpen()),
                        Boolean.TRUE.equals(c.belowPrevClose()),
                        Boolean.TRUE.equals(c.entryTriggered()),
                        Boolean.TRUE.equals(c.volumeSpike()),
                        0                                   // fresh signal = evaluated today
                ))
                .toList();

        List<ExecutionTimingDecision> timings =
                executionTimingService.evaluateAll(timingInputs, tradingDate);
        Map<String, ExecutionTimingDecision> timingMap = timings.stream()
                .collect(Collectors.toMap(ExecutionTimingDecision::symbol, t -> t, (a, b) -> a));

        scoredCandidates = scoredCandidates.stream()
                .filter(c -> {
                    ExecutionTimingDecision t = timingMap.get(c.stockCode());
                    if (t == null || !t.approved()) {
                        if (codexContext.buyNowSymbols().contains(c.stockCode())) {
                            return true;
                        }
                        String why = t == null ? "NO_TIMING_DECISION" : t.rejectionReason();
                        log.info("[FinalDecision] 排除 {} — timing: {}", c.stockCode(), why);
                        return false;
                    }
                    return true;
                })
                .toList();

        // ── Step 0.8: Per-candidate Risk Layer (P0.5) ─────────────────────────
        // size is computed only after hard checks pass (Codex gate C)
        List<RankedCandidate> timingPassedRanked = scoredCandidates.stream()
                .map(c -> rankMap.get(c.stockCode()))
                .filter(Objects::nonNull)
                .toList();

        List<PortfolioRiskDecision> riskDecisions =
                portfolioRiskService.evaluateCandidates(timingPassedRanked, openPositions, tradingDate);
        Map<String, PortfolioRiskDecision> riskMap = riskDecisions.stream()
                .collect(Collectors.toMap(
                        PortfolioRiskDecision::symbol,
                        r -> r, (a, b) -> a));

        scoredCandidates = scoredCandidates.stream()
                .filter(c -> {
                    PortfolioRiskDecision r = riskMap.get(c.stockCode());
                    if (r == null || !r.approved()) {
                        String why = r == null ? "NO_RISK_DECISION" : r.blockReason();
                        log.info("[FinalDecision] 排除 {} — risk: {}", c.stockCode(), why);
                        return false;
                    }
                    return true;
                })
                .toList();

        // v2.8：依 AI source taskType 決定盤中 vs 盤後規劃模式
        if (shouldUseCodexBuyNowOnly(readiness, codexContext)) {
            List<FinalDecisionCandidateRequest> codexPreferred = scoredCandidates.stream()
                    .filter(c -> codexContext.buyNowSymbols().contains(c.stockCode()))
                    .sorted(Comparator.comparing(
                            (FinalDecisionCandidateRequest c) -> codexContext.reviewIndex().getOrDefault(c.stockCode(), 999)))
                    .toList();
            if (!codexPreferred.isEmpty()) {
                scoredCandidates = codexPreferred;
                log.info("[FinalDecision] Use Codex execution-priority candidates only: {}",
                        scoredCandidates.stream().map(FinalDecisionCandidateRequest::stockCode).toList());
            }
        }

        com.austin.trading.domain.enums.DecisionPlanningMode planningMode =
                com.austin.trading.domain.enums.DecisionPlanningMode.fromTaskType(
                        readiness == null ? null : readiness.sourceTaskType());

        // 盤後規劃模式忽略 decisionLock（日內 gate 不應污染明日規劃）
        String effectiveDecisionLock = planningMode.isPostClosePlanning()
                ? "NONE"
                : decisionLock;

        FinalDecisionEvaluateRequest request = new FinalDecisionEvaluateRequest(
                marketGrade,
                effectiveDecisionLock,
                timeDecay,
                hasPosition,
                scoredCandidates
        );

        log.info("[FinalDecision] planningMode={} sourceTaskType={} effectiveDecisionLock={}",
                planningMode, readiness == null ? null : readiness.sourceTaskType(), effectiveDecisionLock);

        FinalDecisionResponse decision = finalDecisionEngine.evaluate(
                request,
                MarketSession.fromTime(LocalTime.now(MARKET_ZONE)),
                planningMode);

        // 建立 candidateMap 以便回查估值模式
        Map<String, FinalDecisionCandidateRequest> candidateMap = scoredCandidates.stream()
                .collect(Collectors.toMap(FinalDecisionCandidateRequest::stockCode, c -> c, (a, b) -> a));

        // v3：可動用現金由 ledger 推導（cashBalance − reservedCash），不再讀 capital_config.available_cash
        double availCash = capitalService.getAvailableCash().doubleValue();
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

        // ── Step 0.9: Execution Layer (P0.6) — log ENTER/SKIP after Momentum merge ──
        // enterSymbols must be derived from the fully merged list so Momentum ENTER
        // candidates are not incorrectly logged as SKIP.
        {
            Set<String> enterSymbols = merged.stream()
                    .map(FinalDecisionSelectedStockResponse::stockCode)
                    .collect(Collectors.toSet());
            List<ExecutionDecisionInput> execInputs = scoredCandidates.stream()
                    .map(c -> new ExecutionDecisionInput(
                            rankMap.get(c.stockCode()),
                            enterSymbols.contains(c.stockCode()) ? "ENTER" : "SKIP",
                            regime,
                            setupMap.get(c.stockCode()),
                            timingMap.get(c.stockCode()),
                            riskMap.get(c.stockCode())
                    )).toList();
            executionDecisionService.logDecisions(execInputs, tradingDate);
        }

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

        // v2.8：保留原 decision 的 planningPayload（盤後規劃模式用）
        boolean softPenaltySkipped = shouldUseCodexBuyNowOnly(readiness, codexContext)
                && !codexContext.buyNowSymbols().isEmpty();
        boolean hardRiskBlocked = hasCodexExecutionPriorityHardRiskBlock(readiness, codexContext, riskMap);
        String traceFinalAction = hardRiskBlocked ? "BLOCKED" : finalDecisionCode;
        String traceFinalReason = resolveDecisionTraceReason(codexContext, riskMap, hardRiskBlocked, summary);
        DecisionTrace trace = buildDecisionTrace(
                codexContext, traceFinalAction, traceFinalReason, hardRiskBlocked, softPenaltySkipped,
                priceGateTraceMap);

        // v2.11 Capital Allocation：對 ENTER 的每檔算建議金額 / 股數 / mode，寫入 decisionTrace.allocations
        List<Map<String, Object>> allocationTrace = new ArrayList<>();
        if ("ENTER".equalsIgnoreCase(finalDecisionCode)) {
            String regimeType = regime != null ? regime.regimeType() : null;
            for (FinalDecisionSelectedStockResponse s : merged) {
                FinalDecisionCandidateRequest c = candidateMap.get(s.stockCode());
                if (c == null) continue;
                try {
                    BigDecimal[] zone = parseEntryZone(s.entryPriceZone());
                    BigDecimal entry = (zone != null && zone.length > 0) ? zone[0] : c.currentPrice();
                    BigDecimal stop = s.stopLossPrice() == null ? null : BigDecimal.valueOf(s.stopLossPrice());
                    BigDecimal target = s.takeProfit1() == null ? null : BigDecimal.valueOf(s.takeProfit1());
                    BigDecimal current = c.currentPrice();
                    String bucketStr = codexContext.reviewedBySymbol().get(c.stockCode()) != null
                            ? codexContext.reviewedBySymbol().get(c.stockCode()).bucket() : null;
                    CapitalAllocationResult allocation = capitalAllocationService.allocateForEntry(
                            c.stockCode(), /*theme*/ null, bucketStr, c.finalRankScore(),
                            entry, current, stop, target, regimeType);
                    Map<String, Object> entryTrace = new LinkedHashMap<>();
                    entryTrace.put("symbol", c.stockCode());
                    entryTrace.put("action", allocation.action().name());
                    entryTrace.put("mode", allocation.mode() == null ? null : allocation.mode().name());
                    entryTrace.put("suggestedAmount", allocation.suggestedAmount());
                    entryTrace.put("suggestedShares", allocation.suggestedShares());
                    entryTrace.put("riskPerShare", allocation.riskPerShare());
                    entryTrace.put("maxLossAmount", allocation.maxLossAmount());
                    entryTrace.put("estimatedLossAmount", allocation.estimatedLossAmount());
                    entryTrace.put("positionPctOfEquity", allocation.positionPctOfEquity());
                    entryTrace.put("reasons", allocation.reasons());
                    entryTrace.put("warnings", allocation.warnings());
                    allocationTrace.add(entryTrace);
                } catch (Exception e) {
                    log.warn("[FinalDecision] allocation failed symbol={}: {}", c.stockCode(), e.getMessage());
                }
            }
        }
        Map<String, Object> tracePayload = new LinkedHashMap<>(trace.payload());
        if (!allocationTrace.isEmpty()) {
            tracePayload.put("allocations", allocationTrace);
        }

        // v2 Theme Engine PR4：dual-run gate trace（trace-only；flag=theme.gate.trace.enabled）
        // ⚠️ 無論結果為何，絕對不改 finalDecisionCode / merged / response；只往 trace 塞。
        // PR5 起對「全部 candidate」跑 probe（不限 ENTER），確保 shadow 6 類 diff 都能觀察到。
        try {
            List<ThemeGateOrchestrator.CandidateProbe> probes = buildThemeGateProbes(
                    candidateMap.values(), regime, codexContext,
                    openPositions.size(), maxPos, capitalService.getAvailableCash());
            ThemeGateOrchestrator.Outcome themeOutcome = themeGateOrchestrator.traceCandidates(probes);
            if (themeOutcome.active()) {
                Map<String, Object> themeTracePayload = new LinkedHashMap<>();
                themeTracePayload.put("summary", themeOutcome.summary());
                themeTracePayload.put("snapshotStatus", themeOutcome.snapshotStatus());
                themeTracePayload.put("snapshotTraceKey", themeOutcome.snapshotTraceKey());
                themeTracePayload.put("claudeStatus", themeOutcome.claudeStatus());
                themeTracePayload.put("claudeTraceKey", themeOutcome.claudeTraceKey());
                themeTracePayload.put("mergeWarnings", themeOutcome.mergeWarnings());
                themeTracePayload.put("mergeRejected", themeOutcome.mergeRejectedClaudeEntries());
                themeTracePayload.put("results", themeOutcome.results().stream().map(r -> {
                    Map<String, Object> rm = new LinkedHashMap<>();
                    rm.put("symbol", r.symbol());
                    rm.put("overall", r.overallOutcome() == null ? null : r.overallOutcome().name());
                    rm.put("themeMultiplier", r.themeMultiplier());
                    rm.put("themeFinalScore", r.themeFinalScore());
                    rm.put("themeSizeFactor", r.themeSizeFactor());
                    rm.put("gates", r.gates().stream().map(g -> Map.<String, Object>of(
                            "gate", g.gateKey(),
                            "result", g.result().name(),
                            "reason", g.reason()
                    )).toList());
                    return rm;
                }).toList());
                tracePayload.put("themeGateTrace", themeTracePayload);

                // v2 Theme Engine PR5：shadow mode（flag=theme.shadow_mode.enabled，預設關）
                // ⚠️ 仍是 trace-only；寫失敗也不影響 legacy decision
                ThemeShadowReportService.ReportResult shadowReportResult = null;
                try {
                    java.util.Set<String> selectedSymbols = merged == null ? java.util.Set.of()
                            : merged.stream()
                                    .map(FinalDecisionSelectedStockResponse::stockCode)
                                    .filter(java.util.Objects::nonNull)
                                    .collect(java.util.stream.Collectors.toSet());
                    List<ThemeShadowModeService.Input> shadowInputs = buildShadowInputs(
                            candidateMap.values(), selectedSymbols, finalDecisionCode, themeOutcome);
                    if (!shadowInputs.isEmpty()) {
                        ThemeShadowModeService.RunResult shadowRun = themeShadowModeService.record(
                                tradingDate,
                                regime == null ? null : regime.regimeType(),
                                shadowInputs);
                        if (shadowRun.active() && shadowRun.totalRecorded() > 0) {
                            shadowReportResult = themeShadowReportService.generateDaily(tradingDate);
                        }
                    }
                } catch (Exception se) {
                    log.warn("[FinalDecision] theme shadow mode failed: {}", se.getMessage());
                }

                // v2 Theme Engine PR6：Phase 3 live decision override（flag=theme.live_decision.enabled，預設關）
                // ⚠️ 開啟後 BLOCK 可改寫 legacy ENTER；WAIT 仍不介入（除非 wait_override=true）。
                // 改寫前將 legacy 原值保留到 tracePayload，rollback 靠關 flag 即回 legacy。
                ThemeLiveDecisionService.Result liveOverride = null;
                try {
                    liveOverride = themeLiveDecisionService.apply(finalDecisionCode, merged, themeOutcome);
                    if (liveOverride.changed()) {
                        // 1. 保留 legacy 原值到 tracePayload（永不覆寫）
                        tracePayload.put("legacyFinalDecisionCode", liveOverride.legacyFinalDecisionCode());
                        tracePayload.put("legacyMergedSymbols", liveOverride.legacyMerged().stream()
                                .map(FinalDecisionSelectedStockResponse::stockCode).toList());
                        tracePayload.put("themeLiveDecisionOverride", liveOverride.trace());

                        // 2. 套用覆寫到 local 變數
                        finalDecisionCode = liveOverride.finalDecisionCode();
                        merged.clear();
                        merged.addAll(liveOverride.merged());

                        // 3. 同步移除對應的 allocationTrace 項目，避免回傳含已被擋下的倉位建議
                        if (!allocationTrace.isEmpty()) {
                            java.util.Set<String> keptSymbols = merged.stream()
                                    .map(FinalDecisionSelectedStockResponse::stockCode)
                                    .collect(Collectors.toSet());
                            allocationTrace.removeIf(m -> !keptSymbols.contains(String.valueOf(m.get("symbol"))));
                            tracePayload.put("allocations", allocationTrace);
                        }
                    }
                } catch (Exception oe) {
                    log.warn("[FinalDecision] theme live decision override failed: {}", oe.getMessage());
                }

                // v2 Theme Engine PR6：LINE 摘要 formatter（flag=theme.line.summary.enabled，預設關）
                // 不實際發送 LINE；只把格式化內容 log 出來，由外部決定是否轉發（符合 CLAUDE.md 規則）。
                try {
                    themeLineSummaryService.formatDailySummary(tradingDate, shadowReportResult, liveOverride)
                            .ifPresent(text -> tracePayload.put("themeLineSummary", text));
                } catch (Exception le) {
                    log.warn("[FinalDecision] theme line summary failed: {}", le.getMessage());
                }
            }
        } catch (Exception te) {
            log.warn("[FinalDecision] theme gate trace failed: {}", te.getMessage());
            // trace 層失敗絕不影響 legacy decision
        }

        FinalDecisionResponse enrichedDecision = new FinalDecisionResponse(
                finalDecisionCode, merged, decision.rejectedReasons(), summary,
                mergePlanningPayload(decision.planningPayload(), tracePayload));

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
        syncDashboardState(tradingDate, response, readiness);

        // Phase 1 paper trade:final decision 落地後 publish event,
        // PaperTradeService 透過 @TransactionalEventListener(AFTER_COMMIT) 接手開虛擬倉。
        try {
            events.publishEvent(new FinalDecisionPersistedEvent(
                    entity.getId(),
                    tradingDate,
                    response.decision(),
                    strategyType,
                    readiness != null ? readiness.aiTaskId() : null,
                    entity.getAiStatus(),
                    entity.getSourceTaskType(),
                    response.selectedStocks()
            ));
        } catch (Exception e) {
            log.warn("[FinalDecisionService] publish FinalDecisionPersistedEvent failed: {}", e.getMessage());
        }

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

    private void syncDashboardState(LocalDate tradingDate,
                                    FinalDecisionResponse response,
                                    AiReadiness readiness) {
        try {
            Optional<TradingStateResponse> existingState = tradingStateService.getStateByDate(tradingDate);
            Optional<MarketCurrentResponse> existingMarket = marketDataService.getMarketPreferToday()
                    .filter(m -> tradingDate.equals(m.tradingDate()));
            Optional<MarketRegimeDecision> latestRegime = marketRegimeService.getLatestForToday()
                    .filter(r -> tradingDate.equals(r.tradingDate()))
                    .or(marketRegimeService::getLatest);

            String marketGrade = firstNonBlank(
                    latestRegime.map(MarketRegimeDecision::marketGrade).orElse(null),
                    existingState.map(TradingStateResponse::marketGrade).orElse(null),
                    existingMarket.map(MarketCurrentResponse::marketGrade).orElse(null),
                    "B"
            );
            String marketPhase = resolveMarketPhase(readiness);
            String timeDecayStage = TradingStateService.resolveTimeDecayForNow();
            String decisionLock = firstNonBlank(
                    existingState.map(TradingStateResponse::decisionLock).orElse(null),
                    "NONE"
            );
            String hourlyGate = firstNonBlank(
                    existingState.map(TradingStateResponse::hourlyGate).orElse(null),
                    "ON"
            );
            String monitorMode = firstNonBlank(
                    existingState.map(TradingStateResponse::monitorMode).orElse(null),
                    "WATCH"
            );
            String payloadJson = buildDashboardSyncPayload(response, readiness, marketPhase, timeDecayStage);

            marketDataService.createSnapshot(new MarketSnapshotCreateRequest(
                    tradingDate,
                    marketGrade,
                    marketPhase,
                    response.decision(),
                    payloadJson
            ));

            tradingStateService.create(new TradingStateUpsertRequest(
                    tradingDate,
                    marketGrade,
                    decisionLock,
                    timeDecayStage,
                    hourlyGate,
                    monitorMode,
                    payloadJson
            ));
        } catch (Exception e) {
            log.warn("[FinalDecisionService] dashboard sync skipped for {}: {}", tradingDate, e.getMessage(), e);
        }
    }

    private String resolveMarketPhase(AiReadiness readiness) {
        if (readiness == null || readiness.sourceTaskType() == null || readiness.sourceTaskType().isBlank()) {
            return "INTRADAY";
        }
        return switch (readiness.sourceTaskType().toUpperCase()) {
            case "PREMARKET" -> "PREMARKET";
            case "OPENING" -> "OPENING";
            case "MIDDAY" -> "MIDDAY";
            case "POSTMARKET", "T86_TOMORROW" -> "CLOSE";
            default -> "INTRADAY";
        };
    }

    private String buildDashboardSyncPayload(FinalDecisionResponse response,
                                             AiReadiness readiness,
                                             String marketPhase,
                                             String timeDecayStage) {
        String aiStatus = readiness == null || readiness.mode() == null
                ? "UNKNOWN"
                : readiness.mode().name();
        String sourceTaskType = readiness == null ? null : readiness.sourceTaskType();
        String fallbackReason = readiness == null ? null : readiness.fallbackReason();
        return "{"
                + "\"source\":\"FINAL_DECISION\","
                + "\"market_phase\":\"" + escapeJson(marketPhase) + "\","
                + "\"time_decay_stage\":\"" + escapeJson(timeDecayStage) + "\","
                + "\"decision\":\"" + escapeJson(response.decision()) + "\","
                + "\"summary\":\"" + escapeJson(response.summary()) + "\","
                + "\"ai_status\":\"" + escapeJson(aiStatus) + "\","
                + "\"source_task_type\":" + toJsonStringOrNull(sourceTaskType) + ","
                + "\"fallback_reason\":" + toJsonStringOrNull(fallbackReason)
                + "}";
    }

    private String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String toJsonStringOrNull(String value) {
        return value == null || value.isBlank()
                ? "null"
                : "\"" + escapeJson(value) + "\"";
    }

    private String escapeJson(String value) {
        if (value == null) return "";
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");
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

    private record CodexExecutionContext(
            CodexResultPayloadRequest payload,
            MarketSession marketSession,
            Map<String, CodexReviewedSymbolRequest> reviewedBySymbol,
            Set<String> buyNowSymbols,
            Set<String> rejectedSymbols,
            Map<String, Integer> reviewIndex
    ) {
        static CodexExecutionContext empty() {
            return new CodexExecutionContext(null, null, Map.of(), Set.of(), Set.of(), Map.of());
        }
    }

    private record DecisionTrace(
            Map<String, Object> payload,
            boolean executionPriority
    ) {
        static DecisionTrace empty() {
            return new DecisionTrace(Map.of(), false);
        }
    }

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
     * v2.8: 給 cron 觸發路徑用的 grace period。允許 cron 在 9:30/15:30 觸發時，
     * 等 Claude/Codex 在 grace period 內完成，再進入正式評估。
     *
     * <p>不在 @Transactional 內呼叫，避免 sleep 持有 DB 連線。</p>
     *
     * @param tradingDate    交易日
     * @param preferTaskType OPENING / POSTMARKET / T86_TOMORROW 等
     * @return true 表示 ready (FULL_AI_READY 或 PARTIAL_AI_READY)，false 表示已超時仍 AI_NOT_READY
     */
    public boolean awaitAiReadiness(LocalDate tradingDate, String preferTaskType) {
        long graceSec = Math.max(0, aiGracePeriodSeconds);
        long pollSec  = Math.max(5, aiGracePollIntervalSeconds);
        // 先做一次 fast check
        AiReadinessMode first = resolveAiReadiness(tradingDate, preferTaskType).mode();
        if (first != AiReadinessMode.AI_NOT_READY) {
            return true;
        }
        if (graceSec == 0) {
            return false;
        }
        long deadlineMs = System.currentTimeMillis() + graceSec * 1000L;
        int attempt = 1;
        log.info("[FinalDecision] AI_NOT_READY → 進入 grace period（最多等 {}s, 每 {}s 重檢） preferTaskType={}",
                graceSec, pollSec, preferTaskType);
        while (System.currentTimeMillis() < deadlineMs) {
            try {
                Thread.sleep(pollSec * 1000L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                log.warn("[FinalDecision] grace period 被中斷");
                return false;
            }
            AiReadinessMode mode = resolveAiReadiness(tradingDate, preferTaskType).mode();
            attempt++;
            if (mode != AiReadinessMode.AI_NOT_READY) {
                long waitedMs = System.currentTimeMillis() - (deadlineMs - graceSec * 1000L);
                log.info("[FinalDecision] grace period 結束 ready：mode={} 等待 {}ms 共 {} 次重檢",
                        mode, waitedMs, attempt);
                return true;
            }
        }
        log.warn("[FinalDecision] grace period 超時 ({}s) 仍 AI_NOT_READY，本輪走 REST 路徑", graceSec);
        return false;
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

    private CodexExecutionContext loadCodexExecutionContext(AiReadiness readiness) {
        if (readiness == null || readiness.aiTaskId() == null || readiness.mode() != AiReadinessMode.FULL_AI_READY) {
            return CodexExecutionContext.empty();
        }
        Optional<AiTaskEntity> taskOpt = aiTaskService.getById(readiness.aiTaskId());
        if (taskOpt.isEmpty()) {
            return CodexExecutionContext.empty();
        }
        String payloadJson = taskOpt.get().getCodexPayloadJson();
        if (payloadJson == null || payloadJson.isBlank()) {
            return CodexExecutionContext.empty();
        }
        try {
            CodexResultPayloadRequest payload = objectMapper.readValue(payloadJson, CodexResultPayloadRequest.class);
            Map<String, CodexReviewedSymbolRequest> reviewedBySymbol = new LinkedHashMap<>();
            Map<String, Integer> reviewIndex = new LinkedHashMap<>();
            Set<String> buyNowSymbols = new java.util.LinkedHashSet<>();
            Set<String> rejectedSymbols = new java.util.LinkedHashSet<>();
            int index = 0;
            for (CodexReviewedSymbolRequest item : safeItems(payload.selected())) {
                if (item == null || isBlank(item.symbol())) continue;
                reviewedBySymbol.put(item.symbol(), item);
                reviewIndex.putIfAbsent(item.symbol(), index++);
                if ("SELECT_BUY_NOW".equalsIgnoreCase(item.bucket())) {
                    buyNowSymbols.add(item.symbol());
                }
            }
            for (CodexReviewedSymbolRequest item : safeItems(payload.watchlist())) {
                if (item == null || isBlank(item.symbol())) continue;
                reviewedBySymbol.putIfAbsent(item.symbol(), item);
                reviewIndex.putIfAbsent(item.symbol(), index++);
            }
            for (CodexReviewedSymbolRequest item : safeItems(payload.rejected())) {
                if (item == null || isBlank(item.symbol())) continue;
                reviewedBySymbol.put(item.symbol(), item);
                reviewIndex.putIfAbsent(item.symbol(), index++);
                rejectedSymbols.add(item.symbol());
            }
            MarketSession payloadSession = resolveCodexMarketSession(payload, readiness);
            return new CodexExecutionContext(
                    payload,
                    payloadSession,
                    reviewedBySymbol,
                    Set.copyOf(buyNowSymbols),
                    Set.copyOf(rejectedSymbols),
                    Map.copyOf(reviewIndex));
        } catch (Exception e) {
            log.warn("[FinalDecision] Failed to parse codex payload taskId={}: {}", readiness.aiTaskId(), e.getMessage());
            return CodexExecutionContext.empty();
        }
    }

    private boolean shouldBlockPremarketTrade(AiReadiness readiness, CodexExecutionContext context) {
        if (readiness == null || context == null || context.payload() == null) {
            return false;
        }
        if (!"PREMARKET".equalsIgnoreCase(readiness.sourceTaskType())) {
            return false;
        }
        return Boolean.TRUE.equals(context.payload().noTradeDecision())
                || context.marketSession() == MarketSession.PREMARKET;
    }

    private boolean shouldUseCodexBuyNowOnly(AiReadiness readiness, CodexExecutionContext context) {
        if (readiness == null || context == null || context.buyNowSymbols().isEmpty()) {
            return false;
        }
        return "OPENING".equalsIgnoreCase(readiness.sourceTaskType())
                && context.marketSession() == MarketSession.LIVE_TRADING;
    }

    private MarketSession resolveCodexMarketSession(CodexResultPayloadRequest payload, AiReadiness readiness) {
        if (!isBlank(payload.marketSession())) {
            try {
                return MarketSession.valueOf(payload.marketSession().trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                // Fallback to reviewTime/taskType parsing.
            }
        }

        LocalTime reviewTime = null;
        if (!isBlank(payload.reviewTime())) {
            try {
                reviewTime = OffsetDateTime.parse(payload.reviewTime()).atZoneSameInstant(MARKET_ZONE).toLocalTime();
            } catch (Exception ignored) {
                try {
                    reviewTime = LocalDateTime.parse(payload.reviewTime()).toLocalTime();
                } catch (Exception ignoredAgain) {
                    reviewTime = null;
                }
            }
        }
        return MarketSession.fromTaskType(
                payload.taskType() == null ? readiness.sourceTaskType() : payload.taskType(),
                reviewTime == null ? LocalTime.now(MARKET_ZONE) : reviewTime);
    }

    private List<FinalDecisionCandidateRequest> applyCodexExecutionOverlay(
            List<FinalDecisionCandidateRequest> rawCandidates,
            CodexExecutionContext context
    ) {
        return applyCodexExecutionOverlay(rawCandidates, context, null);
    }

    /**
     * v2.9.1 Gate 6/7 強化 overload：多傳一個 {@code priceGateExtrasBySymbol} 累加器，
     * overlay 算完 VWAP / volumeRatio 後會把「來源、currentVolume、expectedVolume、reason」
     * 塞進去，供 trace 層合併使用。傳 null 則忽略（向下相容）。
     */
    private List<FinalDecisionCandidateRequest> applyCodexExecutionOverlay(
            List<FinalDecisionCandidateRequest> rawCandidates,
            CodexExecutionContext context,
            Map<String, Map<String, Object>> priceGateExtrasBySymbol
    ) {
        if (rawCandidates == null || rawCandidates.isEmpty() || context == null || context.reviewedBySymbol().isEmpty()) {
            return rawCandidates;
        }
        LocalTime now = LocalTime.now(MARKET_ZONE);
        return rawCandidates.stream()
                .filter(c -> !context.rejectedSymbols().contains(c.stockCode()))
                .map(c -> {
                    CodexReviewedSymbolRequest item = context.reviewedBySymbol().get(c.stockCode());
                    return item == null ? c : overlayCandidateWithCodexReview(c, item, now, priceGateExtrasBySymbol);
                })
                .toList();
    }

    private List<FinalDecisionCandidateRequest> applyCodexActionableGate(
            List<FinalDecisionCandidateRequest> rawCandidates,
            AiReadiness readiness,
            CodexExecutionContext context
    ) {
        if (rawCandidates == null || rawCandidates.isEmpty() || context == null || context.reviewedBySymbol().isEmpty()) {
            return rawCandidates;
        }
        if (readiness == null
                || !"OPENING".equalsIgnoreCase(readiness.sourceTaskType())
                || context.marketSession() != MarketSession.LIVE_TRADING) {
            return rawCandidates;
        }
        return rawCandidates.stream()
                .filter(c -> {
                    CodexReviewedSymbolRequest review = context.reviewedBySymbol().get(c.stockCode());
                    return review == null || "SELECT_BUY_NOW".equalsIgnoreCase(review.bucket());
                })
                .toList();
    }

    /** 舊 2-arg overload — 保留給其他呼叫者用（e.g. 單 candidate 單測）。 */
    private FinalDecisionCandidateRequest overlayCandidateWithCodexReview(
            FinalDecisionCandidateRequest c,
            CodexReviewedSymbolRequest item
    ) {
        return overlayCandidateWithCodexReview(c, item, LocalTime.now(MARKET_ZONE), null);
    }

    private FinalDecisionCandidateRequest overlayCandidateWithCodexReview(
            FinalDecisionCandidateRequest c,
            CodexReviewedSymbolRequest item,
            LocalTime now,
            Map<String, Map<String, Object>> priceGateExtrasBySymbol
    ) {
        String bucket = safeUpper(item.bucket());
        double currentPrice = item.currentPrice() == null ? 0.0 : item.currentPrice();
        double openPrice = item.openPrice() == null ? 0.0 : item.openPrice();
        double prevClose = item.previousClose() == null ? 0.0 : item.previousClose();
        double dayHigh = item.dayHigh() == null ? 0.0 : item.dayHigh();
        boolean belowOpen = openPrice > 0 && currentPrice > 0 && currentPrice < openPrice;
        boolean belowPrevClose = prevClose > 0 && currentPrice > 0 && currentPrice < prevClose;
        boolean entryTooExtended = "SELECT_WAIT_PULLBACK".equals(bucket)
                || "NOT_CHASE".equalsIgnoreCase(item.suggestedAction());
        boolean falseBreakout = "REJECT_RISK".equals(bucket) || containsKeyword(item.issues(), "fake", "false breakout");
        boolean entryTriggered = "SELECT_BUY_NOW".equals(bucket);
        boolean nearDayHigh = dayHigh > 0 && currentPrice > 0 && currentPrice >= dayHigh * 0.985;
        boolean mainStream = item.thesisStillValid() == null ? c.mainStream() : item.thesisStillValid();
        Double realtimeRr = item.realTimeRR() != null && item.realTimeRR() > 0 ? item.realTimeRR() : c.riskRewardRatio();
        String entryZone = buildEntryZone(item, c.entryPriceZone());
        String rationale = mergeRationale(c.rationale(), item);

        // v2.9 Price Gate：把現價 / 開盤 / 昨收 與衍生比例傳入 candidate。
        BigDecimal currentPriceBd = currentPrice > 0 ? BigDecimal.valueOf(currentPrice) : null;
        BigDecimal openPriceBd    = openPrice > 0    ? BigDecimal.valueOf(openPrice)    : null;
        BigDecimal prevCloseBd    = prevClose > 0    ? BigDecimal.valueOf(prevClose)    : null;
        BigDecimal distanceFromOpenPct = (currentPriceBd != null && openPriceBd != null)
                ? currentPriceBd.subtract(openPriceBd).divide(openPriceBd, 6, RoundingMode.HALF_UP)
                : null;
        BigDecimal dropFromPrevClosePct = (currentPriceBd != null && prevCloseBd != null)
                ? prevCloseBd.subtract(currentPriceBd).divide(prevCloseBd, 6, RoundingMode.HALF_UP)
                : null;

        // v2.9.1 Gate 6/7 強化：呼叫 IntradayVwapService + VolumeProfileService 填 vwap / volumeRatio。
        IntradayVwapService.VwapResult vwap =
                intradayVwapService.computeFromCumulative(item.volume(), item.turnover());
        VolumeProfileService.VolumeRatioResult volRatio =
                volumeProfileService.compute(item.volume(), item.averageDailyVolume(), now);
        BigDecimal vwapPriceBd   = vwap.price();
        BigDecimal volumeRatioBd = volRatio.ratio();

        if (priceGateExtrasBySymbol != null) {
            Map<String, Object> extras = new LinkedHashMap<>();
            extras.put("vwapSource", vwap.source());
            extras.put("vwapReason", vwap.reason());
            extras.put("volumeSource", volRatio.source());
            extras.put("volumeReason", volRatio.reason());
            extras.put("currentVolume", volRatio.currentVolume());
            extras.put("expectedVolume", volRatio.expectedVolume());
            extras.put("avgDailyVolume", item.averageDailyVolume());
            extras.put("elapsedTradingMinutes", volRatio.elapsedMinutes());
            extras.put("turnover", item.turnover());
            priceGateExtrasBySymbol.put(c.stockCode(), extras);
        }

        return new FinalDecisionCandidateRequest(
                c.stockCode(),
                c.stockName(),
                c.valuationMode(),
                c.entryType(),
                realtimeRr,
                c.includeInFinalPlan(),
                mainStream,
                falseBreakout,
                belowOpen,
                belowPrevClose,
                nearDayHigh,
                c.stopLossReasonable(),
                rationale,
                entryZone,
                item.stopLoss() != null ? item.stopLoss() : c.stopLossPrice(),
                item.targetPrice() != null ? item.targetPrice() : c.takeProfit1(),
                c.takeProfit2(),
                c.javaStructureScore(),
                c.claudeScore(),
                mapCodexScore(bucket),
                c.finalRankScore(),
                "REJECT_RISK".equals(bucket),
                c.baseScore(),
                c.hasTheme(),
                c.themeRank(),
                c.finalThemeScore(),
                c.consensusScore(),
                c.disagreementPenalty(),
                c.volumeSpike(),
                c.priceNotBreakHigh(),
                entryTooExtended,
                entryTriggered,
                currentPriceBd,
                openPriceBd,
                prevCloseBd,
                vwapPriceBd,
                volumeRatioBd,
                distanceFromOpenPct,
                dropFromPrevClosePct,
                c.marketRegime(),         // regime 由主流程透過 applyMarketRegime 補上
                // v2.16：實際當日最高價（從 Codex live-quote），ChasedHigh evaluator 優先採用
                dayHigh > 0 ? BigDecimal.valueOf(dayHigh) : null
        );
    }

    /**
     * v2.9 Gate 6/7：在 overlay 之後把 marketRegime 字串塞給每個 candidate，
     * PriceGateEvaluator 依此判斷 BULL_TREND / BEAR / PANIC 差異化行為。
     */
    private List<FinalDecisionCandidateRequest> applyMarketRegime(
            List<FinalDecisionCandidateRequest> candidates,
            MarketRegimeDecision regime
    ) {
        if (candidates == null || candidates.isEmpty() || regime == null) {
            return candidates;
        }
        String regimeType = regime.regimeType();
        if (regimeType == null || regimeType.isBlank()) {
            return candidates;
        }
        return candidates.stream().map(c -> withRegime(c, regimeType)).toList();
    }

    private FinalDecisionCandidateRequest withRegime(FinalDecisionCandidateRequest c, String regimeType) {
        return new FinalDecisionCandidateRequest(
                c.stockCode(), c.stockName(), c.valuationMode(), c.entryType(), c.riskRewardRatio(),
                c.includeInFinalPlan(), c.mainStream(), c.falseBreakout(), c.belowOpen(), c.belowPrevClose(),
                c.nearDayHigh(), c.stopLossReasonable(), c.rationale(), c.entryPriceZone(),
                c.stopLossPrice(), c.takeProfit1(), c.takeProfit2(),
                c.javaStructureScore(), c.claudeScore(), c.codexScore(), c.finalRankScore(),
                c.isVetoed(), c.baseScore(), c.hasTheme(), c.themeRank(), c.finalThemeScore(),
                c.consensusScore(), c.disagreementPenalty(), c.volumeSpike(),
                c.priceNotBreakHigh(), c.entryTooExtended(), c.entryTriggered(),
                c.currentPrice(), c.openPrice(), c.previousClose(), c.vwapPrice(),
                c.volumeRatio(), c.distanceFromOpenPct(), c.dropFromPrevClosePct(), regimeType,
                c.dayHigh()
        );
    }

    private List<CodexReviewedSymbolRequest> safeItems(List<CodexReviewedSymbolRequest> items) {
        return items == null ? List.of() : items;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String safeUpper(String value) {
        return value == null ? "" : value.toUpperCase(Locale.ROOT);
    }

    private BigDecimal mapCodexScore(String bucket) {
        return switch (safeUpper(bucket)) {
            case "SELECT_BUY_NOW" -> new BigDecimal("9.8");
            case "SELECT_WAIT_PULLBACK" -> new BigDecimal("7.8");
            case "WATCH_ONLY", "HOLD_EXISTING" -> new BigDecimal("6.8");
            case "REDUCE_EXISTING", "EXIT_EXISTING", "REJECT_WEAK" -> new BigDecimal("3.0");
            case "REJECT_RISK" -> new BigDecimal("2.0");
            default -> null;
        };
    }

    private String buildEntryZone(CodexReviewedSymbolRequest item, String fallback) {
        if (item.entryZoneLow() != null && item.entryZoneHigh() != null) {
            return item.entryZoneLow() + "-" + item.entryZoneHigh();
        }
        return fallback;
    }

    private String mergeRationale(String existing, CodexReviewedSymbolRequest item) {
        List<String> notes = new ArrayList<>();
        if (existing != null && !existing.isBlank()) {
            notes.add(existing);
        }
        if (item.reasons() != null) {
            notes.addAll(item.reasons().stream().filter(s -> s != null && !s.isBlank()).toList());
        }
        if (item.issues() != null && !item.issues().isEmpty()) {
            notes.add("Codex issues: " + String.join("; ", item.issues()));
        }
        return String.join(" | ", notes);
    }

    private boolean containsKeyword(List<String> values, String... keywords) {
        if (values == null || values.isEmpty()) {
            return false;
        }
        for (String value : values) {
            if (value == null) continue;
            String lowered = value.toLowerCase(Locale.ROOT);
            for (String keyword : keywords) {
                if (lowered.contains(keyword.toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
        }
        return false;
    }

    private DecisionTrace buildDecisionTrace(
            CodexExecutionContext context,
            String finalAction,
            String finalReason,
            boolean hardRiskBlocked,
            boolean softPenaltySkipped
    ) {
        return buildDecisionTrace(context, finalAction, finalReason, hardRiskBlocked, softPenaltySkipped, null);
    }

    /**
     * v2.9 Gate 6/7：多傳一個 {@code priceGateTraceMap}（symbol -> priceGate 子 trace），
     * 主流程會對每檔預先算好。若傳 null 則不輸出 priceGate 區塊，維持向下相容。
     */
    private DecisionTrace buildDecisionTrace(
            CodexExecutionContext context,
            String finalAction,
            String finalReason,
            boolean hardRiskBlocked,
            boolean softPenaltySkipped,
            Map<String, Map<String, Object>> priceGateTraceMap
    ) {
        if (context == null || context.reviewedBySymbol().isEmpty()) {
            return DecisionTrace.empty();
        }
        CodexReviewedSymbolRequest primary = resolvePrimaryCodexReview(context);
        if (primary == null) {
            return DecisionTrace.empty();
        }

        boolean executionPriority = "SELECT_BUY_NOW".equalsIgnoreCase(primary.bucket());
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("symbol", primary.symbol());
        trace.put("codexBucket", primary.bucket());
        trace.put("suggestedAction", primary.suggestedAction());
        trace.put("executionPriority", executionPriority);
        trace.put("hardRiskBlocked", hardRiskBlocked);
        trace.put("softPenaltySkipped", softPenaltySkipped);
        trace.put("codexReasons", primary.reasons() == null ? List.of() : primary.reasons());
        trace.put("codexIssues", primary.issues() == null ? List.of() : primary.issues());
        trace.put("finalAction", finalAction);
        trace.put("finalReason", finalReason);
        if (priceGateTraceMap != null) {
            Map<String, Object> pg = priceGateTraceMap.get(primary.symbol());
            if (pg != null) {
                trace.put("priceGate", pg);
            }
        }
        return new DecisionTrace(trace, executionPriority);
    }

    private CodexReviewedSymbolRequest resolvePrimaryCodexReview(CodexExecutionContext context) {
        if (context == null || context.reviewedBySymbol().isEmpty()) {
            return null;
        }
        if (!context.buyNowSymbols().isEmpty()) {
            String firstBuyNow = context.buyNowSymbols().stream()
                    .sorted(Comparator.comparing(symbol -> context.reviewIndex().getOrDefault(symbol, 999)))
                    .findFirst()
                    .orElse(null);
            if (firstBuyNow != null) {
                return context.reviewedBySymbol().get(firstBuyNow);
            }
        }
        return context.reviewedBySymbol().values().stream()
                .sorted(Comparator.comparing(item -> context.reviewIndex().getOrDefault(item.symbol(), 999)))
                .findFirst()
                .orElse(null);
    }

    private FinalDecisionResponse buildCodexShortCircuitDecision(
            AiReadiness readiness,
            CodexExecutionContext context,
            List<FinalDecisionCandidateRequest> actionableCandidates
    ) {
        if (readiness == null
                || !"OPENING".equalsIgnoreCase(readiness.sourceTaskType())
                || context == null
                || context.marketSession() != MarketSession.LIVE_TRADING
                || (actionableCandidates != null && !actionableCandidates.isEmpty())) {
            return null;
        }
        CodexReviewedSymbolRequest primary = resolvePrimaryCodexReview(context);
        if (primary == null) {
            return null;
        }

        String bucket = safeUpper(primary.bucket());
        String reason = resolveReviewReason(primary);
        return switch (bucket) {
            case "SELECT_WAIT_PULLBACK", "WATCH_ONLY" -> withDecisionTrace(
                    new FinalDecisionResponse("WAIT", List.of(), List.of(bucket), reason),
                    buildDecisionTrace(context, "WAIT", reason, false, false));
            case "REJECT_WEAK", "REJECT_RISK" -> withDecisionTrace(
                    new FinalDecisionResponse("REST", List.of(), List.of(bucket), reason),
                    buildDecisionTrace(context, "REJECT", reason, false, false));
            default -> null;
        };
    }

    private boolean hasCodexExecutionPriorityHardRiskBlock(
            AiReadiness readiness,
            CodexExecutionContext context,
            Map<String, PortfolioRiskDecision> riskMap
    ) {
        if (!shouldUseCodexBuyNowOnly(readiness, context) || riskMap == null || riskMap.isEmpty()) {
            return false;
        }
        return context.buyNowSymbols().stream().anyMatch(symbol -> {
            PortfolioRiskDecision decision = riskMap.get(symbol);
            return decision == null || !decision.approved();
        });
    }

    private String resolveDecisionTraceReason(
            CodexExecutionContext context,
            Map<String, PortfolioRiskDecision> riskMap,
            boolean hardRiskBlocked,
            String fallback
    ) {
        CodexReviewedSymbolRequest primary = resolvePrimaryCodexReview(context);
        if (hardRiskBlocked && context != null && riskMap != null) {
            for (String symbol : context.buyNowSymbols()) {
                PortfolioRiskDecision risk = riskMap.get(symbol);
                if (risk == null) {
                    return "NO_RISK_DECISION";
                }
                if (!risk.approved()) {
                    return isBlank(risk.blockReason()) ? "PORTFOLIO_RISK_BLOCKED" : risk.blockReason();
                }
            }
        }
        if (primary != null) {
            String reviewReason = resolveReviewReason(primary);
            if (!isBlank(reviewReason)) {
                return reviewReason;
            }
        }
        return fallback;
    }

    private String resolveReviewReason(CodexReviewedSymbolRequest review) {
        if (review == null) {
            return null;
        }
        if (review.reasons() != null) {
            String reasons = review.reasons().stream()
                    .filter(s -> s != null && !s.isBlank())
                    .collect(Collectors.joining(" | "));
            if (!reasons.isBlank()) {
                return reasons;
            }
        }
        if (review.issues() != null) {
            String issues = review.issues().stream()
                    .filter(s -> s != null && !s.isBlank())
                    .collect(Collectors.joining(" | "));
            if (!issues.isBlank()) {
                return issues;
            }
        }
        return review.bucket();
    }

    private FinalDecisionResponse withDecisionTrace(FinalDecisionResponse response, DecisionTrace trace) {
        if (response == null || trace == null || trace.payload().isEmpty()) {
            return response;
        }
        return new FinalDecisionResponse(
                response.decision(),
                response.selectedStocks(),
                response.rejectedReasons(),
                response.summary(),
                mergePlanningPayload(response.planningPayload(), trace.payload())
        );
    }

    private Map<String, Object> mergePlanningPayload(Map<String, Object> existing, Map<String, Object> tracePayload) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (existing != null && !existing.isEmpty()) {
            payload.putAll(existing);
        }
        if (tracePayload != null && !tracePayload.isEmpty()) {
            payload.put("decisionTrace", tracePayload);
        }
        return payload.isEmpty() ? null : payload;
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
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("decision",         decision.decision() == null ? "" : decision.decision());
            body.put("selected_count",   decision.selectedStocks().size());
            body.put("rejected_count",   decision.rejectedReasons().size());
            body.put("selected_stocks",  decision.selectedStocks());
            body.put("rejected_reasons", decision.rejectedReasons());
            body.put("summary",          decision.summary() == null ? "" : decision.summary());
            // v2.8：盤後規劃模式帶 planningPayload（primary/backup/sectorIndicators/avoidSymbols/tomorrowExecutionNotes）
            if (decision.planningPayload() != null && !decision.planningPayload().isEmpty()) {
                body.put("planning", decision.planningPayload());
            }
            return objectMapper.writeValueAsString(body);
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
            BigDecimal rawRank    = weightedScoringEngine.computeFinalRankScore(aiWeighted, consensusScore, veto.vetoed());
            // v2.6 MVP: 套用 VetoEngine soft penalty 扣分（hard veto 時 rawRank 已為 0，不會再扣）
            // v2.12 Fix4：bucket=SELECT_BUY_NOW（entryTriggered=true）直接 bypass 所有 soft penalty
            //   （ranking / timing / divergence），不再受 codexScore>=9.5 限制；hard risk gate 仍生效。
            // Rollback flag：final_decision.select_buy_now_bypass_soft_penalty.enabled=false
            //   → 回到舊行為（需 codexScore>=9.5 才 bypass）。
            boolean bypassFlagEnabled = scoreConfigService.getBoolean(
                    "final_decision.select_buy_now_bypass_soft_penalty.enabled", true);
            boolean codexExecutionPriority = Boolean.TRUE.equals(c.entryTriggered())
                    && (bypassFlagEnabled
                        || (codexScore != null && codexScore.compareTo(new BigDecimal("9.5")) >= 0));
            BigDecimal finalRank  = veto.vetoed()
                    ? rawRank
                    : codexExecutionPriority
                    ? rawRank
                    : rawRank.subtract(
                            veto.scoringPenalty() == null ? BigDecimal.ZERO : veto.scoringPenalty()
                      ).max(BigDecimal.ZERO);

            // 5. 回寫 stock_evaluation（含 v2.6 MVP veto trace + v2.8 P0.9 scoring trace）
            double rrForBucket = c.riskRewardRatio() == null ? 0.0 : c.riskRewardRatio();
            String bucket = computeBucket(finalRank, rrForBucket, veto.vetoed());
            List<String> missingAiScores = new ArrayList<>();
            if (claudeScore == null) missingAiScores.add("claude");
            if (codexScore  == null) missingAiScores.add("codex");
            persistScores(tradingDate, c.stockCode(), javaScore, claudeScore, codexScore,
                    aiWeighted, finalRank, consensusScore, disagreementPenalty,
                    veto.vetoed(),
                    veto.reasons().isEmpty() ? null : String.join(",", veto.reasons()),
                    rankingOrder,
                    rawRank, veto.scoringPenalty(),
                    veto.hardReasons(), veto.penaltyReasons(), bucket,
                    consensusResult.aiConfidenceMode(), missingAiScores);

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
                               int rankingOrder,
                               // v2.6 MVP trace 新參數
                               BigDecimal rawRankBeforePenalty,
                               BigDecimal scoringPenalty,
                               List<String> hardReasons,
                               List<String> penaltyReasons,
                               String bucket,
                               // v2.8 P0.9 scoring trace
                               String aiConfidenceMode,
                               List<String> missingAiScores) {
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
                eval.setScoreVersion(scoreConfigService.getString("scoring.version", "v2.8-p09-scoring-trace"));
                if (vetoReasons != null) {
                    String vetoJson = "[\"" + vetoReasons.replace(",", "\",\"") + "\"]";
                    eval.setVetoReasonsJson(vetoJson);
                    eval.setJavaVetoFlags(vetoJson);
                }
                // v2.6 MVP veto_trace + v2.8 P0.9 scoring_trace
                eval.setPayloadJson(buildVetoTracePayload(
                        rawRankBeforePenalty, scoringPenalty,
                        hardReasons, penaltyReasons, bucket,
                        javaScore, claudeScore, codexScore,
                        aiConfidenceMode, missingAiScores,
                        eval.getPayloadJson()));
                stockEvaluationRepository.save(eval);
            });
        } catch (Exception e) {
            log.warn("[FinalDecisionService] persistScores failed for {}: {}", symbol, e.getMessage());
        }
    }

    /**
     * v2.6 MVP: 建構 veto_trace 區塊，保留既有 payload_json 其他欄位。
     * v2.8 P0.9：加 scoring_trace（aiConfidenceMode / weightedScoreInputs / weightedScoreWeightsUsed / missingAiScores）。
     */
    private String buildVetoTracePayload(BigDecimal rawRank, BigDecimal penalty,
                                          List<String> hardReasons, List<String> penaltyReasons,
                                          String bucket,
                                          BigDecimal javaScore, BigDecimal claudeScore, BigDecimal codexScore,
                                          String aiConfidenceMode, List<String> missingAiScores,
                                          String existingPayload) {
        try {
            ObjectNode root = parsePayloadObject(existingPayload);
            ObjectNode vetoTrace = objectMapper.createObjectNode();
            if (bucket != null) {
                vetoTrace.put("bucket", bucket);
            } else {
                vetoTrace.putNull("bucket");
            }
            if (rawRank != null) {
                vetoTrace.put("raw_rank", rawRank);
            } else {
                vetoTrace.putNull("raw_rank");
            }
            if (penalty != null) {
                vetoTrace.put("penalty", penalty);
            } else {
                vetoTrace.putNull("penalty");
            }
            BigDecimal finalRank = rawRank == null || penalty == null
                    ? null
                    : rawRank.subtract(penalty).max(BigDecimal.ZERO);
            if (finalRank != null) {
                vetoTrace.put("final_rank", finalRank);
            } else {
                vetoTrace.putNull("final_rank");
            }
            vetoTrace.set("hard_reasons", toArrayNode(hardReasons));
            vetoTrace.set("penalty_reasons", toArrayNode(penaltyReasons));
            root.set("veto_trace", vetoTrace);

            // v2.8 P0.9 scoring_trace
            ObjectNode scoringTrace = objectMapper.createObjectNode();
            scoringTrace.put("aiConfidenceMode", aiConfidenceMode == null ? "UNKNOWN" : aiConfidenceMode);

            ObjectNode inputs = objectMapper.createObjectNode();
            if (javaScore   != null) inputs.put("java",   javaScore);   else inputs.putNull("java");
            if (claudeScore != null) inputs.put("claude", claudeScore); else inputs.putNull("claude");
            if (codexScore  != null) inputs.put("codex",  codexScore);  else inputs.putNull("codex");
            scoringTrace.set("weightedScoreInputs", inputs);

            // 實際使用的權重（對應 WeightedScoringEngine v2.8 P0.9 修法：null 分數權重不計）
            BigDecimal jw = scoreConfigService.getDecimal("scoring.java_weight",   new BigDecimal("0.40"));
            BigDecimal cw = scoreConfigService.getDecimal("scoring.claude_weight", new BigDecimal("0.35"));
            BigDecimal xw = scoreConfigService.getDecimal("scoring.codex_weight",  new BigDecimal("0.25"));
            boolean codexEnabled = scoreConfigService.getBoolean("scoring.enable_codex_review", true);
            ObjectNode weights = objectMapper.createObjectNode();
            if (javaScore   != null) weights.put("java",   jw); else weights.putNull("java");
            if (claudeScore != null) weights.put("claude", cw); else weights.putNull("claude");
            if (codexEnabled && codexScore != null) weights.put("codex", xw);
            else weights.putNull("codex");
            scoringTrace.set("weightedScoreWeightsUsed", weights);

            scoringTrace.set("missingAiScores", toArrayNode(missingAiScores));
            root.set("scoring_trace", scoringTrace);

            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            log.warn("[FinalDecisionService] buildVetoTracePayload failed, fallback to minimal payload", e);
            return "{\"veto_trace\":{\"bucket\":\""
                    + (bucket == null ? "" : bucket.replace("\"", "\\\""))
                    + "\"}}";
        }
    }

    private ArrayNode toArrayNode(List<String> values) {
        ArrayNode array = objectMapper.createArrayNode();
        if (values != null) {
            values.forEach(array::add);
        }
        return array;
    }

    private ObjectNode parsePayloadObject(String existingPayload) {
        if (existingPayload == null || existingPayload.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode node = objectMapper.readTree(existingPayload);
            if (node != null && node.isObject()) {
                return (ObjectNode) node.deepCopy();
            }
        } catch (Exception e) {
            log.warn("[FinalDecisionService] invalid existing payload_json, reset veto_trace payload: {}", e.getMessage());
        }
        return objectMapper.createObjectNode();
    }

    /**
     * v2.6 MVP bucket 分級（與 FinalDecisionEngine 對齊）。
     */
    private String computeBucket(BigDecimal finalRank, double rr, boolean isVetoed) {
        if (isVetoed) return "REJECTED_HARD_VETO";
        if (finalRank == null) return "REJECTED_NO_SCORE";
        BigDecimal apMin = scoreConfigService.getDecimal("scoring.grade_ap_min", new BigDecimal("8.2"));
        BigDecimal aMin  = scoreConfigService.getDecimal("scoring.grade_a_min",  new BigDecimal("7.5"));
        BigDecimal bMin  = scoreConfigService.getDecimal("scoring.grade_b_min",  new BigDecimal("6.5"));
        BigDecimal rrMinAp = scoreConfigService.getDecimal("scoring.rr_min_ap", new BigDecimal("2.2"));
        if (finalRank.compareTo(apMin) >= 0 && rr >= rrMinAp.doubleValue()) return "A_PLUS";
        if (finalRank.compareTo(aMin)  >= 0) return "A";
        if (finalRank.compareTo(bMin)  >= 0) return "B";
        return "C";
    }

    // ── Setup generation helpers (P0.4) ──────────────────────────────────────

    /**
     * Returns valid setup decisions for the given candidates.
     * Re-uses any row already in DB for today; generates new rows on-the-fly for
     * candidates that have no setup yet (e.g. when no pre-market job has run).
     */
    private Map<String, SetupDecision> buildOrLoadSetups(
            List<FinalDecisionCandidateRequest> candidates,
            Map<String, RankedCandidate> rankMap,
            MarketRegimeDecision regime,
            LocalDate tradingDate,
            Map<String, ThemeStrengthDecision> themeDecisions) {

        Map<String, SetupDecision> result = new HashMap<>();
        setupValidationService.getValidByDate(tradingDate)
                .forEach(s -> result.put(s.symbol(), s));

        List<com.austin.trading.dto.internal.SetupEvaluationInput> toGenerate = candidates.stream()
                .filter(c -> !result.containsKey(c.stockCode()))
                .map(c -> toSetupInput(c, rankMap.get(c.stockCode()), regime,
                        themeDecisions.get(rankMap.containsKey(c.stockCode())
                                ? rankMap.get(c.stockCode()).themeTag() : null)))
                .filter(Objects::nonNull)
                .toList();

        if (!toGenerate.isEmpty()) {
            log.info("[FinalDecision] generating setup on-the-fly for {} candidates", toGenerate.size());
            setupValidationService.evaluateAll(toGenerate).stream()
                    .filter(SetupDecision::valid)
                    .forEach(d -> result.put(d.symbol(), d));
        }
        return result;
    }

    /**
     * Build a {@link com.austin.trading.dto.internal.SetupEvaluationInput} from
     * available candidate request fields. Returns {@code null} when
     * {@code entryPriceZone} is absent or unparseable (setup skipped for that candidate).
     */
    /** Backward-compatible overload — no ThemeStrengthDecision (used by tests). */
    com.austin.trading.dto.internal.SetupEvaluationInput toSetupInput(
            FinalDecisionCandidateRequest c,
            RankedCandidate ranked,
            MarketRegimeDecision regime) {
        return toSetupInput(c, ranked, regime, null);
    }

    com.austin.trading.dto.internal.SetupEvaluationInput toSetupInput(
            FinalDecisionCandidateRequest c,
            RankedCandidate ranked,
            MarketRegimeDecision regime,
            ThemeStrengthDecision themeDecision) {

        BigDecimal[] zone = parseEntryZone(c.entryPriceZone());
        if (zone == null) return null;

        BigDecimal currentPrice = zone[0].add(zone[1])
                .divide(new BigDecimal("2"), 4, RoundingMode.HALF_UP);
        BigDecimal baseHigh = zone[1];
        BigDecimal baseLow  = c.stopLossPrice() != null
                ? BigDecimal.valueOf(c.stopLossPrice()) : null;
        // Pullback proxy: zone low as ma5 support, stop loss as ma10 anchor
        BigDecimal ma5  = zone[0];
        BigDecimal ma10 = baseLow;

        boolean eventDriven = c.entryType() != null
                && c.entryType().toUpperCase().contains("EVENT");
        int consolDays = eventDriven ? 5 : 0;

        return new com.austin.trading.dto.internal.SetupEvaluationInput(
                ranked, regime, themeDecision,
                currentPrice, null,
                ma5, ma10,
                baseHigh, baseLow,
                null, null,
                null, null,   // skip volume — not in request
                consolDays, eventDriven
        );
    }

    /** Parse "low-high" or single-price entryPriceZone string. Returns null if unparseable. */
    private static BigDecimal[] parseEntryZone(String zone) {
        if (zone == null || zone.isBlank()) return null;
        try {
            String trimmed = zone.trim();
            String[] parts = trimmed.split("-");
            if (parts.length == 2) {
                return new BigDecimal[]{ new BigDecimal(parts[0].trim()),
                                         new BigDecimal(parts[1].trim()) };
            }
            if (parts.length == 1) {
                BigDecimal p = new BigDecimal(parts[0].trim());
                return new BigDecimal[]{ p, p };
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    // ── 工具 ──────────────────────────────────────────────────────────────────

    private String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    /**
     * v2 Theme Engine PR4/PR5：把候選股集合（全部 candidate，非只 selected）轉成 gate trace probe。
     *
     * <p>PR5 修正：若只對 selected 跑 trace，shadow diff 只會產生 SAME_BUY /
     * LEGACY_BUY_THEME_BLOCK / CONFLICT_REVIEW_REQUIRED，看不到 LEGACY_WAIT_THEME_BUY
     * / BOTH_BLOCK / SAME_WAIT。故對所有 candidate 都跑 probe，讓 shadow 看到完整 6 類。</p>
     *
     * <p>nullable：themeTag 嘗試從 Codex review 或 CandidateStock 推斷；找不到就給 null（merge 會略過對齊）。
     * Trace-only，純讀不寫。</p>
     */
    private List<ThemeGateOrchestrator.CandidateProbe> buildThemeGateProbes(
            java.util.Collection<FinalDecisionCandidateRequest> candidates,
            MarketRegimeDecision regime,
            CodexExecutionContext codexContext,
            int openPositions,
            int maxPositions,
            BigDecimal availableCash
    ) {
        List<ThemeGateOrchestrator.CandidateProbe> probes = new ArrayList<>();
        if (candidates == null || candidates.isEmpty()) return probes;
        String regimeType = regime != null ? regime.regimeType() : null;
        boolean tradeAllowed = regime == null || regime.tradeAllowed();
        BigDecimal riskMultiplier = regime != null ? regime.riskMultiplier() : null;

        for (FinalDecisionCandidateRequest c : candidates) {
            if (c == null || c.stockCode() == null) continue;

            String themeTag = null;
            BigDecimal turnover = null;
            if (codexContext != null) {
                var review = codexContext.reviewedBySymbol().get(c.stockCode());
                if (review != null && review.turnover() != null) {
                    turnover = BigDecimal.valueOf(review.turnover());
                }
            }

            probes.add(new ThemeGateOrchestrator.CandidateProbe(
                    c.stockCode(),
                    themeTag,
                    regimeType,
                    tradeAllowed,
                    riskMultiplier,
                    turnover,
                    c.volumeRatio(),
                    c.javaStructureScore(),
                    c.claudeScore(),
                    c.codexScore(),
                    c.riskRewardRatio() == null ? null : BigDecimal.valueOf(c.riskRewardRatio()),
                    c.baseScore(),
                    openPositions,
                    maxPositions,
                    availableCash
            ));
        }
        return probes;
    }

    /**
     * v2 Theme Engine PR5：把 PR4 themeOutcome 對齊成 shadow diff input。
     *
     * <p>Legacy decision 正規化規則：逐檔判斷 {@code (finalDecisionCode=ENTER && selectedSymbols.contains(symbol)) ? ENTER : WAIT}。
     * 這讓 shadow 可看到「legacy 未選 × theme PASS/BLOCK/WAIT」等完整 6 類。</p>
     * <p>Legacy final score 使用 {@code finalRankScore}（未設時退回 {@code baseScore}）。</p>
     */
    private List<ThemeShadowModeService.Input> buildShadowInputs(
            java.util.Collection<FinalDecisionCandidateRequest> candidates,
            java.util.Set<String> selectedSymbols,
            String finalDecisionCode,
            ThemeGateOrchestrator.Outcome themeOutcome) {
        if (candidates == null || candidates.isEmpty() || themeOutcome == null) return List.of();
        boolean finalDecisionEnter = "ENTER".equalsIgnoreCase(finalDecisionCode);
        java.util.Set<String> selected = selectedSymbols == null ? java.util.Set.of() : selectedSymbols;
        List<ThemeShadowModeService.Input> list = new ArrayList<>(candidates.size());
        for (FinalDecisionCandidateRequest c : candidates) {
            if (c == null || c.stockCode() == null) continue;
            var trace = themeOutcome.findBySymbol(c.stockCode()).orElse(null);
            if (trace == null) continue;   // theme trace 缺 → 跳過該檔
            boolean wasSelected = selected.contains(c.stockCode());
            String legacyDecision = (finalDecisionEnter && wasSelected) ? "ENTER" : "WAIT";
            BigDecimal legacyScore = c.finalRankScore() != null ? c.finalRankScore() : c.baseScore();
            list.add(new ThemeShadowModeService.Input(
                    c.stockCode(),
                    legacyDecision,
                    legacyScore,
                    trace,
                    null));
        }
        return list;
    }
}
