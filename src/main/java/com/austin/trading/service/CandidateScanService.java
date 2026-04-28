package com.austin.trading.service;

import com.austin.trading.client.TwseMisClient;
import com.austin.trading.client.dto.StockQuote;
import com.austin.trading.dto.request.CandidateBatchItemRequest;
import com.austin.trading.dto.request.FinalDecisionCandidateRequest;
import com.austin.trading.dto.response.CandidateBatchSaveResponse;
import com.austin.trading.dto.response.CandidateResponse;
import com.austin.trading.dto.response.LiveQuoteResponse;
import com.austin.trading.engine.MomentumCandidateEngine;
import com.austin.trading.engine.MomentumCandidateEngine.CandidateDecision;
import com.austin.trading.engine.MomentumCandidateEngine.CandidateInput;
import com.austin.trading.entity.CandidateStockEntity;
import com.austin.trading.entity.StockEvaluationEntity;
import com.austin.trading.entity.ThemeSnapshotEntity;
import com.austin.trading.repository.CandidateStockRepository;
import com.austin.trading.repository.StockEvaluationRepository;
import com.austin.trading.repository.ThemeSnapshotRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class CandidateScanService {

    private static final Logger log = LoggerFactory.getLogger(CandidateScanService.class);

    private final CandidateStockRepository candidateStockRepository;
    private final StockEvaluationRepository stockEvaluationRepository;
    private final ThemeSnapshotRepository themeSnapshotRepository;
    private final TwseMisClient twseMisClient;
    private final MomentumCandidateEngine momentumCandidateEngine;
    private final ScoreConfigService scoreConfigService;
    private final ObjectMapper objectMapper;

    public CandidateScanService(
            CandidateStockRepository candidateStockRepository,
            StockEvaluationRepository stockEvaluationRepository,
            ThemeSnapshotRepository themeSnapshotRepository,
            TwseMisClient twseMisClient,
            MomentumCandidateEngine momentumCandidateEngine,
            ScoreConfigService scoreConfigService,
            ObjectMapper objectMapper
    ) {
        this.candidateStockRepository = candidateStockRepository;
        this.stockEvaluationRepository = stockEvaluationRepository;
        this.themeSnapshotRepository = themeSnapshotRepository;
        this.twseMisClient = twseMisClient;
        this.momentumCandidateEngine = momentumCandidateEngine;
        this.scoreConfigService = scoreConfigService;
        this.objectMapper = objectMapper;
    }

    /**
     * 查詢任意股票代號的即時報價（TSE 優先，無價格則以 OTC 補查）。
     */
    public List<LiveQuoteResponse> getLiveQuotesBySymbols(List<String> symbols) {
        if (symbols == null || symbols.isEmpty()) return List.of();

        Map<String, StockQuote> quoteMap = new HashMap<>();
        twseMisClient.getTseQuotes(symbols)
                .forEach(q -> quoteMap.put(q.symbol(), q));

        List<String> retryOtc = symbols.stream()
                .filter(s -> !quoteMap.containsKey(s) || quoteMap.get(s).currentPrice() == null)
                .collect(Collectors.toList());
        if (!retryOtc.isEmpty()) {
            twseMisClient.getOtcQuotes(retryOtc)
                    .forEach(q -> {
                        if (q.currentPrice() != null || !quoteMap.containsKey(q.symbol())) {
                            quoteMap.put(q.symbol(), q);
                        }
                    });
        }

        return symbols.stream()
                .map(sym -> {
                    StockQuote q = quoteMap.get(sym);
                    if (q == null) return new LiveQuoteResponse(
                            sym, null, "tse", null, null, null, null, null,
                            null, null, null, null, false);
                    Double changeAmount = (q.currentPrice() != null && q.prevClose() != null)
                            ? Math.round((q.currentPrice() - q.prevClose()) * 100.0) / 100.0
                            : null;
                    return new LiveQuoteResponse(
                            sym, q.name(), q.market(),
                            q.currentPrice(), q.prevClose(), q.open(), q.dayHigh(), q.dayLow(),
                            q.changePercent(), changeAmount, q.volume(), q.tradeTime(), q.available());
                })
                .collect(Collectors.toList());
    }

    /**
     * 取得今日候選股即時報價。
     * <p>
     * 先以 TSE 查詢，若某檔 currentPrice 為 null，再以 OTC 補查一次。
     * 盤外時段 currentPrice 為 null，但昨收/開盤等欄位仍會回傳。
     * </p>
     */
    public List<LiveQuoteResponse> getCurrentLiveQuotes() {
        List<CandidateStockEntity> candidates = candidateStockRepository
                .findByTradingDateOrderByScoreDesc(resolveLatestTradingDate(), PageRequest.of(0, 20));
        if (candidates.isEmpty()) return List.of();

        List<String> symbols = candidates.stream()
                .map(CandidateStockEntity::getSymbol)
                .collect(Collectors.toList());

        List<LiveQuoteResponse> quotes = getLiveQuotesBySymbols(symbols);

        // 用候選股資料補強 stockName
        Map<String, String> nameMap = candidates.stream()
                .filter(c -> c.getStockName() != null)
                .collect(Collectors.toMap(CandidateStockEntity::getSymbol,
                        CandidateStockEntity::getStockName, (a, b) -> a));

        return quotes.stream()
                .map(q -> nameMap.containsKey(q.symbol())
                        ? new LiveQuoteResponse(q.symbol(), nameMap.get(q.symbol()), q.market(),
                                q.currentPrice(), q.prevClose(), q.open(), q.dayHigh(), q.dayLow(),
                                q.changePercent(), q.changeAmount(), q.volume(), q.tradeTime(), q.available())
                        : q)
                .collect(Collectors.toList());
    }

    // ── v2.3 Hard Gate：Momentum Candidate Engine ─────────────────────────────

    /**
     * 批次 upsert 候選股 — <b>v2.3 Momentum hard gate 版</b>。
     * <p>流程：</p>
     * <ol>
     *   <li>讀 feature flag {@code candidate.momentum_gate.enabled}（預設 true）。</li>
     *   <li>對每筆 request 呼叫 {@link MomentumCandidateEngine#evaluate}。</li>
     *   <li>通過者寫入 candidate_stock 並標 {@code is_momentum_candidate=true}，
     *       同時把 5 條基本條件結果序列化到 {@code momentum_flags_json}。</li>
     *   <li>未通過者收進 rejections，不寫 DB。</li>
     *   <li>flag 關閉時退化為舊行為（全部寫入，{@code is_momentum_candidate=false}）。</li>
     * </ol>
     */
    @Transactional
    public CandidateBatchSaveResponse saveBatchWithGate(List<CandidateBatchItemRequest> items) {
        boolean gateEnabled = scoreConfigService.getBoolean("candidate.momentum_gate.enabled", true);

        List<CandidateBatchItemRequest> accepted = new ArrayList<>();
        List<CandidateDecision> decisions = new ArrayList<>();
        List<CandidateBatchSaveResponse.Rejection> rejections = new ArrayList<>();

        for (CandidateBatchItemRequest item : items) {
            if (!gateEnabled) {
                accepted.add(item);
                decisions.add(null);
                continue;
            }

            CandidateInput input = buildEngineInput(item);
            CandidateDecision decision = momentumCandidateEngine.evaluate(input);

            if (decision.isMomentumCandidate()) {
                accepted.add(item);
                decisions.add(decision);
            } else {
                rejections.add(buildRejection(item, decision, input));
            }
        }

        List<CandidateResponse> persisted = persistAccepted(accepted, decisions);

        log.info("[CandidateBatchGate] received={} accepted={} rejected={} flagEnabled={}",
                items.size(), accepted.size(), rejections.size(), gateEnabled);

        return new CandidateBatchSaveResponse(
                items.size(),
                accepted.size(),
                rejections.size(),
                rejections,
                persisted
        );
    }

    /**
     * 把 {@link CandidateBatchItemRequest} 轉成 {@link CandidateInput}。
     *
     * <p><b>Bootstrap fallback 策略：</b>上游 PowerShell 還未把全部訊號（MA、新高、連續上漲等）
     * 一起填進 payload，因此對未知欄位採寬鬆預設、對已知不利訊號採嚴格：</p>
     * <ul>
     *   <li>{@code todayChangePct} ← payload.changePct；無則 null</li>
     *   <li>{@code volumeRatioTo5MA} ← payload.volumeRatio；若無但 amountYi >= 1 億則給 1.5（剛好過 engine 門檻）</li>
     *   <li>{@code themeRank / finalThemeScore} ← 同日 theme_snapshot；找不到 → rank=99 / score=5.0（中性，不過 theme 條件）</li>
     *   <li>{@code claudeScore} ← payload.claudeScore；無則 null（engine 視為中性 PASS）</li>
     *   <li>{@code codexVetoed} ← payload.codexVetoed；無則 false</li>
     *   <li>{@code claudeRiskFlags} ← payload.claudeRiskFlags 陣列；無則 empty</li>
     * </ul>
     * <p>哲學：gate 的目的是擋掉<b>明顯的爛標的</b>（Codex veto、claudeScore 過低、無量等），
     * 而不是要求每張單都帶滿訊號。「未知 → 通過」是 bootstrap 期的合理行為。</p>
     */
    CandidateInput buildEngineInput(CandidateBatchItemRequest item) {
        JsonNode payload = parsePayload(item.payloadJson());

        Double changePct = readDouble(payload, "changePct");
        Double volumeRatio = readDouble(payload, "volumeRatio");
        if (volumeRatio == null) {
            Double amountYi = readDouble(payload, "amountYi");
            if (amountYi != null && amountYi >= 1.0) volumeRatio = 1.5;
        }

        Boolean newHigh20 = readBoolean(payload, "todayNewHigh20");
        Integer consecUp = readInt(payload, "consecutiveUpDays");
        Boolean aboveOpen = readBoolean(payload, "todayAboveOpen");

        Boolean aboveMa5 = readBoolean(payload, "aboveMa5");
        Boolean ma5OverMa10 = readBoolean(payload, "ma5OverMa10");
        Boolean ma5Turning = readBoolean(payload, "ma5Turning");

        Boolean breakoutVolume = readBoolean(payload, "breakoutVolumeSpike");
        Boolean codexVetoed = Boolean.TRUE.equals(readBoolean(payload, "codexVetoed"));

        BigDecimal claudeScore = readDecimal(payload, "claudeScore");
        List<String> claudeRiskFlags = readStringList(payload, "claudeRiskFlags");

        Integer themeRank = null;
        BigDecimal finalThemeScore = null;
        if (item.themeTag() != null && !item.themeTag().isBlank()) {
            LocalDate date = item.tradingDate() != null ? item.tradingDate() : LocalDate.now();
            ThemeSnapshotEntity snap = themeSnapshotRepository
                    .findByTradingDateAndThemeTag(date, item.themeTag())
                    .orElse(null);
            if (snap != null) {
                themeRank = snap.getRankingOrder();
                finalThemeScore = snap.getFinalThemeScore();
            }
        }
        if (themeRank == null) themeRank = 99;
        if (finalThemeScore == null) finalThemeScore = new BigDecimal("5.0");

        return new CandidateInput(
                item.symbol(),
                changePct,
                consecUp,
                newHigh20,
                aboveOpen,
                aboveMa5,
                ma5OverMa10,
                ma5Turning,
                volumeRatio,
                breakoutVolume,
                themeRank,
                finalThemeScore,
                claudeScore,
                claudeRiskFlags,
                codexVetoed
        );
    }

    private CandidateBatchSaveResponse.Rejection buildRejection(
            CandidateBatchItemRequest item,
            CandidateDecision decision,
            CandidateInput input
    ) {
        if (decision.aiStronglyNegative()) {
            if (Boolean.TRUE.equals(input.codexVetoed())) {
                return new CandidateBatchSaveResponse.Rejection(
                        item.symbol(), "HARD_VETO_CODEX", "Codex 已標 veto");
            }
            BigDecimal claude = input.claudeScore();
            if (claude != null && claude.compareTo(new BigDecimal("4.0")) < 0) {
                return new CandidateBatchSaveResponse.Rejection(
                        item.symbol(), "HARD_VETO_CLAUDE_LOW_SCORE",
                        "claudeScore " + claude + " < 4.0");
            }
            List<String> flags = input.claudeRiskFlags();
            String flagDesc = (flags != null && !flags.isEmpty())
                    ? String.join(",", flags) : "(unspecified)";
            return new CandidateBatchSaveResponse.Rejection(
                    item.symbol(), "HARD_VETO_RISK_FLAG", "命中 hard risk flag: " + flagDesc);
        }
        return new CandidateBatchSaveResponse.Rejection(
                item.symbol(),
                "INSUFFICIENT_CONDITIONS",
                "matched=" + decision.matchedConditionsCount() + " flags=" + decision.matchedFlags()
        );
    }

    /** 寫入通過 gate 的部分；同時設定 is_momentum_candidate / momentum_flags_json。 */
    private List<CandidateResponse> persistAccepted(
            List<CandidateBatchItemRequest> items,
            List<CandidateDecision> decisions
    ) {
        if (items.isEmpty()) return List.of();
        LocalDate today = LocalDate.now();
        List<LocalDate> affectedDates = new ArrayList<>();

        for (int i = 0; i < items.size(); i++) {
            CandidateBatchItemRequest item = items.get(i);
            CandidateDecision decision = decisions.get(i);
            LocalDate date = item.tradingDate() != null ? item.tradingDate() : today;
            if (!affectedDates.contains(date)) affectedDates.add(date);

            CandidateStockEntity entity = candidateStockRepository
                    .findByTradingDateAndSymbol(date, item.symbol())
                    .orElseGet(CandidateStockEntity::new);
            entity.setTradingDate(date);
            entity.setSymbol(item.symbol());
            if (item.stockName()   != null) entity.setStockName(item.stockName());
            if (item.score()       != null) entity.setScore(item.score());
            if (item.reason()      != null) entity.setReason(item.reason());
            if (item.themeTag()    != null) entity.setThemeTag(item.themeTag());
            if (item.sector()      != null) entity.setSector(item.sector());
            if (item.payloadJson() != null) entity.setPayloadJson(item.payloadJson());

            if (decision != null) {
                entity.setMomentumCandidate(true);
                entity.setMomentumFlagsJson(serializeFlags(decision));
            }

            candidateStockRepository.save(entity);

            if (hasEvalFields(item)) {
                StockEvaluationEntity eval = stockEvaluationRepository
                        .findByTradingDateAndSymbol(date, item.symbol())
                        .orElseGet(StockEvaluationEntity::new);
                eval.setTradingDate(date);
                eval.setSymbol(item.symbol());
                if (item.valuationMode()    != null) eval.setValuationMode(item.valuationMode());
                if (item.entryPriceZone()   != null) eval.setEntryPriceZone(item.entryPriceZone());
                if (item.stopLossPrice()    != null) eval.setStopLossPrice(item.stopLossPrice());
                if (item.takeProfit1()      != null) eval.setTakeProfit1(item.takeProfit1());
                if (item.takeProfit2()      != null) eval.setTakeProfit2(item.takeProfit2());
                if (item.riskRewardRatio()  != null) eval.setRiskRewardRatio(item.riskRewardRatio());
                if (item.includeInFinalPlan() != null) eval.setIncludeInFinalPlan(item.includeInFinalPlan());
                stockEvaluationRepository.save(eval);
            }
        }

        LocalDate latestDate = affectedDates.stream().max(LocalDate::compareTo).orElse(today);
        return getCandidatesByDate(latestDate, 200);
    }

    private String serializeFlags(CandidateDecision decision) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("matched", decision.matchedConditionsCount());
            payload.put("flags", decision.matchedFlags());
            payload.put("aiStronglyNegative", decision.aiStronglyNegative());
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            log.warn("[CandidateBatchGate] failed to serialize momentum flags: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 舊路徑相容介面：內部委派至 {@link #saveBatchWithGate(List)}，僅回傳 accepted 項。
     * 新代碼請改用 {@link #saveBatchWithGate(List)} 以拿到 rejections 列表。
     */
    @Transactional
    public List<CandidateResponse> saveBatch(List<CandidateBatchItemRequest> items) {
        return saveBatchWithGate(items).items();
    }

    // ── payload JSON 讀值 helper ──────────────────────────────────────────────

    private JsonNode parsePayload(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return objectMapper.readTree(raw);
        } catch (JsonProcessingException e) {
            log.debug("[CandidateBatchGate] payload parse failed: {}", e.getMessage());
            return null;
        }
    }

    private Double readDouble(JsonNode n, String key) {
        if (n == null) return null;
        JsonNode v = n.get(key);
        if (v == null || v.isNull()) return null;
        if (v.isNumber()) return v.doubleValue();
        if (v.isTextual()) {
            try { return Double.parseDouble(v.asText()); } catch (NumberFormatException ignore) { return null; }
        }
        return null;
    }

    private Integer readInt(JsonNode n, String key) {
        if (n == null) return null;
        JsonNode v = n.get(key);
        if (v == null || v.isNull()) return null;
        if (v.isNumber()) return v.intValue();
        if (v.isTextual()) {
            try { return Integer.parseInt(v.asText()); } catch (NumberFormatException ignore) { return null; }
        }
        return null;
    }

    private Boolean readBoolean(JsonNode n, String key) {
        if (n == null) return null;
        JsonNode v = n.get(key);
        if (v == null || v.isNull()) return null;
        if (v.isBoolean()) return v.booleanValue();
        if (v.isTextual()) return Boolean.parseBoolean(v.asText());
        return null;
    }

    private BigDecimal readDecimal(JsonNode n, String key) {
        if (n == null) return null;
        JsonNode v = n.get(key);
        if (v == null || v.isNull()) return null;
        if (v.isNumber()) return v.decimalValue();
        if (v.isTextual()) {
            try { return new BigDecimal(v.asText()); } catch (NumberFormatException ignore) { return null; }
        }
        return null;
    }

    private List<String> readStringList(JsonNode n, String key) {
        if (n == null) return List.of();
        JsonNode v = n.get(key);
        if (v == null || !v.isArray()) return List.of();
        List<String> out = new ArrayList<>();
        v.forEach(e -> { if (e != null && !e.isNull()) out.add(e.asText()); });
        return out;
    }

    private boolean hasEvalFields(CandidateBatchItemRequest item) {
        return item.valuationMode()    != null
            || item.entryPriceZone()   != null
            || item.stopLossPrice()    != null
            || item.takeProfit1()      != null
            || item.takeProfit2()      != null
            || item.riskRewardRatio()  != null
            || item.includeInFinalPlan() != null;
    }

    public List<FinalDecisionCandidateRequest> loadFinalDecisionCandidates(LocalDate tradingDate, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 20));
        List<CandidateStockEntity> candidates = candidateStockRepository.findByTradingDateOrderByScoreDesc(
                tradingDate,
                PageRequest.of(0, safeLimit)
        );

        Map<String, StockEvaluationEntity> evaluationBySymbol = new HashMap<>();
        for (StockEvaluationEntity e : stockEvaluationRepository.findByTradingDate(tradingDate)) {
            evaluationBySymbol.put(e.getSymbol(), e);
        }

        // 預先載入當日所有題材快照（依排名順序），供 themeRank 查詢
        Map<String, ThemeSnapshotEntity> themeByTag = new HashMap<>();
        for (ThemeSnapshotEntity t : themeSnapshotRepository.findByTradingDateOrderByRankingOrderAsc(tradingDate)) {
            themeByTag.put(t.getThemeTag(), t);
        }

        return candidates.stream()
                .map(c -> toFinalDecisionCandidate(c, evaluationBySymbol.get(c.getSymbol()),
                        c.getThemeTag() != null ? themeByTag.get(c.getThemeTag()) : null))
                .toList();
    }

    public List<CandidateResponse> getCurrentCandidates(int limit) {
        return getCandidatesByDate(resolveLatestTradingDate(), limit);
    }

    /**
     * 解析「最新有效交易日」。
     * 優先用今天；若今天無資料（週末/假日），回退至 DB 最後一筆的 tradingDate。
     */
    private LocalDate resolveLatestTradingDate() {
        LocalDate today = LocalDate.now();
        boolean hasToday = candidateStockRepository
                .findByTradingDateOrderByScoreDesc(today, PageRequest.of(0, 1))
                .isEmpty() == false;
        if (hasToday) return today;
        return candidateStockRepository
                .findTopByOrderByTradingDateDesc()
                .map(CandidateStockEntity::getTradingDate)
                .orElse(today);
    }

    public List<CandidateResponse> getCandidatesByDate(LocalDate tradingDate, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        List<CandidateStockEntity> candidates = candidateStockRepository.findByTradingDateOrderByScoreDesc(
                tradingDate,
                PageRequest.of(0, safeLimit)
        );
        return mergeWithEvaluation(candidates, tradingDate);
    }

    public List<CandidateResponse> getCandidatesHistory(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 300));
        List<CandidateStockEntity> candidates = candidateStockRepository.findAllByOrderByTradingDateDescScoreDesc(
                PageRequest.of(0, safeLimit)
        );
        return mergeWithEvaluation(candidates, null);
    }

    private List<CandidateResponse> mergeWithEvaluation(List<CandidateStockEntity> candidates, LocalDate filterDate) {
        Map<String, StockEvaluationEntity> evaluationBySymbol = new HashMap<>();
        if (filterDate != null) {
            for (StockEvaluationEntity e : stockEvaluationRepository.findByTradingDate(filterDate)) {
                evaluationBySymbol.put(filterDate + ":" + e.getSymbol(), e);
            }
        } else {
            Map<LocalDate, List<StockEvaluationEntity>> grouped = new HashMap<>();
            for (CandidateStockEntity c : candidates) {
                grouped.computeIfAbsent(c.getTradingDate(), d -> stockEvaluationRepository.findByTradingDate(d));
            }
            for (Map.Entry<LocalDate, List<StockEvaluationEntity>> entry : grouped.entrySet()) {
                for (StockEvaluationEntity e : entry.getValue()) {
                    evaluationBySymbol.put(entry.getKey() + ":" + e.getSymbol(), e);
                }
            }
        }

        return candidates.stream()
                .map(c -> {
                    StockEvaluationEntity eval = evaluationBySymbol.get(c.getTradingDate() + ":" + c.getSymbol());
                    return new CandidateResponse(
                            c.getTradingDate(),
                            c.getSymbol(),
                            c.getStockName(),
                            c.getScore(),
                            c.getReason(),
                            eval == null ? null : eval.getValuationMode(),
                            eval == null ? null : eval.getEntryPriceZone(),
                            eval == null ? null : eval.getRiskRewardRatio(),
                            eval == null ? null : eval.getIncludeInFinalPlan(),
                            eval == null ? null : eval.getStopLossPrice(),
                            eval == null ? null : eval.getTakeProfit1(),
                            eval == null ? null : eval.getTakeProfit2(),
                            c.getThemeTag(),
                            c.getSector(),
                            eval == null ? null : eval.getJavaStructureScore(),
                            eval == null ? null : eval.getClaudeScore(),
                            eval == null ? null : eval.getCodexScore(),
                            eval == null ? null : eval.getFinalRankScore(),
                            eval == null ? null : eval.getIsVetoed(),
                            eval == null ? null : eval.getAiWeightedScore(),
                            eval == null ? null : eval.getConsensusScore(),
                            eval == null ? null : eval.getDisagreementPenalty()
                    );
                })
                .toList();
    }

    private FinalDecisionCandidateRequest toFinalDecisionCandidate(
            CandidateStockEntity candidate,
            StockEvaluationEntity eval,
            ThemeSnapshotEntity themeSnapshot
    ) {
        BigDecimal rr = eval == null ? BigDecimal.ZERO : nullSafe(eval.getRiskRewardRatio(), BigDecimal.ZERO);
        boolean includePlan = eval != null && Boolean.TRUE.equals(eval.getIncludeInFinalPlan());
        String valuationMode = eval == null ? "VALUE_STORY" : nullSafe(eval.getValuationMode(), "VALUE_STORY");
        String entryType = inferEntryType(candidate.getReason());

        String entryZone = eval == null ? null : eval.getEntryPriceZone();
        Double stopLoss = eval == null || eval.getStopLossPrice() == null ? null : eval.getStopLossPrice().doubleValue();
        Double tp1 = eval == null || eval.getTakeProfit1() == null ? null : eval.getTakeProfit1().doubleValue();
        Double tp2 = eval == null || eval.getTakeProfit2() == null ? null : eval.getTakeProfit2().doubleValue();

        // 從 eval 讀取既有的 AI 評分（Claude/Codex 可能已由外部回填）
        BigDecimal existingClaudeScore = eval == null ? null : eval.getClaudeScore();
        BigDecimal existingCodexScore  = eval == null ? null : eval.getCodexScore();
        BigDecimal existingFinalRank   = eval == null ? null : eval.getFinalRankScore();
        Boolean    existingIsVetoed    = eval == null ? null : eval.getIsVetoed();

        // 從 theme snapshot 取題材排名與分數（BC Sniper v2.0）
        Integer    themeRank       = themeSnapshot != null ? themeSnapshot.getRankingOrder() : null;
        BigDecimal finalThemeScore = themeSnapshot != null ? themeSnapshot.getFinalThemeScore() : null;

        return new FinalDecisionCandidateRequest(
                candidate.getSymbol(),
                nullSafe(candidate.getStockName(), candidate.getSymbol()),
                valuationMode,
                entryType,
                rr.doubleValue(),
                includePlan,
                true,
                false,
                false,
                false,
                false,
                stopLoss != null,
                candidate.getReason(),
                entryZone,
                stopLoss,
                tp1,
                tp2,
                eval == null ? null : eval.getJavaStructureScore(),
                existingClaudeScore,
                existingCodexScore,
                existingFinalRank,
                existingIsVetoed,
                candidate.getScore(),                          // baseScore
                candidate.getThemeTag() != null,               // hasTheme
                themeRank,                                     // themeRank
                finalThemeScore,                               // finalThemeScore
                eval == null ? null : eval.getConsensusScore(),        // consensusScore
                eval == null ? null : eval.getDisagreementPenalty(),   // disagreementPenalty
                null,                                          // volumeSpike
                null,                                          // priceNotBreakHigh
                null,                                          // entryTooExtended
                null                                           // entryTriggered（由外部資料補充）
        );
    }

    private String inferEntryType(String reason) {
        if (reason == null) {
            return "PULLBACK";
        }
        String r = reason.toLowerCase(Locale.ROOT);
        if (r.contains("突破") || r.contains("breakout")) {
            return "BREAKOUT";
        }
        if (r.contains("轉強") || r.contains("reversal")) {
            return "REVERSAL";
        }
        return "PULLBACK";
    }

    /**
     * 切換今日候選股「納入最終計畫」狀態。
     * 若 StockEvaluation 不存在，自動建立後設為 true。
     */
    @Transactional
    public CandidateResponse toggleInclude(String symbol) {
        LocalDate today = LocalDate.now();
        StockEvaluationEntity eval = stockEvaluationRepository
                .findByTradingDateAndSymbol(today, symbol)
                .orElseGet(() -> {
                    StockEvaluationEntity e = new StockEvaluationEntity();
                    e.setTradingDate(today);
                    e.setSymbol(symbol);
                    return e;
                });
        eval.setIncludeInFinalPlan(!Boolean.TRUE.equals(eval.getIncludeInFinalPlan()));
        stockEvaluationRepository.save(eval);

        CandidateStockEntity cand = candidateStockRepository
                .findByTradingDateAndSymbol(today, symbol).orElse(null);
        return cand == null ? null : new CandidateResponse(
                cand.getTradingDate(), cand.getSymbol(), cand.getStockName(),
                cand.getScore(), cand.getReason(),
                eval.getValuationMode(), eval.getEntryPriceZone(), eval.getRiskRewardRatio(),
                eval.getIncludeInFinalPlan(), eval.getStopLossPrice(),
                eval.getTakeProfit1(), eval.getTakeProfit2(),
                cand.getThemeTag(), cand.getSector(),
                eval.getJavaStructureScore(), eval.getClaudeScore(),
                eval.getCodexScore(), eval.getFinalRankScore(), eval.getIsVetoed(),
                eval.getAiWeightedScore(), eval.getConsensusScore(), eval.getDisagreementPenalty()
        );
    }

    /**
     * 更新今日候選股的評估欄位（僅更新非 null 的欄位）。
     * 若 StockEvaluation 不存在，自動建立。
     */
    @Transactional
    public CandidateResponse updateCandidate(String symbol, CandidateBatchItemRequest req) {
        LocalDate today = LocalDate.now();
        CandidateStockEntity cand = candidateStockRepository
                .findByTradingDateAndSymbol(today, symbol)
                .orElseThrow(() -> new RuntimeException("候選股不存在: " + symbol));

        if (req.score()     != null) cand.setScore(req.score());
        if (req.reason()    != null) cand.setReason(req.reason());
        if (req.stockName() != null) cand.setStockName(req.stockName());
        if (req.themeTag()  != null) cand.setThemeTag(req.themeTag());
        if (req.sector()    != null) cand.setSector(req.sector());
        candidateStockRepository.save(cand);

        StockEvaluationEntity eval = stockEvaluationRepository
                .findByTradingDateAndSymbol(today, symbol)
                .orElseGet(() -> {
                    StockEvaluationEntity e = new StockEvaluationEntity();
                    e.setTradingDate(today);
                    e.setSymbol(symbol);
                    return e;
                });
        if (req.valuationMode()     != null) eval.setValuationMode(req.valuationMode());
        if (req.entryPriceZone()    != null) eval.setEntryPriceZone(req.entryPriceZone());
        if (req.stopLossPrice()     != null) eval.setStopLossPrice(req.stopLossPrice());
        if (req.takeProfit1()       != null) eval.setTakeProfit1(req.takeProfit1());
        if (req.takeProfit2()       != null) eval.setTakeProfit2(req.takeProfit2());
        if (req.riskRewardRatio()   != null) eval.setRiskRewardRatio(req.riskRewardRatio());
        if (req.includeInFinalPlan()!= null) eval.setIncludeInFinalPlan(req.includeInFinalPlan());
        stockEvaluationRepository.save(eval);

        return new CandidateResponse(
                cand.getTradingDate(), cand.getSymbol(), cand.getStockName(),
                cand.getScore(), cand.getReason(),
                eval.getValuationMode(), eval.getEntryPriceZone(), eval.getRiskRewardRatio(),
                eval.getIncludeInFinalPlan(), eval.getStopLossPrice(),
                eval.getTakeProfit1(), eval.getTakeProfit2(),
                cand.getThemeTag(), cand.getSector(),
                eval.getJavaStructureScore(), eval.getClaudeScore(),
                eval.getCodexScore(), eval.getFinalRankScore(), eval.getIsVetoed(),
                eval.getAiWeightedScore(), eval.getConsensusScore(), eval.getDisagreementPenalty()
        );
    }

    private <T> T nullSafe(T value, T fallback) {
        return value == null ? fallback : value;
    }
}
