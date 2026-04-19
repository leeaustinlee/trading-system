package com.austin.trading.controller;

import com.austin.trading.workflow.HourlyGateWorkflowService;
import com.austin.trading.workflow.IntradayDecisionWorkflowService;
import com.austin.trading.workflow.PostmarketWorkflowService;
import com.austin.trading.workflow.PremarketWorkflowService;
import com.austin.trading.workflow.WatchlistWorkflowService;
import com.austin.trading.service.StrategyRecommendationService;
import com.austin.trading.service.TradeReviewService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;

/**
 * 手動觸發 Workflow 的 API。
 *
 * <p>每個 endpoint 對應一個既有 scheduler 的 Job，用於測試或人工介入。</p>
 */
@RestController
@RequestMapping("/api/workflow")
public class WorkflowController {

    private final PremarketWorkflowService premarketWorkflow;
    private final IntradayDecisionWorkflowService intradayDecisionWorkflow;
    private final HourlyGateWorkflowService hourlyGateWorkflow;
    private final PostmarketWorkflowService postmarketWorkflow;
    private final WatchlistWorkflowService watchlistWorkflow;
    private final TradeReviewService tradeReviewService;
    private final StrategyRecommendationService strategyRecommendationService;

    public WorkflowController(
            PremarketWorkflowService premarketWorkflow,
            IntradayDecisionWorkflowService intradayDecisionWorkflow,
            HourlyGateWorkflowService hourlyGateWorkflow,
            PostmarketWorkflowService postmarketWorkflow,
            WatchlistWorkflowService watchlistWorkflow,
            TradeReviewService tradeReviewService,
            StrategyRecommendationService strategyRecommendationService
    ) {
        this.premarketWorkflow = premarketWorkflow;
        this.intradayDecisionWorkflow = intradayDecisionWorkflow;
        this.hourlyGateWorkflow = hourlyGateWorkflow;
        this.postmarketWorkflow = postmarketWorkflow;
        this.watchlistWorkflow = watchlistWorkflow;
        this.tradeReviewService = tradeReviewService;
        this.strategyRecommendationService = strategyRecommendationService;
    }

    @PostMapping("/premarket/trigger")
    public ResponseEntity<?> premarket(@RequestParam(required = false)
                                        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        LocalDate d = date != null ? date : LocalDate.now();
        premarketWorkflow.execute(d);
        return ResponseEntity.ok(Map.of("ok", true, "workflow", "premarket", "date", d));
    }

    @PostMapping("/final-decision/trigger")
    public ResponseEntity<?> finalDecision(@RequestParam(required = false)
                                            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        LocalDate d = date != null ? date : LocalDate.now();
        intradayDecisionWorkflow.execute(d);
        return ResponseEntity.ok(Map.of("ok", true, "workflow", "final-decision", "date", d));
    }

    @PostMapping("/hourly-gate/trigger")
    public ResponseEntity<?> hourlyGate(@RequestParam(required = false)
                                         @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        LocalDate d = date != null ? date : LocalDate.now();
        hourlyGateWorkflow.execute(d, LocalTime.now());
        return ResponseEntity.ok(Map.of("ok", true, "workflow", "hourly-gate", "date", d));
    }

    @PostMapping("/postmarket/trigger")
    public ResponseEntity<?> postmarket(@RequestParam(required = false)
                                         @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        LocalDate d = date != null ? date : LocalDate.now();
        postmarketWorkflow.execute(d);
        return ResponseEntity.ok(Map.of("ok", true, "workflow", "postmarket", "date", d));
    }

    @PostMapping("/watchlist-refresh/trigger")
    public ResponseEntity<?> watchlistRefresh(@RequestParam(required = false)
                                                @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        LocalDate d = date != null ? date : LocalDate.now();
        watchlistWorkflow.execute(d);
        return ResponseEntity.ok(Map.of("ok", true, "workflow", "watchlist-refresh", "date", d));
    }

    @PostMapping("/weekly-review/trigger")
    public ResponseEntity<?> weeklyReview() {
        int reviewed = tradeReviewService.generateForAllUnreviewed();
        var recs = strategyRecommendationService.generate(null);
        return ResponseEntity.ok(Map.of("ok", true, "reviewed", reviewed, "recommendations", recs.size()));
    }
}
