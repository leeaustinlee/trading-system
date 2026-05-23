package com.austin.trading.controller;

import com.austin.trading.dto.request.AiScoreUpdateRequest;
import com.austin.trading.dto.request.CandidateBatchItemRequest;
import com.austin.trading.dto.response.CandidateBatchSaveResponse;
import com.austin.trading.dto.response.CandidateResponse;
import com.austin.trading.dto.response.LiveQuoteResponse;
import com.austin.trading.entity.StockEvaluationEntity;
import com.austin.trading.repository.CandidateStockRepository;
import com.austin.trading.repository.StockEvaluationRepository;
import com.austin.trading.service.CandidateScanService;
import com.austin.trading.service.StockEvaluationService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 候選股 API。
 *
 * <ul>
 *   <li>GET  /api/candidates/current           舊相容：今天優先，無今天才回 DB 最新</li>
 *   <li>GET  /api/candidates/latest            UI 用：永遠讀 DB 最新候選日</li>
 *   <li>GET  /api/candidates/today             明確讀今日候選股</li>
 *   <li>GET  /api/candidates/next              明確讀下一個已寫入 DB 的候選日</li>
 *   <li>GET  /api/candidates/live-quotes       舊相容：current 候選股即時報價（打 TWSE MIS）</li>
 *   <li>GET  /api/candidates/latest/live-quotes 最新候選股即時報價（打 TWSE MIS）</li>
 *   <li>GET  /api/candidates/history           讀取歷史候選股</li>

 * </ul>
 */
@RestController
@RequestMapping("/api/candidates")
public class CandidateController {

    private final CandidateScanService    candidateScanService;
    private final StockEvaluationService stockEvaluationService;
    @Autowired private CandidateStockRepository candidateStockRepository;
    @Autowired private StockEvaluationRepository stockEvaluationRepository;

    public CandidateController(CandidateScanService candidateScanService,
                               StockEvaluationService stockEvaluationService) {
        this.candidateScanService    = candidateScanService;
        this.stockEvaluationService  = stockEvaluationService;
    }

    @GetMapping("/current")
    public List<CandidateResponse> getCurrent(@RequestParam(defaultValue = "20") int limit) {
        return candidateScanService.getCurrentCandidates(limit);
    }

    @GetMapping("/latest")
    public List<CandidateResponse> getLatest(@RequestParam(defaultValue = "20") int limit) {
        return candidateScanService.getLatestCandidates(limit);
    }

    @GetMapping("/theme-first/current")
    public Map<String, Object> getThemeFirstCurrent(@RequestParam(defaultValue = "10") int limit) {
        List<CandidateResponse> candidates = candidateScanService.getThemeFirstCurrentCandidates(limit);
        return candidateScanService.buildThemeFirstReplay(candidates.isEmpty() ? null : candidates.get(0).tradingDate(), candidates);
    }

