package com.austin.trading.controller;

import com.austin.trading.dto.request.FinalDecisionEvaluateRequest;
import com.austin.trading.dto.request.HourlyGateEvaluateRequest;
import com.austin.trading.dto.request.MarketGateEvaluateRequest;
import com.austin.trading.dto.request.PositionSizingEvaluateRequest;
import com.austin.trading.dto.request.StopLossTakeProfitEvaluateRequest;
import com.austin.trading.dto.response.FinalDecisionRecordResponse;
import com.austin.trading.dto.response.FinalDecisionResponse;
import com.austin.trading.dto.response.HourlyGateDecisionRecordResponse;
import com.austin.trading.dto.response.HourlyGateDecisionResponse;
import com.austin.trading.dto.response.MarketGateDecisionResponse;
import com.austin.trading.dto.response.PositionSizingResponse;
import com.austin.trading.dto.response.StopLossTakeProfitResponse;
import com.austin.trading.dto.request.StockEvaluateRequest;
import com.austin.trading.dto.response.StockEvaluateResult;
import com.austin.trading.engine.FinalDecisionEngine;
import com.austin.trading.engine.HourlyGateEngine;
import com.austin.trading.engine.MarketGateEngine;
import com.austin.trading.engine.PositionSizingEngine;
import com.austin.trading.engine.StockEvaluationEngine;
import com.austin.trading.engine.StopLossTakeProfitEngine;
import com.austin.trading.dto.internal.MarketRegimeDecision;
import com.austin.trading.dto.internal.MarketRegimeInput;
import com.austin.trading.service.FinalDecisionService;
import com.austin.trading.service.HourlyGateDecisionService;
import com.austin.trading.service.MarketRegimeService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/decisions")
public class DecisionController {

    private final MarketGateEngine marketGateEngine;
    private final HourlyGateEngine hourlyGateEngine;
    private final FinalDecisionEngine finalDecisionEngine;
    private final FinalDecisionService finalDecisionService;
    private final HourlyGateDecisionService hourlyGateDecisionService;
    private final PositionSizingEngine positionSizingEngine;
    private final StopLossTakeProfitEngine stopLossTakeProfitEngine;
    private final StockEvaluationEngine stockEvaluationEngine;
    private final MarketRegimeService marketRegimeService;

    public DecisionController(
            MarketGateEngine marketGateEngine,
            HourlyGateEngine hourlyGateEngine,
            FinalDecisionEngine finalDecisionEngine,
            FinalDecisionService finalDecisionService,
            HourlyGateDecisionService hourlyGateDecisionService,
            PositionSizingEngine positionSizingEngine,
            StopLossTakeProfitEngine stopLossTakeProfitEngine,
            StockEvaluationEngine stockEvaluationEngine,
            MarketRegimeService marketRegimeService
    ) {
        this.marketGateEngine = marketGateEngine;
        this.hourlyGateEngine = hourlyGateEngine;
        this.finalDecisionEngine = finalDecisionEngine;
        this.finalDecisionService = finalDecisionService;
        this.hourlyGateDecisionService = hourlyGateDecisionService;
        this.positionSizingEngine = positionSizingEngine;
        this.stopLossTakeProfitEngine = stopLossTakeProfitEngine;
        this.stockEvaluationEngine = stockEvaluationEngine;
        this.marketRegimeService = marketRegimeService;
    }

    @GetMapping("/current")
    public ResponseEntity<FinalDecisionRecordResponse> getCurrent() {
        return finalDecisionService.getCurrent()
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping("/history")
    public List<FinalDecisionRecordResponse> getHistory(@RequestParam(defaultValue = "50") int limit) {
        return finalDecisionService.getHistory(limit);
    }

    /**
     * 手動觸發今日（或指定日）最終決策評估並寫入 DB。
     * POST /api/decisions/final/evaluate-persist?date=2026-04-18
     */
    @PostMapping("/final/evaluate-persist")
    public FinalDecisionResponse evaluateAndPersist(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        return finalDecisionService.evaluateAndPersist(date != null ? date : LocalDate.now());
    }

    @GetMapping("/hourly-gate/current")
    public ResponseEntity<HourlyGateDecisionRecordResponse> getCurrentHourlyGate() {
        return hourlyGateDecisionService.getCurrent()
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping("/hourly-gate/history")
    public List<HourlyGateDecisionRecordResponse> getHourlyGateHistory(@RequestParam(defaultValue = "50") int limit) {
        return hourlyGateDecisionService.getHistory(limit);
    }

    @PostMapping("/market-gate/evaluate")
    public MarketGateDecisionResponse evaluateMarketGate(@Valid @RequestBody MarketGateEvaluateRequest request) {
        return marketGateEngine.evaluate(request);
    }

    // ── v3 Regime layer (P0.1) ────────────────────────────────────────

    /**
     * Evaluate market regime from the latest market_snapshot and persist.
     * Returns {@code 204 No Content} if no snapshot is available.
     */
    @PostMapping("/regime/evaluate")
    public ResponseEntity<MarketRegimeDecision> evaluateRegime() {
        return marketRegimeService.evaluateAndPersist()
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /**
     * Evaluate regime with a caller-supplied input (manual override / testing).
     * Use this when market_snapshot does not yet have structured breadth/leader
     * fields — downstream jobs can short-circuit to this endpoint.
     */
    @PostMapping("/regime/evaluate-custom")
    public MarketRegimeDecision evaluateRegimeCustom(@RequestBody MarketRegimeInput input) {
        return marketRegimeService.evaluateAndPersist(input, null);
    }

    @GetMapping("/regime/current")
    public ResponseEntity<MarketRegimeDecision> getCurrentRegime() {
        return marketRegimeService.getLatestForToday()
                .or(marketRegimeService::getLatest)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping("/regime/history")
    public List<MarketRegimeDecision> getRegimeHistory(@RequestParam(defaultValue = "50") int limit) {
        return marketRegimeService.getHistory(limit);
    }

    @PostMapping("/hourly-gate/evaluate")
    public HourlyGateDecisionResponse evaluateHourlyGate(@Valid @RequestBody HourlyGateEvaluateRequest request) {
        return hourlyGateEngine.evaluate(request);
    }

    @PostMapping("/final/evaluate")
    public FinalDecisionResponse evaluateFinalDecision(@Valid @RequestBody FinalDecisionEvaluateRequest request) {
        return finalDecisionEngine.evaluate(request);
    }

    @PostMapping("/position-sizing/evaluate")
    public PositionSizingResponse evaluatePositionSizing(@Valid @RequestBody PositionSizingEvaluateRequest request) {
        return positionSizingEngine.evaluate(request);
    }

    @PostMapping("/stoploss-takeprofit/evaluate")
    public StopLossTakeProfitResponse evaluateStopLossTakeProfit(@Valid @RequestBody StopLossTakeProfitEvaluateRequest request) {
        return stopLossTakeProfitEngine.evaluate(request);
    }

    @PostMapping("/stock/evaluate")
    public StockEvaluateResult evaluateStock(@Valid @RequestBody StockEvaluateRequest request) {
        return stockEvaluationEngine.evaluate(request);
    }
}
