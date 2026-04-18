package com.austin.trading.controller;

import com.austin.trading.entity.WatchlistStockEntity;
import com.austin.trading.service.PositionReviewService;
import com.austin.trading.service.WatchlistService;
import com.austin.trading.workflow.WatchlistWorkflowService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/watchlist")
public class WatchlistController {

    private final WatchlistService watchlistService;
    private final WatchlistWorkflowService watchlistWorkflowService;
    private final PositionReviewService positionReviewService;

    public WatchlistController(WatchlistService watchlistService,
                               WatchlistWorkflowService watchlistWorkflowService,
                               PositionReviewService positionReviewService) {
        this.watchlistService = watchlistService;
        this.watchlistWorkflowService = watchlistWorkflowService;
        this.positionReviewService = positionReviewService;
    }

    @GetMapping("/current")
    public List<WatchlistStockEntity> getCurrent() {
        return watchlistService.getCurrentWatchlist();
    }

    @GetMapping("/ready")
    public List<WatchlistStockEntity> getReady() {
        return watchlistService.getReadyStocks();
    }

    @GetMapping("/history")
    public List<WatchlistStockEntity> getHistory() {
        return watchlistService.getHistory();
    }

    @PostMapping("/rebuild")
    public ResponseEntity<?> rebuild(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        LocalDate d = date != null ? date : LocalDate.now();
        watchlistWorkflowService.execute(d);
        return ResponseEntity.ok(Map.of("rebuilt", true, "date", d));
    }

    @PatchMapping("/{symbol}/status")
    public ResponseEntity<?> updateStatus(@PathVariable String symbol, @RequestBody Map<String, String> body) {
        String newStatus = body.get("status");
        if (newStatus == null || newStatus.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "status is required"));
        }
        WatchlistStockEntity updated = watchlistService.updateStatus(symbol, newStatus);
        return ResponseEntity.ok(updated);
    }

    @PostMapping("/position-review/trigger")
    public ResponseEntity<?> triggerPositionReview() {
        var results = positionReviewService.reviewAllOpenPositions("DAILY");
        return ResponseEntity.ok(Map.of(
                "reviewed", results.size(),
                "results", results.stream().map(r -> Map.of(
                        "symbol", r.position().getSymbol(),
                        "status", r.decision().status().name(),
                        "reason", r.decision().reason()
                )).toList()
        ));
    }
}
