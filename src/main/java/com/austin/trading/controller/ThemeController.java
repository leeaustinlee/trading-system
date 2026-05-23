package com.austin.trading.controller;

import com.austin.trading.dto.request.ClaudeThemeScoreRequest;
import com.austin.trading.dto.response.StockThemeMappingResponse;
import com.austin.trading.dto.response.ThemeExposureResponse;
import com.austin.trading.dto.response.ThemeLeadershipSnapshotResponse;
import com.austin.trading.dto.response.ThemeManualReviewQueueResponse;
import com.austin.trading.dto.response.ThemeMappingObservabilityResponse;
import com.austin.trading.dto.response.ThemeSnapshotResponse;
import com.austin.trading.dto.response.ThemeTaxonomyResponse;
import com.austin.trading.service.ThemeExposureService;
import com.austin.trading.service.ThemeLeadershipObservabilityService;
import com.austin.trading.service.ThemeObservabilityService;
import com.austin.trading.service.ThemeService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/themes")
public class ThemeController {

    private final ThemeService themeService;
    private final ThemeExposureService themeExposureService;
    private final ThemeObservabilityService themeObservabilityService;
    private final ThemeLeadershipObservabilityService themeLeadershipObservabilityService;

    public ThemeController(ThemeService themeService,
                            ThemeExposureService themeExposureService,
                            ThemeObservabilityService themeObservabilityService,
                            ThemeLeadershipObservabilityService themeLeadershipObservabilityService) {
        this.themeService = themeService;
        this.themeExposureService = themeExposureService;
        this.themeObservabilityService = themeObservabilityService;
        this.themeLeadershipObservabilityService = themeLeadershipObservabilityService;
    }

    /**
     * v2.16 Batch C：GET /api/themes/exposure
     * 回傳每個題材的當前曝險百分比 + status (OK/WARN/OVER_LIMIT) + limit/warn 閾值。
     */
    @GetMapping("/exposure")
    public ThemeExposureResponse exposure() {
        return themeExposureService.computeCurrentExposure();
    }

    /** GET /api/themes/snapshots?date=2026-04-18 */
    @GetMapping("/snapshots")
    public List<ThemeSnapshotResponse> snapshots(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return themeService.getSnapshotsByDate(date != null ? date : LocalDate.now());
    }

    /**
     * Theme-first MVP-1A：POST /api/themes/leadership/snapshots
     * Persist read-only observability rows from a market-breadth scan payload.
     * This does not call FinalDecisionEngine and does not affect candidate ranking.
     */
    @PostMapping("/leadership/snapshots")
    public ThemeLeadershipSnapshotResponse generateLeadershipSnapshot(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "POSTMARKET") String sourcePhase,
            @RequestBody JsonNode payload) {
        return themeLeadershipObservabilityService.generateSnapshot(
                date != null ? date : LocalDate.now(), sourcePhase, payload);
    }

    /** GET /api/themes/leadership/snapshots?date=2026-05-22&sourcePhase=POSTMARKET */
    @GetMapping("/leadership/snapshots")
    public ThemeLeadershipSnapshotResponse leadershipSnapshot(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) String sourcePhase,
            @RequestParam(defaultValue = "50") int limit) {
        return themeLeadershipObservabilityService.getSnapshot(date != null ? date : LocalDate.now(), sourcePhase, limit);
    }

    /** GET /api/themes/leadership/divergence?date=2026-05-22 */
    @GetMapping("/leadership/divergence")
    public ThemeLeadershipSnapshotResponse leadershipDivergence(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "50") int limit) {
        return themeLeadershipObservabilityService.getDivergence(date != null ? date : LocalDate.now(), limit);
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

    /**
     * W2-1 Truth Layer：GET /api/themes/taxonomy
     * Read-only 題材 taxonomy / snapshot / mapping 聚合；不改交易決策語意。
     */
    @GetMapping("/taxonomy")
    public ThemeTaxonomyResponse taxonomy(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "true") boolean activeOnly) {
        return themeObservabilityService.getTaxonomy(date != null ? date : LocalDate.now(), activeOnly);
    }

    /**
     * W2-1 Truth Layer：GET /api/themes/mappings/observability
     * Read-only mapping coverage / quality dashboard；不寫入 mapping，不影響 scoring。
     */
    @GetMapping("/mappings/observability")
    public ThemeMappingObservabilityResponse mappingObservability(
            @RequestParam(required = false) String symbol,
            @RequestParam(required = false) String theme,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) Boolean activeOnly,
            @RequestParam(required = false) java.math.BigDecimal minConfidence,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String suggestedCategory,
            @RequestParam(required = false) Boolean unresolvedOtherOnly,
            @RequestParam(required = false) String reviewPriority,
            @RequestParam(required = false) String recommendedAction) {
        return themeObservabilityService.getMappingObservability(
                symbol, theme, category, source, activeOnly, minConfidence, limit,
                suggestedCategory, unresolvedOtherOnly, reviewPriority, recommendedAction);
    }

    /**
     * W2-12：GET /api/themes/mappings/manual-review-queue
     * Read-only unresolved OTHER queue for human taxonomy review; no mapping writes.
     */
    @GetMapping("/mappings/manual-review-queue")
    public ThemeManualReviewQueueResponse manualReviewQueue(
            @RequestParam(required = false) Boolean activeOnly,
            @RequestParam(required = false) String reviewPriority,
            @RequestParam(required = false) Integer limit) {
        return themeObservabilityService.getManualReviewQueue(activeOnly, reviewPriority, limit);
    }

    /**
     * W2-15：GET /api/themes/mappings/manual-review-queue/worksheet.csv
     * Read-only CSV worksheet for operator evidence collection; no mapping writes.
     */
    @GetMapping(value = "/mappings/manual-review-queue/worksheet.csv", produces = "text/csv")
    public ResponseEntity<String> manualReviewWorksheetCsv(
            @RequestParam(required = false) Boolean activeOnly,
            @RequestParam(required = false) String reviewPriority,
            @RequestParam(required = false) Integer limit) {
        String csv = themeObservabilityService.getManualReviewWorksheetCsv(activeOnly, reviewPriority, limit);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=theme-manual-review-worksheet.csv")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body("\uFEFF" + csv);
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
