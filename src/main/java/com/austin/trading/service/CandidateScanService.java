package com.austin.trading.service;

import com.austin.trading.client.TwseMisClient;
import com.austin.trading.client.dto.StockQuote;
import com.austin.trading.dto.request.CandidateBatchItemRequest;
import com.austin.trading.dto.request.FinalDecisionCandidateRequest;
import com.austin.trading.dto.response.CandidateResponse;
import com.austin.trading.dto.response.LiveQuoteResponse;
import com.austin.trading.entity.CandidateStockEntity;
import com.austin.trading.entity.StockEvaluationEntity;
import com.austin.trading.entity.ThemeSnapshotEntity;
import com.austin.trading.repository.CandidateStockRepository;
import com.austin.trading.repository.StockEvaluationRepository;
import com.austin.trading.repository.ThemeSnapshotRepository;
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

    private final CandidateStockRepository candidateStockRepository;
    private final StockEvaluationRepository stockEvaluationRepository;
    private final ThemeSnapshotRepository themeSnapshotRepository;
    private final TwseMisClient twseMisClient;

    public CandidateScanService(
            CandidateStockRepository candidateStockRepository,
            StockEvaluationRepository stockEvaluationRepository,
            ThemeSnapshotRepository themeSnapshotRepository,
            TwseMisClient twseMisClient
    ) {
        this.candidateStockRepository = candidateStockRepository;
        this.stockEvaluationRepository = stockEvaluationRepository;
        this.themeSnapshotRepository = themeSnapshotRepository;
        this.twseMisClient = twseMisClient;
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

    /**
     * 批次 upsert 候選股。
     * <p>
     * 每筆依 (tradingDate, symbol) 決定新增或更新。
     * 若請求含評估欄位（valuationMode / stopLossPrice 等），
     * 同步 upsert stock_evaluation 表。
     * </p>
     *
     * @param items 候選股清單
     * @return 當日最新候選股（最多 200 筆）
     */
    @Transactional
    public List<CandidateResponse> saveBatch(List<CandidateBatchItemRequest> items) {
        LocalDate today = LocalDate.now();
        List<LocalDate> affectedDates = new ArrayList<>();

        for (CandidateBatchItemRequest item : items) {
            LocalDate date = item.tradingDate() != null ? item.tradingDate() : today;
            if (!affectedDates.contains(date)) affectedDates.add(date);

            // upsert candidate_stock
            CandidateStockEntity entity = candidateStockRepository
                    .findByTradingDateAndSymbol(date, item.symbol())
                    .orElseGet(CandidateStockEntity::new);
            entity.setTradingDate(date);
            entity.setSymbol(item.symbol());
            if (item.stockName()   != null) entity.setStockName(item.stockName());
            if (item.score()       != null) entity.setScore(item.score());
            if (item.reason()      != null) entity.setReason(item.reason());
            if (item.payloadJson() != null) entity.setPayloadJson(item.payloadJson());
            candidateStockRepository.save(entity);

            // upsert stock_evaluation（若有任何評估欄位）
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

        // 回傳影響到的日期中最新一天的候選股
        LocalDate latestDate = affectedDates.stream().max(LocalDate::compareTo).orElse(today);
        return getCandidatesByDate(latestDate, 200);
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
