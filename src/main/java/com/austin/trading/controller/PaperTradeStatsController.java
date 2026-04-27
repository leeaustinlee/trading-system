package com.austin.trading.controller;

import com.austin.trading.dto.response.PaperTradeStatsResponse;
import com.austin.trading.dto.response.RecentPaperTradeItem;
import com.austin.trading.service.PaperTradeStatsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Subagent D — paper trade aggregated statistics + recent timeline.
 *
 * <ul>
 *   <li>{@code GET /api/paper/stats?days=30} — full aggregated stats (winRate / sharpe /
 *       drawdown / by-grade / by-theme / by-regime / by-exit-reason).</li>
 *   <li>{@code GET /api/paper/recent?limit=20} — most-recent CLOSED trades for timeline view.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/paper")
public class PaperTradeStatsController {

    private final PaperTradeStatsService statsService;

    public PaperTradeStatsController(PaperTradeStatsService statsService) {
        this.statsService = statsService;
    }

    @GetMapping("/stats")
    public ResponseEntity<PaperTradeStatsResponse> stats(
            @RequestParam(defaultValue = "30") int days) {
        // Clamp to safe range
        int safe = Math.max(1, Math.min(days, 365));
        return ResponseEntity.ok(statsService.computeStats(safe));
    }

    @GetMapping("/recent")
    public ResponseEntity<List<RecentPaperTradeItem>> recent(
            @RequestParam(defaultValue = "20") int limit) {
        int safe = Math.max(1, Math.min(limit, 200));
        return ResponseEntity.ok(statsService.listRecent(safe));
    }
}
