package com.austin.trading.controller;

import com.austin.trading.dto.request.MarketSnapshotCreateRequest;
import com.austin.trading.dto.response.MarketCurrentResponse;
import com.austin.trading.service.MarketDataService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 市場快照 API。
 *
 * <ul>
 *   <li>GET  /api/market/current          最新市場快照</li>
 *   <li>GET  /api/market/history          市場快照歷史</li>
 *   <li>POST /api/market/snapshot         手動建立快照（Codex / 測試用）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/market")
public class MarketController {

    private final MarketDataService marketDataService;

    public MarketController(MarketDataService marketDataService) {
        this.marketDataService = marketDataService;
    }

    @GetMapping("/current")
    public ResponseEntity<MarketCurrentResponse> getCurrent() {
        return marketDataService.getCurrentMarket()
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping("/history")
    public List<MarketCurrentResponse> getHistory(@RequestParam(defaultValue = "50") int limit) {
        return marketDataService.getMarketHistory(limit);
    }

    /**
     * 手動建立市場快照。
     * <p>適用情境：Codex 盤後更新市場等級、或測試時手動設定盤型。</p>
     *
     * <pre>
     * POST /api/market/snapshot
     * {
     *   "tradingDate": "2026-04-17",
     *   "marketGrade": "B",
     *   "marketPhase": "高檔震盪期",
     *   "decision": "WATCH"
     * }
     * </pre>
     */
    @PostMapping("/snapshot")
    public MarketCurrentResponse createSnapshot(@RequestBody MarketSnapshotCreateRequest req) {
        return marketDataService.createSnapshot(req);
    }
}
