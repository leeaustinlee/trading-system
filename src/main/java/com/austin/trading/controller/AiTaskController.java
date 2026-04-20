package com.austin.trading.controller;

import com.austin.trading.dto.request.AiTaskCreateRequest;
import com.austin.trading.dto.request.ClaudeSubmitRequest;
import com.austin.trading.dto.request.CodexSubmitRequest;
import com.austin.trading.entity.AiTaskEntity;
import com.austin.trading.service.AiTaskInvalidStateException;
import com.austin.trading.service.AiTaskService;
import com.austin.trading.service.AiTaskService.SubmitResult;
import com.austin.trading.service.FinalDecisionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * AI 任務佇列 API（v2.1 嚴格狀態機）。
 *
 * <ul>
 *   <li>GET  /api/ai/tasks                今日所有任務</li>
 *   <li>GET  /api/ai/tasks/{id}           單筆詳細</li>
 *   <li>GET  /api/ai/tasks/date/{date}    指定日期</li>
 *   <li>GET  /api/ai/tasks/pending        PENDING 任務（認領用）</li>
 *   <li>POST /api/ai/tasks                手動建任務</li>
 *   <li>POST /api/ai/tasks/{id}/claim     legacy = claim-claude（PENDING → CLAUDE_RUNNING）</li>
 *   <li>POST /api/ai/tasks/{id}/claim-claude  PENDING → CLAUDE_RUNNING</li>
 *   <li>POST /api/ai/tasks/{id}/claim-codex   CLAUDE_DONE → CODEX_RUNNING</li>
 *   <li>POST /api/ai/tasks/{id}/claude-result Claude 提交（PENDING / CLAUDE_RUNNING）</li>
 *   <li>POST /api/ai/tasks/{id}/codex-result  Codex 提交（CLAUDE_DONE / CODEX_RUNNING）</li>
 *   <li>POST /api/ai/tasks/{id}/finalize  標記 FINALIZED（CODEX_DONE 才能）</li>
 *   <li>POST /api/ai/tasks/{id}/fail      標記 FAILED（non-terminal 才能）</li>
 *   <li>POST /api/ai/tasks/{id}/expire    標記 EXPIRED（non-terminal 才能）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/ai/tasks")
public class AiTaskController {

    private static final Logger log = LoggerFactory.getLogger(AiTaskController.class);

    private final AiTaskService aiTaskService;
    private final FinalDecisionService finalDecisionService;

    public AiTaskController(AiTaskService aiTaskService,
                             FinalDecisionService finalDecisionService) {
        this.aiTaskService = aiTaskService;
        this.finalDecisionService = finalDecisionService;
    }