    @GetMapping("/theme-first/replay")
    public Map<String, Object> getThemeFirstReplay(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "10") int limit
    ) {
        return candidateScanService.buildThemeFirstReplay(date, candidateScanService.getThemeFirstCandidatesByDate(date, limit));
    }

    @GetMapping("/theme-first/trace/{traceId}")
    public Map<String, Object> getThemeFirstTrace(@PathVariable String traceId) {
        return candidateScanService.getThemeFirstTrace(traceId);
    }

    @GetMapping("/today")
    public List<CandidateResponse> getToday(@RequestParam(defaultValue = "20") int limit) {
        return candidateScanService.getTodayCandidates(limit);
    }

    @GetMapping("/next")
    public List<CandidateResponse> getNext(@RequestParam(defaultValue = "20") int limit) {
        return candidateScanService.getNextCandidates(limit);
    }

    /**
     * 今日候選股即時報價。
     * <p>打 TWSE MIS API 取得最新成交價，盤外時段回傳昨收與開盤等靜態資料。</p>
     */
    @GetMapping("/live-quotes")
    public List<LiveQuoteResponse> getLiveQuotes() {
        return candidateScanService.getCurrentLiveQuotes();
    }

    @GetMapping("/latest/live-quotes")
    public List<LiveQuoteResponse> getLatestLiveQuotes() {
        return candidateScanService.getLatestLiveQuotes();
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
        // 2026-04-29 P0.1：每筆 request 先過 changePct hard gate + MomentumCandidateEngine hard gate；
        // 通過者寫入並標 is_momentum_candidate=true，未通過者列在 rejections。
        // 兩個 gate 都吃 ScoreConfigService flag，預設 OFF（pure compatibility）。
        CandidateBatchSaveResponse result = candidateScanService.saveBatchWithGate(items);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success",              true);
        body.put("received",             result.received());
        body.put("accepted",             result.accepted());
        body.put("rejected",             result.rejected());
        body.put("rejections",           result.rejections());
        body.put("items",                result.items());
        // ── 舊欄位（保留向後相容）─────────────────────────────────────
        body.put("saved",                result.accepted());
        body.put("currentDayCandidates", result.items().size());
        body.put("candidates",           result.items());
        return ResponseEntity.ok(body);
    }

    /**
     * AI 評分回填（Claude / Codex 研究完成後呼叫）。
     * <p>
     * 更新 stock_evaluation 的 claude_score / codex_score 等欄位，
     * 並自動重算 ai_weighted_score 與 final_rank_score。
     * </p>
     *
     * <pre>
     * PUT /api/candidates/{symbol}/ai-scores
     * {
     *   "tradingDate": "2026-04-18",
     *   "claudeScore": 7.5,
     *   "claudeThesis": "AI PCB族群延續，法人持續買超",
     *   "claudeRiskFlags": ["接近前波高點", "大盤若轉弱優先出場"],
     *   "codexScore": 8.0
     * }
     * </pre>
     */
    @PutMapping("/{symbol}/ai-scores")
    public ResponseEntity<?> updateAiScores(
            @PathVariable String symbol,
            @RequestBody AiScoreUpdateRequest req
    ) {
        try {
            StockEvaluationEntity updated = stockEvaluationService.updateAiScores(symbol, req);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("success",         true);
            body.put("symbol",          symbol);
            body.put("tradingDate",     updated.getTradingDate());
            body.put("claudeScore",     updated.getClaudeScore());
            body.put("codexScore",      updated.getCodexScore());
            body.put("aiWeightedScore", updated.getAiWeightedScore());
            body.put("consensusScore",  updated.getConsensusScore());
            body.put("finalRankScore",  updated.getFinalRankScore());
            body.put("isVetoed",        Boolean.TRUE.equals(updated.getIsVetoed()));
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("success", false,
                    "error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        }
    }

    /**
     * 按日期刪除候選股 + stock_evaluation（admin cleanup 用，例如清 mock 殘留）。
     * <p>用法：{@code DELETE /api/candidates/by-date/2026-04-17}</p>
     */
    @DeleteMapping("/by-date/{date}")
    @Transactional
    public Map<String, Object> deleteByDate(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        long cands = candidateStockRepository.deleteByTradingDate(date);
        long evals = stockEvaluationRepository.deleteByTradingDate(date);
        return Map.of(
                "success", true,
                "tradingDate", date.toString(),
                "deletedCandidates", cands,
                "deletedEvaluations", evals
        );
    }

    /** 切換今日候選股「納入最終計畫」狀態（toggle） */
    @PatchMapping("/{symbol}/include")
    public ResponseEntity<?> toggleInclude(@PathVariable String symbol) {
        try {
            CandidateResponse r = candidateScanService.toggleInclude(symbol);
            return r != null ? ResponseEntity.ok(r)
                    : ResponseEntity.notFound().build();
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 更新今日候選股的評估欄位（只更新有傳的欄位）。
     * Body 格式與 batch 單項相同（symbol 可省略）。
     */
    @PatchMapping("/{symbol}")
    public ResponseEntity<?> updateCandidate(
            @PathVariable String symbol,
            @RequestBody CandidateBatchItemRequest req
    ) {
        try {
            CandidateResponse r = candidateScanService.updateCandidate(symbol, req);
            return ResponseEntity.ok(r);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
