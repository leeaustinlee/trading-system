package com.austin.trading.controller;

import com.austin.trading.dto.response.FinalDecisionRecordResponse;
import com.austin.trading.entity.AiTaskEntity;
import com.austin.trading.entity.DailyOrchestrationStatusEntity;
import com.austin.trading.service.AiTaskService;
import com.austin.trading.service.DailyOrchestrationService;
import com.austin.trading.service.FinalDecisionService;
import com.austin.trading.service.OrchestrationStep;
import com.austin.trading.workflow.HourlyGateWorkflowService;
import com.austin.trading.workflow.IntradayDecisionWorkflowService;
import com.austin.trading.workflow.PostmarketWorkflowService;
import com.austin.trading.workflow.PremarketWorkflowService;
import com.austin.trading.workflow.WatchlistWorkflowService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    private static final List<String> PRIMARY_AI_TASK_ORDER = List.of(
            "PREMARKET", "OPENING", "MIDDAY", "POSTMARKET", "T86_TOMORROW"
    );
    private static final Set<String> PRIMARY_AI_TASK_TYPES = new LinkedHashSet<>(PRIMARY_AI_TASK_ORDER);

    private final DailyOrchestrationService orchestrationService;
    private final PremarketWorkflowService premarketWorkflow;
    private final IntradayDecisionWorkflowService intradayDecisionWorkflow;
    private final HourlyGateWorkflowService hourlyGateWorkflow;
    private final PostmarketWorkflowService postmarketWorkflow;
    private final WatchlistWorkflowService watchlistWorkflow;
    // v2.2 AI orchestration
    private final AiTaskService aiTaskService;
    private final FinalDecisionService finalDecisionService;

    public OrchestrationController(
            DailyOrchestrationService orchestrationService,
            PremarketWorkflowService premarketWorkflow,
            IntradayDecisionWorkflowService intradayDecisionWorkflow,
            HourlyGateWorkflowService hourlyGateWorkflow,
            PostmarketWorkflowService postmarketWorkflow,
            WatchlistWorkflowService watchlistWorkflow,
            AiTaskService aiTaskService,
            FinalDecisionService finalDecisionService
    ) {
        this.orchestrationService = orchestrationService;
        this.premarketWorkflow = premarketWorkflow;
        this.intradayDecisionWorkflow = intradayDecisionWorkflow;
        this.hourlyGateWorkflow = hourlyGateWorkflow;
        this.postmarketWorkflow = postmarketWorkflow;
        this.watchlistWorkflow = watchlistWorkflow;
        this.aiTaskService = aiTaskService;
        this.finalDecisionService = finalDecisionService;
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

    // ═════════════════════════════════════════════════════════════════════
    //  v2.2 AI 任務 orchestration（與 ai_task + final_decision 對應）
    // ═════════════════════════════════════════════════════════════════════

    /** 今日 AI 任務 + 對應 FinalDecision + Dashboard displayStatus/displayMessage */
    @GetMapping("/tasks/today")
    public List<Map<String, Object>> aiTasksToday() {
        LocalDate today = LocalDate.now();
        List<AiTaskEntity> tasks = aiTaskService.getByDate(today).stream()
                .filter(t -> t.getTaskType() != null && PRIMARY_AI_TASK_TYPES.contains(t.getTaskType()))
                .toList();
        List<FinalDecisionRecordResponse> decisions = finalDecisionService.getHistory(100)
                .stream().filter(d -> today.equals(d.tradingDate())).toList();

        Map<String, AiTaskEntity> latestByType = new LinkedHashMap<>();
        for (AiTaskEntity task : tasks) {
            AiTaskEntity current = latestByType.get(task.getTaskType());
            if (current == null || compareAiTaskRecency(task, current) > 0) {
                latestByType.put(task.getTaskType(), task);
            }
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (String taskType : PRIMARY_AI_TASK_ORDER) {
            AiTaskEntity task = latestByType.get(taskType);
            if (task != null) {
                rows.add(toAiOrchestrationRow(task, decisions));
            }
        }
        return rows;
    }

    private int compareAiTaskRecency(AiTaskEntity a, AiTaskEntity b) {
        Comparator<AiTaskEntity> comparator = Comparator
                .comparing(OrchestrationController::aiTaskSortTime,
                        Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(AiTaskEntity::getId, Comparator.nullsFirst(Comparator.naturalOrder()));
        return comparator.compare(a, b);
    }

    private static LocalDateTime aiTaskSortTime(AiTaskEntity task) {
        if (task.getLastTransitionAt() != null) return task.getLastTransitionAt();
        if (task.getFinalizedAt() != null) return task.getFinalizedAt();
        if (task.getCodexDoneAt() != null) return task.getCodexDoneAt();
        if (task.getClaudeDoneAt() != null) return task.getClaudeDoneAt();
        if (task.getCreatedAt() != null) return task.getCreatedAt();
        return null;
    }

    /**
     * P1-7 手動補跑：CODEX_DONE + 無 FinalDecision 的 task 可在此觸發。
     * 錯誤碼：TASK_NOT_FOUND / TASK_ALREADY_FINALIZED / TASK_NOT_CODEX_DONE /
     *        CODEX_RESULT_EMPTY / FINAL_DECISION_FAILED。
     */
    @PostMapping("/tasks/{taskId}/finalize-catchup")
    public ResponseEntity<Map<String, Object>> finalizeCatchup(@PathVariable Long taskId) {
        AiTaskEntity task = aiTaskService.getById(taskId).orElse(null);
        if (task == null) {
            return aiError(HttpStatus.NOT_FOUND, "TASK_NOT_FOUND",
                    "任務不存在：" + taskId, taskId);
        }
        if (AiTaskService.STATUS_FINALIZED.equals(task.getStatus())) {
            Map<String, Object> ok = aiOkBody(task);
            ok.put("idempotent", true);
            ok.put("message", "已 FINALIZED，無需重跑");
            return ResponseEntity.ok(ok);
        }
        if (!AiTaskService.STATUS_CODEX_DONE.equals(task.getStatus())) {
            return aiError(HttpStatus.CONFLICT, "TASK_NOT_CODEX_DONE",
                    "只允許 CODEX_DONE 狀態補跑，目前：" + task.getStatus(), taskId);
        }
        if (task.getCodexResultMarkdown() == null
                || task.getCodexResultMarkdown().trim().length() < 10) {
            return aiError(HttpStatus.CONFLICT, "CODEX_RESULT_EMPTY",
                    "Codex 結果為空或過短，無法補跑", taskId);
        }
        try {
            var decision = finalDecisionService.evaluateAndPersist(
                    task.getTradingDate(), task.getTaskType());
            AiTaskEntity refreshed = aiTaskService.getById(taskId).orElse(task);
            Map<String, Object> ok = aiOkBody(refreshed);
            ok.put("idempotent", false);
            ok.put("decision", decision.decision());
            ok.put("summary", decision.summary());
            return ResponseEntity.ok(ok);
        } catch (Exception e) {
            log.error("[Orchestration] finalize-catchup failed for {}: {}", taskId, e.getMessage(), e);
            return aiError(HttpStatus.INTERNAL_SERVER_ERROR, "FINAL_DECISION_FAILED",
                    e.getMessage(), taskId);
        }
    }

    // ── AI orchestration helpers ──────────────────────────────────────────

    private Map<String, Object> toAiOrchestrationRow(AiTaskEntity t,
                                                      List<FinalDecisionRecordResponse> decisions) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("taskId",               t.getId());
        row.put("taskType",             t.getTaskType());
        row.put("status",               t.getStatus());
        row.put("claudeDoneAt",         t.getClaudeDoneAt());
        row.put("codexDoneAt",          t.getCodexDoneAt());
        row.put("finalizedAt",          t.getFinalizedAt());
        row.put("lastTransitionAt",     t.getLastTransitionAt());
        row.put("lastTransitionReason", t.getLastTransitionReason());
        row.put("errorMessage",         t.getErrorMessage());

        FinalDecisionRecordResponse matched = decisions.stream()
                .filter(d -> t.getId().equals(d.aiTaskId()))
                .findFirst()
                .orElseGet(() -> decisions.stream()
                        .filter(d -> t.getTaskType() != null
                                && t.getTaskType().equalsIgnoreCase(d.sourceTaskType()))
                        .findFirst().orElse(null));
        if (matched != null) {
            row.put("finalDecisionId",     matched.id());
            row.put("finalDecisionStatus", matched.decision());
            row.put("aiStatus",            matched.aiStatus());
            row.put("fallbackReason",      matched.fallbackReason());
            row.put("selectedCount",       countJsonArrayEntries(matched.payloadJson(), "selected_stocks"));
            row.put("rejectedCount",       countJsonArrayEntries(matched.payloadJson(), "rejected_reasons"));
            row.put("summary",             matched.summary());
        } else {
            row.put("finalDecisionId",     null);
            row.put("finalDecisionStatus", null);
            row.put("aiStatus",            null);
            row.put("fallbackReason",      null);
            row.put("selectedCount",       0);
            row.put("rejectedCount",       0);
            row.put("summary",             null);
        }

        String[] disp = computeAiDisplayStatus(t, matched);
        row.put("displayStatus",  disp[0]);
        row.put("displayMessage", disp[1]);
        return row;
    }

    private String[] computeAiDisplayStatus(AiTaskEntity t, FinalDecisionRecordResponse d) {
        String s = t.getStatus();
        if (AiTaskService.STATUS_FINALIZED.equals(s)) {
            return new String[]{ "OK", "最終決策完成" };
        }
        if (AiTaskService.STATUS_CODEX_DONE.equals(s)) {
            if (d == null) {
                return new String[]{ "WARN", "Codex 已完成，等待 FinalDecision 消化" };
            }
            if ("REST".equalsIgnoreCase(d.decision())) {
                return new String[]{ "OK", "已決策：休息 / 無符合標的" };
            }
            if ("WATCH".equalsIgnoreCase(d.decision())) {
                return new String[]{ "OK", "已決策：僅觀察" };
            }
            return new String[]{ "OK", "已決策：" + d.decision() };
        }
        if (AiTaskService.STATUS_CLAUDE_DONE.equals(s)) {
            return new String[]{ "INFO", "Claude 完成，等 Codex" };
        }
        if (AiTaskService.STATUS_FAILED.equals(s) || AiTaskService.STATUS_EXPIRED.equals(s)) {
            return new String[]{ "ERR", t.getErrorMessage() == null
                    ? "任務異常（" + s + "）" : t.getErrorMessage() };
        }
        return new String[]{ "INFO", "進行中（" + s + "）" };
    }

    /** 粗解析 payloadJson 中某陣列欄位的元素數（避免拉入完整 JSON 解析器）。 */
    private int countJsonArrayEntries(String payloadJson, String field) {
        if (payloadJson == null) return 0;
        int i = payloadJson.indexOf("\"" + field + "\"");
        if (i < 0) return 0;
        int open = payloadJson.indexOf('[', i);
        int close = payloadJson.indexOf(']', open);
        if (open < 0 || close < 0 || close <= open + 1) return 0;
        String body = payloadJson.substring(open + 1, close).trim();
        if (body.isEmpty()) return 0;
        long commas = body.chars().filter(ch -> ch == ',').count();
        return (int) (commas + 1);
    }

    private Map<String, Object> aiOkBody(AiTaskEntity task) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", true);
        m.put("taskId", task.getId());
        m.put("taskType", task.getTaskType());
        m.put("status", task.getStatus());
        m.put("finalizedAt", task.getFinalizedAt());
        return m;
    }

    private ResponseEntity<Map<String, Object>> aiError(HttpStatus status, String code,
                                                          String message, Long taskId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("errorCode", code);
        body.put("message", message);
        body.put("taskId", taskId);
        body.put("timestamp", LocalDateTime.now());
        return ResponseEntity.status(status).body(body);
    }
}
