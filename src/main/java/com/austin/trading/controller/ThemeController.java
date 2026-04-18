package com.austin.trading.controller;

import com.austin.trading.dto.request.ClaudeThemeScoreRequest;
import com.austin.trading.dto.response.StockThemeMappingResponse;
import com.austin.trading.dto.response.ThemeSnapshotResponse;
import com.austin.trading.service.ThemeService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/themes")
public class ThemeController {

    private final ThemeService themeService;

    public ThemeController(ThemeService themeService) {
        this.themeService = themeService;
    }

    /** GET /api/themes/snapshots?date=2026-04-18 */
    @GetMapping("/snapshots")
    public List<ThemeSnapshotResponse> snapshots(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return themeService.getSnapshotsByDate(date != null ? date : LocalDate.now());
    }

    /**
     * GET /api/themes/mappings          — 全部啟用對應
     * GET /api/themes/mappings?symbol=2330   — 依個股
     * GET /api/themes/mappings?theme=AI算力  — 依題材
     */
    @GetMapping("/mappings")
    public List<StockThemeMappingResponse> mappings(
            @RequestParam(required = false) String symbol,
            @RequestParam(required = false) String theme) {
        if (symbol != null) return themeService.getMappingsBySymbol(symbol);
        if (theme  != null) return themeService.getMappingsByTheme(theme);
        return themeService.getAllActiveMappings();
    }

    /** POST /api/themes/mappings  body: {symbol, stockName, themeTag, source} */
    @PostMapping("/mappings")
    public StockThemeMappingResponse addMapping(@RequestBody Map<String, String> body) {
        return themeService.addMapping(
                body.get("symbol"),
                body.get("stockName"),
                body.get("themeTag"),
                body.get("source")
        );
    }

    /**
     * Claude 題材評分回填（heat + continuation），自動重算 final_theme_score。
     *
     * <pre>
     * PUT /api/themes/snapshots/{themeTag}/claude-scores
     * {
     *   "tradingDate":            "2026-04-18",
     *   "themeHeatScore":         8.5,
     *   "themeContinuationScore": 7.0,
     *   "driverType":             "法說",
     *   "riskSummary":            "高檔追價風險"
     * }
     * </pre>
     */
    @PutMapping("/snapshots/{themeTag}/claude-scores")
    public ResponseEntity<?> updateClaudeScores(
            @PathVariable String themeTag,
            @RequestBody ClaudeThemeScoreRequest req
    ) {
        try {
            ThemeSnapshotResponse result = themeService.mergeClaudeScores(themeTag, req);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
