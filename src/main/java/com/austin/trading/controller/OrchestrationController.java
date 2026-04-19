package com.austin.trading.controller;

import com.austin.trading.entity.DailyOrchestrationStatusEntity;
import com.austin.trading.service.DailyOrchestrationService;
import com.austin.trading.service.OrchestrationStep;
import com.austin.trading.workflow.HourlyGateWorkflowService;
import com.austin.trading.workflow.IntradayDecisionWorkflowService;
import com.austin.trading.workflow.PostmarketWorkflowService;
import com.austin.trading.workflow.PremarketWorkflowService;
import com.austin.trading.workflow.WatchlistWorkflowService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 每日排程狀態 + 補跑 API。
 *
 * <ul>
 *     <li>{@code GET  /api/orchestration/today} — 今日所有步驟狀態</li>
 *     <li>{@code GET  /api/orchestration/{date}} — 指定日期</li>
 *     <li>{@code GET  /api/orchestration/recent?days=5} — 最近 N 天</li>
 *     <li>{@code POST /api/orchestration/recover?date=YYYY-MM-DD} — 一鍵補跑未完成步驟</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/orchestration")
public class OrchestrationController {

    private static final Logger log = LoggerFactory.getLogger(OrchestrationController.class);

    private final DailyOrchestrationService orchestrationService;
    private final PremarketWorkflowService premarketWorkflow;
    private final IntradayDecisionWorkflowService intradayDecisionWorkflow;
    private final HourlyGateWorkflowService hourlyGateWorkflow;
    private final PostmarketWorkflowService postmarketWorkflow;
    private final WatchlistWorkflowService watchlistWorkflow;

    public OrchestrationController(
            DailyOrchestrationService orchestrationService,
            PremarketWorkflowService premarketWorkflow,
            IntradayDecisionWorkflowService intradayDecisionWorkflow,
            HourlyGateWorkflowService hourlyGateWorkflow,
            PostmarketWorkflowService postmarketWorkflow,
            WatchlistWorkflowService watchlistWorkflow
    ) {
        this.orchestrationService = orchestrationService;
        this.premarketWorkflow = premarketWorkflow;
        this.intradayDecisionWorkflow = intradayDecisionWorkflow;
        this.hourlyGateWorkflow = hourlyGateWorkflow;
        this.postmarketWorkflow = postmarketWorkflow;
        this.watchlistWorkflow = watchlistWorkflow;
    }

    // ─────────────────────────────────────────────────────────────────────
    //  查詢
    // ─────────────────────────────────────────────────────────────────────

    @GetMapping("/today")
    public ResponseEntity<?> today() {
        return ResponseEntity.ok(orchestrationService.getStatusMap(LocalDate.now()));
    }

    @GetMapping("/{date}")
    public ResponseEntity<?> byDate(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.ok(orchestrationService.getStatusMap(date));
    }

    @GetMapping("/recent")
    public ResponseEntity<?> recent(@RequestParam(defaultValue = "5") int days) {
        List<DailyOrchestrationStatusEntity> rows = orchestrationService.getRecent(days);
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (DailyOrchestrationStatusEntity e : rows) {
            out.add(orchestrationService.getStatusMap(e.getTradingDate()));
        }
        return ResponseEntity.ok(out);
    }

    // ─────────────────────────────────────────────────────────────────────
    //  一鍵補跑
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 一鍵補跑指定日期所有非 DONE 的步驟。
     * <ul>
     *     <li>過去日期：只補 data-prep 類（不重發今日 LINE 通知）。</li>
     *     <li>今日：補所有可補的步驟。</li>
     * </ul>
     * 回傳每個 step 的執行結果（status / message）。
     */
    @PostMapping("/recover")
    public ResponseEntity<?> recover(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "false") boolean includeNotify) {

        boolean isPast = date.isBefore(LocalDate.now());
        List<OrchestrationStep> incomplete = orchestrationService.listIncompleteSteps(date);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("date", date.toString());
        summary.put("isPast", isPast);
        summary.put("totalIncomplete", incomplete.size());

        List<Map<String, Object>> results = new ArrayList<>();
        int done = 0;
        int failed = 0;
        int skipped = 0;

        for (OrchestrationStep step : incomplete) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("step", step.name());
            entry.put("field", step.entityField());

            // 過去日期：只補 data-prep 類
            if (isPast && !includeNotify && !isDataPrepOrSafeToReplay(step)) {
                entry.put("status", "SKIPPED");
                entry.put("reason", "past date, notify/interactive step not replayed");
                results.add(entry);
                skipped++;
                continue;
            }

            // 強制取得執行權（覆寫 DONE/RUNNING/FAILED）
            orchestrationService.forceMarkRunning(date, step);
            try {
                String result = invokeWorkflow(date, step);
                orchestrationService.markDone(date, step, "recover: " + result);
                entry.put("status", "DONE");
                entry.put("message", result);
                done++;
            } catch (UnsupportedOperationException uoe) {
                orchestrationService.markFailed(date, step, "recover unsupported: " + uoe.getMessage());
                entry.put("status", "SKIPPED");
                entry.put("reason", uoe.getMessage());
                skipped++;
            } catch (Exception e) {
                log.warn("[Orchestration] recover {} failed: {}", step, e.getMessage());
                orchestrationService.markFailed(date, step, "recover failed: " + e.getMessage());
                entry.put("status", "FAILED");
                entry.put("error", e.getMessage());
                failed++;
            }
            results.add(entry);
        }

        summary.put("done", done);
        summary.put("failed", failed);
        summary.put("skipped", skipped);
        summary.put("results", results);
        return ResponseEntity.ok(summary);
    }

    // ─────────────────────────────────────────────────────────────────────
    //  私有 helpers
    // ─────────────────────────────────────────────────────────────────────

    private boolean isDataPrepOrSafeToReplay(OrchestrationStep step) {
        return switch (step) {
            case PREMARKET_DATA_PREP,
                 OPEN_DATA_PREP,
                 POSTMARKET_DATA_PREP,
                 T86_DATA_PREP,
                 POSTMARKET_ANALYSIS,
                 WATCHLIST_REFRESH -> true;
            default -> false;
        };
    }

    /**
     * 將 step 映射到對應的 workflow。缺對應者丟 {@link UnsupportedOperationException}。
     * 注意：data-prep 類 job 內含直接 client 呼叫、不走 workflow；此處補跑只覆蓋
     * 與 workflow 對應的 step（其餘透過 /api/scheduler/trigger/... 手動觸發）。
     */
    private String invokeWorkflow(LocalDate date, OrchestrationStep step) {
        return switch (step) {
            case PREMARKET_DATA_PREP, PREMARKET_NOTIFY -> {
                premarketWorkflow.execute(date);
                yield "premarket workflow executed";
            }
            case FINAL_DECISION -> {
                intradayDecisionWorkflow.execute(date);
                yield "intraday decision workflow executed";
            }
            case HOURLY_GATE -> {
                hourlyGateWorkflow.execute(date, LocalTime.now());
                yield "hourly gate workflow executed";
            }
            case POSTMARKET_ANALYSIS -> {
                postmarketWorkflow.execute(date);
                yield "postmarket workflow executed";
            }
            case WATCHLIST_REFRESH -> {
                watchlistWorkflow.execute(date);
                yield "watchlist workflow executed";
            }
            // 無對應 workflow（由 client 直接抓資料的 data-prep job）
            // 呼叫者應改用 /api/scheduler/trigger/{key} 補跑
            default -> throw new UnsupportedOperationException(
                    "No workflow mapping for step " + step + "; use /api/scheduler/trigger/... instead.");
        };
    }
}
