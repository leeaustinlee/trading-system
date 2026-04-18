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
import com.austin.trading.engine.FinalDecisionEngine;
import com.austin.trading.engine.PositionSizingEngine;
import com.austin.trading.engine.StopLossTakeProfitEngine;
import com.austin.trading.entity.CapitalConfigEntity;
import com.austin.trading.entity.FinalDecisionEntity;
import com.austin.trading.repository.FinalDecisionRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
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

    private final FinalDecisionEngine finalDecisionEngine;
    private final PositionSizingEngine positionSizingEngine;
    private final StopLossTakeProfitEngine stopLossTakeProfitEngine;
    private final FinalDecisionRepository finalDecisionRepository;
    private final MarketDataService marketDataService;
    private final TradingStateService tradingStateService;
    private final CandidateScanService candidateScanService;
    private final CapitalService capitalService;
    private final ObjectMapper objectMapper;

    public FinalDecisionService(
            FinalDecisionEngine finalDecisionEngine,
            PositionSizingEngine positionSizingEngine,
            StopLossTakeProfitEngine stopLossTakeProfitEngine,
            FinalDecisionRepository finalDecisionRepository,
            MarketDataService marketDataService,
            TradingStateService tradingStateService,
            CandidateScanService candidateScanService,
            CapitalService capitalService,
            ObjectMapper objectMapper
    ) {
        this.finalDecisionEngine = finalDecisionEngine;
        this.positionSizingEngine = positionSizingEngine;
        this.stopLossTakeProfitEngine = stopLossTakeProfitEngine;
        this.finalDecisionRepository = finalDecisionRepository;
        this.marketDataService = marketDataService;
        this.tradingStateService = tradingStateService;
        this.candidateScanService = candidateScanService;
        this.capitalService = capitalService;
        this.objectMapper = objectMapper;
    }

    public FinalDecisionResponse evaluateAndPersist(LocalDate tradingDate) {
        MarketCurrentResponse market = marketDataService.getCurrentMarket().orElse(null);
        TradingStateResponse state = tradingStateService.getCurrentState().orElse(null);

        String marketGrade = market == null ? "B" : safe(market.marketGrade(), "B");

        List<FinalDecisionCandidateRequest> candidates =
                candidateScanService.loadFinalDecisionCandidates(tradingDate, 10);

        FinalDecisionEvaluateRequest request = new FinalDecisionEvaluateRequest(
                marketGrade,
                state == null ? "NONE" : safe(state.decisionLock(), "NONE"),
                state == null ? "EARLY" : safe(state.timeDecayStage(), "EARLY"),
                false,
                candidates
        );

        FinalDecisionResponse decision = finalDecisionEngine.evaluate(request);

        // 建立 candidateMap 以便回查估值模式
        Map<String, FinalDecisionCandidateRequest> candidateMap = candidates.stream()
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

    private String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
