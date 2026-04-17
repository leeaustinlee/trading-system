package com.austin.trading.controller;

import com.austin.trading.dto.request.CandidateBatchItemRequest;
import com.austin.trading.dto.response.CandidateResponse;
import com.austin.trading.dto.response.LiveQuoteResponse;
import com.austin.trading.service.CandidateScanService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 候選股 API。
 *
 * <ul>
 *   <li>GET  /api/candidates/current           讀取今日候選股</li>
 *   <li>GET  /api/candidates/live-quotes       今日候選股即時報價（打 TWSE MIS）</li>
 *   <li>GET  /api/candidates/history           讀取歷史候選股</li>
 *   <li>POST /api/candidates/batch             批次寫入候選股（Codex 用）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/candidates")
public class CandidateController {

    private final CandidateScanService candidateScanService;

    public CandidateController(CandidateScanService candidateScanService) {
        this.candidateScanService = candidateScanService;
    }

    @GetMapping("/current")
    public List<CandidateResponse> getCurrent(@RequestParam(defaultValue = "20") int limit) {
        return candidateScanService.getCurrentCandidates(limit);
    }

    /**
     * 今日候選股即時報價。
     * <p>打 TWSE MIS API 取得最新成交價，盤外時段回傳昨收與開盤等靜態資料。</p>
     */
    @GetMapping("/live-quotes")
    public List<LiveQuoteResponse> getLiveQuotes() {
        return candidateScanService.getCurrentLiveQuotes();
    }

    @GetMapping("/history")
    public List<CandidateResponse> getHistory(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "100") int limit
    ) {
        if (date != null) {
            return candidateScanService.getCandidatesByDate(date, limit);
        }
        return candidateScanService.getCandidatesHistory(limit);
    }

    /**
     * 批次 upsert 候選股。
     * <p>
     * Codex 盤後分析完成後，呼叫此端點將選出的候選股寫入 DB。
     * 同一 (tradingDate, symbol) 重複呼叫會覆蓋，不會重複新增。
     * </p>
     *
     * <pre>
     * POST /api/candidates/batch
     * Content-Type: application/json
     *
     * [
     *   {
     *     "tradingDate": "2026-04-17",       // 選填，null 則補今日
     *     "symbol": "2330",
     *     "stockName": "台積電",
     *     "score": 8.5,
     *     "reason": "AI PCB族群強勢，突破整理區",
     *     "valuationMode": "MOMENTUM",
     *     "entryPriceZone": "980-1000",
     *     "stopLossPrice": 940,
     *     "takeProfit1": 1060,
     *     "takeProfit2": 1120,
     *     "riskRewardRatio": 2.5,
     *     "includeInFinalPlan": true
     *   }
     * ]
     * </pre>
     *
     * @return 儲存後當日所有候選股
     */
    @PostMapping("/batch")
    public ResponseEntity<Map<String, Object>> saveBatch(
            @RequestBody @Valid List<CandidateBatchItemRequest> items
    ) {
        if (items == null || items.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "候選股清單不可為空"));
        }
        List<CandidateResponse> saved = candidateScanService.saveBatch(items);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "saved", items.size(),
                "currentDayCandidates", saved.size(),
                "candidates", saved
        ));
    }
}