    @GetMapping
    public List<Map<String, Object>> getToday() {
        return aiTaskService.getToday().stream().map(this::toResponse).toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getById(@PathVariable Long id) {
        return aiTaskService.getById(id)
                .map(t -> ResponseEntity.ok(toResponse(t)))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/date/{date}")
    public List<Map<String, Object>> getByDate(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        return aiTaskService.getByDate(date).stream().map(this::toResponse).toList();
    }

    @GetMapping("/pending")
    public List<Map<String, Object>> getPending(@RequestParam(required = false) String type) {
        String normalized = type == null || type.isBlank() ? null : type.toUpperCase();
        return aiTaskService.getPending(normalized).stream().map(this::toResponse).toList();
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody AiTaskCreateRequest req) {
        LocalDate date = req.tradingDate() != null ? req.tradingDate() : LocalDate.now();
        if (req.taskType() == null || req.taskType().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "taskType is required"));
        }
        try {
            AiTaskEntity task = aiTaskService.createTask(
                    date,
                    req.taskType().toUpperCase(),
                    req.targetSymbol() == null || req.targetSymbol().isBlank() ? null : req.targetSymbol(),
                    req.candidates(),
                    req.promptSummary(),
                    req.promptFilePath()
            );
            return ResponseEntity.ok(toResponse(task));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("success", false,
                    "error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    @PostMapping("/{id}/claim")
    public ResponseEntity<Map<String, Object>> claim(@PathVariable Long id) {
        return claimClaude(id);
    }

    @PostMapping("/{id}/claim-claude")
    public ResponseEntity<Map<String, Object>> claimClaude(@PathVariable Long id) {
        try {
            AiTaskEntity task = aiTaskService.claimClaude(id);
            return ResponseEntity.ok(toResponse(task));
        } catch (AiTaskInvalidStateException e) {
            return invalidState(e);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    @PostMapping("/{id}/claim-codex")
    public ResponseEntity<Map<String, Object>> claimCodex(@PathVariable Long id) {
        try {
            AiTaskEntity task = aiTaskService.claimCodex(id);
            return ResponseEntity.ok(toResponse(task));
        } catch (AiTaskInvalidStateException e) {
            return invalidState(e);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    @PostMapping("/{id}/claude-result")
    public ResponseEntity<Map<String, Object>> submitClaude(
            @PathVariable Long id,
            @RequestBody ClaudeSubmitRequest req
    ) {
        try {
            SubmitResult result = aiTaskService.submitClaudeResult(id, req);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", true);
            body.put("idempotent", result.idempotent());
            body.put("id", result.task().getId());
            body.put("status", result.task().getStatus());
            body.put("autoScored", req.scores() == null ? Map.of() : req.scores());
            return ResponseEntity.ok(body);
        } catch (AiTaskInvalidStateException e) {
            return invalidState(e);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    @PostMapping("/{id}/codex-result")
    public ResponseEntity<Map<String, Object>> submitCodex(
            @PathVariable Long id,
            @RequestBody CodexSubmitRequest req
    ) {
        // P0-5: scores key 必須是單一台股代號（3-6 位英數，不含空白），不合法直接 400。
        String invalidKey = findInvalidScoreKey(req.scores());
        if (invalidKey != null) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("success", false);
            err.put("errorCode", "INVALID_SCORE_SYMBOL_KEY");
            err.put("invalidKey", invalidKey);
            err.put("message", "Codex scores key 必須是單一股票代號（例如 \"2303\"），不可為空白分隔的多 symbol 串接。");
            err.put("exampleCorrect", Map.of("2303", 7.0, "5469", 6.5));
            return ResponseEntity.badRequest().body(err);
        }
        try {
            SubmitResult result = aiTaskService.submitCodexResult(id, req);
            AiTaskEntity task = result.task();

            // P0-2 catch-up: Codex 晚到時，自動跑對應 taskType 的 FinalDecision 消化 + finalize。
            // 只對 CODEX_DONE 且非 idempotent 的新提交觸發；失敗不影響 submit 結果。
            boolean catchUpTriggered = false;
            String catchUpResult = null;
            if (!result.idempotent() && "CODEX_DONE".equals(task.getStatus())) {
                try {
                    var decision = finalDecisionService.evaluateAndPersist(
                            task.getTradingDate(), task.getTaskType());
                    catchUpTriggered = true;
                    catchUpResult = decision.decision();
                    log.info("[AiTaskController] catch-up FinalDecision for task {} ({}): {}",
                            task.getId(), task.getTaskType(), catchUpResult);
                } catch (Exception ex) {
                    log.warn("[AiTaskController] catch-up FinalDecision failed for task {}: {}",
                            task.getId(), ex.getMessage());
                }
            }

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", true);
            body.put("idempotent", result.idempotent());
            body.put("id", task.getId());
            // 重撈一次確保拿到 catch-up 後的狀態（可能已 FINALIZED）
            String finalStatus = aiTaskService.getById(task.getId())
                    .map(AiTaskEntity::getStatus).orElse(task.getStatus());
            body.put("status", finalStatus);
            body.put("autoScored",  req.scores()      == null ? Map.of() : req.scores());
            body.put("vetoSymbols", req.vetoSymbols() == null ? List.of() : req.vetoSymbols());
            body.put("catchUpTriggered", catchUpTriggered);
            if (catchUpResult != null) body.put("catchUpDecision", catchUpResult);
            return ResponseEntity.ok(body);
        } catch (AiTaskInvalidStateException e) {
            return invalidState(e);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    // ── 私有 helpers ───────────────────────────────────────────────────────────

    /** 台股單一股票代號：3-6 位英數（含 ETF 如 00631L / 權證 03xxxx）。不接受空白或多個 symbol 串接。 */
    private static final Pattern SYMBOL_PATTERN = Pattern.compile("^[0-9A-Z]{3,6}$");

    private String findInvalidScoreKey(Map<String, BigDecimal> scores) {
        if (scores == null || scores.isEmpty()) return null;
        for (String key : scores.keySet()) {
            if (key == null) return "(null)";
            String trimmed = key.trim();
            if (!SYMBOL_PATTERN.matcher(trimmed).matches() || !trimmed.equals(key)) {
                return key;
            }
        }
        return null;
    }

    @PostMapping("/{id}/finalize")
    public ResponseEntity<Map<String, Object>> finalizeTask(@PathVariable Long id) {
        try {
            AiTaskEntity task = aiTaskService.finalizeTask(id);
            return ResponseEntity.ok(toResponse(task));
        } catch (AiTaskInvalidStateException e) {
            return invalidState(e);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    @PostMapping("/{id}/fail")
    public ResponseEntity<Map<String, Object>> fail(
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, String> body
    ) {
        String err = body == null ? null : body.getOrDefault("error", null);
        try {
            AiTaskEntity task = aiTaskService.failTask(id, err);
            return ResponseEntity.ok(toResponse(task));
        } catch (AiTaskInvalidStateException e) {
            return invalidState(e);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    @PostMapping("/{id}/expire")
    public ResponseEntity<Map<String, Object>> expire(
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, String> body
    ) {
        String reason = body == null ? "manual" : body.getOrDefault("reason", "manual");
        try {
            AiTaskEntity task = aiTaskService.expireTask(id, reason);
            return ResponseEntity.ok(toResponse(task));
        } catch (AiTaskInvalidStateException e) {
            return invalidState(e);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    // ── 內部 ────────────────────────────────────────────────────────────────

    private ResponseEntity<Map<String, Object>> invalidState(AiTaskInvalidStateException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("errorCode", e.getErrorCode().name());
        body.put("message", e.getMessage());
        body.put("taskId", e.getTaskId());
        body.put("currentStatus", e.getCurrentStatus());
        body.put("expectedStatuses", e.getExpectedStatuses());
        // TASK_NOT_FOUND 回 404，其他（狀態機相關）回 409
        HttpStatus status = e.getErrorCode() == com.austin.trading.service.AiTaskErrorCode.TASK_NOT_FOUND
                ? HttpStatus.NOT_FOUND : HttpStatus.CONFLICT;
        return ResponseEntity.status(status).body(body);
    }

    private Map<String, Object> toResponse(AiTaskEntity task) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id",                    task.getId());
        map.put("tradingDate",           task.getTradingDate());
        map.put("taskType",              task.getTaskType());
        map.put("targetSymbol",          task.getTargetSymbol());
        map.put("status",                task.getStatus());
        map.put("promptSummary",         task.getPromptSummary());
        map.put("promptFilePath",        task.getPromptFilePath());
        map.put("targetCandidatesJson",  task.getTargetCandidatesJson());
        map.put("claudeResultMarkdown",  task.getClaudeResultMarkdown());
        map.put("claudeScoresJson",      task.getClaudeScoresJson());
        map.put("codexResultMarkdown",   task.getCodexResultMarkdown());
        map.put("codexScoresJson",       task.getCodexScoresJson());
        map.put("codexVetoSymbolsJson",  task.getCodexVetoSymbolsJson());
        map.put("errorMessage",          task.getErrorMessage());
        map.put("createdAt",             task.getCreatedAt());
        map.put("claudeStartedAt",       task.getClaudeStartedAt());
        map.put("claudeDoneAt",          task.getClaudeDoneAt());
        map.put("codexStartedAt",        task.getCodexStartedAt());
        map.put("codexDoneAt",           task.getCodexDoneAt());
        map.put("finalizedAt",           task.getFinalizedAt());
        map.put("version",               task.getVersion());
        map.put("lastTransitionAt",      task.getLastTransitionAt());
        map.put("lastTransitionReason",  task.getLastTransitionReason());
        return map;
    }
}
