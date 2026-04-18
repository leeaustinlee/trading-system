package com.austin.trading.controller;

import com.austin.trading.ai.AiFacade;
import com.austin.trading.dto.response.AiResearchResponse;
import com.austin.trading.service.AiResearchService;
import com.austin.trading.service.CandidateScanService;
import com.austin.trading.service.ClaudeCodeRequestWriterService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * AI 研究記錄 API。
 *
 * <ul>
 *   <li>GET  /api/ai/research?date=2026-04-17&type=PREMARKET       查詢研究記錄</li>
 *   <li>POST /api/ai/research/premarket                             觸發盤前研究（直接呼叫 Claude API）</li>
 *   <li>POST /api/ai/research/stock/{symbol}                        觸發個股研究</li>
 *   <li>POST /api/ai/research/final-decision                        觸發最終決策研究</li>
 *   <li>POST /api/ai/research/write-request                         寫入研究請求檔（Claude Code Agent 模式）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/ai")
public class AiController {

    private final AiResearchService          aiResearchService;
    private final AiFacade                   aiFacade;
    private final ClaudeCodeRequestWriterService requestWriterService;
    private final CandidateScanService       candidateScanService;

    public AiController(
            AiResearchService aiResearchService,
            AiFacade aiFacade,
            ClaudeCodeRequestWriterService requestWriterService,
            CandidateScanService candidateScanService
    ) {
        this.aiResearchService    = aiResearchService;
        this.aiFacade             = aiFacade;
        this.requestWriterService = requestWriterService;
        this.candidateScanService = candidateScanService;
    }

    // ── 查詢 ──────────────────────────────────────────────────────────────────

    @GetMapping("/research")
    public List<AiResearchResponse> getResearch(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) String type
    ) {
        LocalDate queryDate = date != null ? date : LocalDate.now();
        if (type != null && !type.isBlank()) {
            return aiResearchService.getByDateAndType(queryDate, type.toUpperCase());
        }
        return aiResearchService.getByDate(queryDate);
    }

    // ── 觸發研究（手動呼叫 / 排程備用）──────────────────────────────────────────

    @PostMapping("/research/premarket")
    public AiResearchResponse triggerPremarket(
            @RequestParam(required = false) String txfSummary,
            @RequestParam(required = false) String globalSummary
    ) {
        return aiFacade.doPremarketResearch(LocalDate.now(), txfSummary, globalSummary);
    }

    @PostMapping("/research/stock/{symbol}")
    public AiResearchResponse triggerStockEval(
            @PathVariable String symbol,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        return aiFacade.doStockEvaluation(date != null ? date : LocalDate.now(), symbol);
    }

    @PostMapping("/research/final-decision")
    public AiResearchResponse triggerFinalDecision(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        return aiFacade.doFinalDecisionResearch(date != null ? date : LocalDate.now());
    }

    /**
     * 寫入研究請求檔，供 Claude Code Agent 讀取後執行研究。
     * <p>
     * 無 API Key 模式下的觸發方式：Java 整理候選股清單 → 寫出 JSON 請求 →
     * Claude Code 讀取並分析 → 寫回 claude-research-latest.md →
     * 使用者點「匯入最新研究報告」補入 DB。
     * </p>
     * <pre>
     * POST /api/ai/research/write-request
     *   ?type=PREMARKET          （PREMARKET / FINAL_DECISION / STOCK_EVAL / POSTMARKET / MIDDAY）
     *   &symbol=2330             （個股研究時帶入，其他類型省略）
     *   &context=xxx             （補充 context，可省略）
     * </pre>
     */
    @PostMapping("/research/write-request")
    public Map<String, Object> writeResearchRequest(
            @RequestParam String type,
            @RequestParam(required = false) String symbol,
            @RequestParam(required = false) String context
    ) {
        List<String> symbols;
        if (symbol != null && !symbol.isBlank()) {
            symbols = List.of(symbol.trim());
        } else {
            symbols = candidateScanService.getCurrentCandidates(10)
                    .stream().map(c -> c.symbol()).toList();
        }
        boolean ok = requestWriterService.writeRequest(type.toUpperCase(), LocalDate.now(), symbols, context);
        String msg = ok
                ? "請求已寫入，等待 Claude Code Agent 執行後按「匯入最新研究報告」"
                : "請求寫入失敗（request-output-path 未設定）";
        return Map.of("success", ok, "type", type.toUpperCase(),
                      "candidateCount", symbols.size(), "message", msg);
    }

    /**
     * 從本機 Markdown 檔案匯入研究結果到 DB。
     * <p>
     * 用途：把 Claude Code 排程 Agent 已寫好的研究檔補入資料庫。
     * </p>
     * <pre>
     * POST /api/ai/research/import-file
     *   ?filePath=/mnt/d/ai/stock/claude-research-latest.md
     *   &researchType=T86_TOMORROW
     *   &tradingDate=2026-04-17          （選填，預設今日）
     * </pre>
     */
    @PostMapping("/research/import-file")
    public ResponseEntity<Map<String, Object>> importFromFile(
            @RequestParam String filePath,
            @RequestParam(defaultValue = "POSTMARKET") String researchType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate tradingDate,
            @RequestParam(required = false) String symbol
    ) {
        LocalDate date = tradingDate != null ? tradingDate : LocalDate.now();
        return aiResearchService.importFromPath(filePath, date, researchType.toUpperCase(), symbol)
                .<ResponseEntity<Map<String, Object>>>map(r -> ResponseEntity.ok(Map.of(
                        "success", true,
                        "id", r.id(),
                        "researchType", r.researchType(),
                        "tradingDate", r.tradingDate().toString(),
                        "contentLength", r.researchResult() != null ? r.researchResult().length() : 0
                )))
                .orElseGet(() -> ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "error", "File not found or read failed: " + filePath
                )));
    }
}
