package com.austin.trading.service;

import com.austin.trading.dto.request.AiScoreUpdateRequest;
import com.austin.trading.dto.request.AiTaskCandidateRef;
import com.austin.trading.dto.request.ClaudeSubmitRequest;
import com.austin.trading.dto.request.CodexSubmitRequest;
import com.austin.trading.entity.AiResearchLogEntity;
import com.austin.trading.entity.AiTaskEntity;
import com.austin.trading.repository.AiResearchLogRepository;
import com.austin.trading.repository.AiTaskRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * AI 任務佇列服務（PR-2）。
 * <p>
 * 核心流程：
 * <ol>
 *   <li>Java workflow 於對應時段呼叫 {@link #createTask}，建立 PENDING 任務</li>
 *   <li>Claude / Codex 認領任務（{@link #claimNextPending} 或顯式 claim）</li>
 *   <li>AI 完成後呼叫 {@link #submitClaudeResult} / {@link #submitCodexResult}，
 *       Service 自動解析 scores 並回寫 stock_evaluation（觸發 consensus 重算）</li>
 *   <li>FinalDecisionService 呼叫 {@link #finalizeTask} 標記完成</li>
 * </ol>
 */
@Service
public class AiTaskService {

    private static final Logger log = LoggerFactory.getLogger(AiTaskService.class);

    // ── 狀態常數 ─────────────────────────────────────────────────────────────
    public static final String STATUS_PENDING        = "PENDING";
    public static final String STATUS_CLAUDE_RUNNING = "CLAUDE_RUNNING";
    public static final String STATUS_CLAUDE_DONE    = "CLAUDE_DONE";
    public static final String STATUS_CODEX_RUNNING  = "CODEX_RUNNING";
    public static final String STATUS_CODEX_DONE     = "CODEX_DONE";
    public static final String STATUS_FINALIZED      = "FINALIZED";
    public static final String STATUS_FAILED         = "FAILED";
    public static final String STATUS_EXPIRED        = "EXPIRED";

    private final AiTaskRepository          aiTaskRepository;
    private final AiResearchLogRepository   aiResearchLogRepository;
    private final StockEvaluationService    stockEvaluationService;
    private final ObjectMapper              objectMapper;

    public AiTaskService(
            AiTaskRepository aiTaskRepository,
            AiResearchLogRepository aiResearchLogRepository,
            StockEvaluationService stockEvaluationService,
            ObjectMapper objectMapper
    ) {
        this.aiTaskRepository        = aiTaskRepository;
        this.aiResearchLogRepository = aiResearchLogRepository;
        this.stockEvaluationService  = stockEvaluationService;
        this.objectMapper            = objectMapper;
    }

    // ── 建立 / 認領 ──────────────────────────────────────────────────────────

    /**
     * 建立任務（UPSERT 語意）。
     * <p>
     * 若 (date, type, symbol) 已存在且狀態未 FINALIZED → 更新既有任務的候選與 prompt。
     * 若已 FINALIZED → 新建一筆並警告（保留歷史）。
     * </p>
     */
    @Transactional
    public AiTaskEntity createTask(
            LocalDate tradingDate,
            String taskType,
            String targetSymbol,
            List<AiTaskCandidateRef> candidates,
            String promptSummary,
            String promptFilePath
    ) {
        Optional<AiTaskEntity> existing = (targetSymbol == null || targetSymbol.isBlank())
                ? aiTaskRepository.findByTradingDateAndTaskTypeAndTargetSymbolIsNull(tradingDate, taskType)
                : aiTaskRepository.findByTradingDateAndTaskTypeAndTargetSymbol(tradingDate, taskType, targetSymbol);

        if (existing.isPresent()) {
            AiTaskEntity task = existing.get();
            if (STATUS_FINALIZED.equals(task.getStatus())) {
                log.warn("[AiTaskService] Task already FINALIZED, creating a NEW task (date={}, type={}, symbol={}). " +
                                "Note: UNIQUE constraint may block this — consider expiring old first.",
                        tradingDate, taskType, targetSymbol);
                // Do not create duplicate — return existing to avoid UNIQUE violation.
                return task;
            }
            // UPSERT：更新候選 + prompt，但不重置狀態（除非還是 PENDING）
            task.setTargetCandidatesJson(toJson(candidates));
            if (promptSummary != null)  task.setPromptSummary(truncate(promptSummary, 2000));
            if (promptFilePath != null) task.setPromptFilePath(promptFilePath);
            // 若 task 原本已經進入 CLAUDE_DONE 之類，不要退回 PENDING；保留既有 status。
            AiTaskEntity saved = aiTaskRepository.save(task);
            log.info("[AiTaskService] UPSERT task id={} type={} date={} symbol={} status={}",
                    saved.getId(), taskType, tradingDate, targetSymbol, saved.getStatus());
            return saved;
        }

        AiTaskEntity task = new AiTaskEntity();
        task.setTradingDate(tradingDate);
        task.setTaskType(taskType);
        task.setTargetSymbol(targetSymbol);
        task.setTargetCandidatesJson(toJson(candidates));
        task.setStatus(STATUS_PENDING);
        task.setPromptSummary(truncate(promptSummary, 2000));
        task.setPromptFilePath(promptFilePath);
        AiTaskEntity saved = aiTaskRepository.save(task);
        log.info("[AiTaskService] CREATE task id={} type={} date={} symbol={} candidates={}",
                saved.getId(), taskType, tradingDate, targetSymbol,
                candidates == null ? 0 : candidates.size());
        return saved;
    }

    /** 認領一個 PENDING 任務。原子：PENDING → CLAUDE_RUNNING。 */
    @Transactional
    public Optional<AiTaskEntity> claimNextPending(String taskType) {
        List<AiTaskEntity> pending = taskType == null
                ? aiTaskRepository.findByStatusOrderByCreatedAtAsc(STATUS_PENDING)
                : aiTaskRepository.findByStatusAndTaskTypeOrderByCreatedAtAsc(STATUS_PENDING, taskType);

        if (pending.isEmpty()) return Optional.empty();
        AiTaskEntity task = pending.get(0);
        task.setStatus(STATUS_CLAUDE_RUNNING);
        task.setClaudeStartedAt(LocalDateTime.now());
        AiTaskEntity saved = aiTaskRepository.save(task);
        log.info("[AiTaskService] CLAIM task id={} type={} → CLAUDE_RUNNING", saved.getId(), saved.getTaskType());
        return Optional.of(saved);
    }

    /** 顯式認領指定 id 的任務。 */
    @Transactional
    public AiTaskEntity claim(Long taskId) {
        AiTaskEntity task = aiTaskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
        if (!STATUS_PENDING.equals(task.getStatus())) {
            throw new IllegalStateException("Task " + taskId + " is not PENDING (status=" + task.getStatus() + ")");
        }
        task.setStatus(STATUS_CLAUDE_RUNNING);
        task.setClaudeStartedAt(LocalDateTime.now());
        return aiTaskRepository.save(task);
    }

    // ── 提交結果 ────────────────────────────────────────────────────────────

    /**
     * Claude 提交研究結果：儲存 markdown + scoresJson，並自動回寫 stock_evaluation.claude_score。
     */
    @Transactional
    public AiTaskEntity submitClaudeResult(Long taskId, ClaudeSubmitRequest req) {
        AiTaskEntity task = aiTaskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));

        if (STATUS_FINALIZED.equals(task.getStatus()) || STATUS_EXPIRED.equals(task.getStatus())) {
            throw new IllegalStateException(
                    "Cannot submit Claude result to task " + taskId + " with status=" + task.getStatus());
        }

        task.setClaudeResultMarkdown(req.contentMarkdown());
        task.setClaudeScoresJson(toJson(req.scores() == null ? Collections.emptyMap() : req.scores()));
        task.setClaudeDoneAt(LocalDateTime.now());
        task.setStatus(STATUS_CLAUDE_DONE);

        // 自動解析分數 → 寫入 stock_evaluation
        autoWriteClaudeScores(task.getTradingDate(), req);

        // 舊 API 相容：同步寫一筆 ai_research_log
        writeResearchLog(task, req.contentMarkdown(), researchTypeOf(task));

        AiTaskEntity saved = aiTaskRepository.save(task);
        log.info("[AiTaskService] SUBMIT Claude id={} type={} scores={} → CLAUDE_DONE",
                saved.getId(), saved.getTaskType(),
                req.scores() == null ? 0 : req.scores().size());
        return saved;
    }

    /**
     * Codex 提交審核結果：儲存 markdown + scoresJson + vetoSymbolsJson，
     * 並自動回寫 stock_evaluation.codex_score / codex_review_issues。
     */
    @Transactional
    public AiTaskEntity submitCodexResult(Long taskId, CodexSubmitRequest req) {
        AiTaskEntity task = aiTaskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));

        if (STATUS_FINALIZED.equals(task.getStatus()) || STATUS_EXPIRED.equals(task.getStatus())) {
            throw new IllegalStateException(
                    "Cannot submit Codex result to task " + taskId + " with status=" + task.getStatus());
        }

        task.setCodexResultMarkdown(req.contentMarkdown());
        task.setCodexScoresJson(toJson(req.scores() == null ? Collections.emptyMap() : req.scores()));
        task.setCodexVetoSymbolsJson(toJson(req.vetoSymbols() == null ? List.of() : req.vetoSymbols()));
        task.setCodexDoneAt(LocalDateTime.now());
        task.setStatus(STATUS_CODEX_DONE);

        autoWriteCodexScores(task.getTradingDate(), req);

        writeResearchLog(task, req.contentMarkdown(), researchTypeOf(task) + "_CODEX");

        AiTaskEntity saved = aiTaskRepository.save(task);
        log.info("[AiTaskService] SUBMIT Codex id={} type={} scores={} veto={} → CODEX_DONE",
                saved.getId(), saved.getTaskType(),
                req.scores()      == null ? 0 : req.scores().size(),
                req.vetoSymbols() == null ? 0 : req.vetoSymbols().size());
        return saved;
    }

    @Transactional
    public AiTaskEntity finalizeTask(Long taskId) {
        AiTaskEntity task = aiTaskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
        task.setStatus(STATUS_FINALIZED);
        task.setFinalizedAt(LocalDateTime.now());
        return aiTaskRepository.save(task);
    }

    @Transactional
    public AiTaskEntity failTask(Long taskId, String errorMessage) {
        AiTaskEntity task = aiTaskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
        task.setStatus(STATUS_FAILED);
        task.setErrorMessage(truncate(errorMessage, 1000));
        return aiTaskRepository.save(task);
    }

    // ── 查詢 ────────────────────────────────────────────────────────────────

    /** 該日 PREMARKET/其他 task 是否已至少 CLAUDE_DONE 以上（CLAUDE_DONE / CODEX_* / FINALIZED）。 */
    public boolean isClaudeReady(LocalDate date, String taskType) {
        return aiTaskRepository.findByTradingDateAndTaskType(date, taskType).stream()
                .anyMatch(t -> isAtOrAfter(t.getStatus(), STATUS_CLAUDE_DONE));
    }

    /** 該日 task 是否已至少 CODEX_DONE 以上。 */
    public boolean isCodexReady(LocalDate date, String taskType) {
        return aiTaskRepository.findByTradingDateAndTaskType(date, taskType).stream()
                .anyMatch(t -> isAtOrAfter(t.getStatus(), STATUS_CODEX_DONE));
    }

    public List<AiTaskEntity> getByDate(LocalDate date) {
        return aiTaskRepository.findByTradingDateOrderByCreatedAtDesc(date);
    }

    public List<AiTaskEntity> getToday() {
        return getByDate(LocalDate.now());
    }

    public Optional<AiTaskEntity> getById(Long id) {
        return aiTaskRepository.findById(id);
    }

    public List<AiTaskEntity> getPending(String taskType) {
        return taskType == null
                ? aiTaskRepository.findByStatusOrderByCreatedAtAsc(STATUS_PENDING)
                : aiTaskRepository.findByStatusAndTaskTypeOrderByCreatedAtAsc(STATUS_PENDING, taskType);
    }

    /**
     * 依優先序找當日最新有內容的 AI 研究 markdown（供 LINE 通知補發 AI 摘要用）。
     * 對每個 taskType 優先 Codex，其次 Claude；依參數順序第一個命中的就回傳。
     */
    public String findLatestMarkdown(LocalDate tradingDate, String... taskTypes) {
        List<AiTaskEntity> tasks = aiTaskRepository.findByTradingDateOrderByCreatedAtDesc(tradingDate);
        for (String type : taskTypes) {
            AiTaskEntity task = tasks.stream()
                    .filter(t -> type.equalsIgnoreCase(t.getTaskType()))
                    .findFirst()   // findByTradingDateOrderByCreatedAtDesc 已 desc，取第一筆=最新
                    .orElse(null);
            if (task == null) continue;
            if (task.getCodexResultMarkdown() != null && !task.getCodexResultMarkdown().isBlank()) {
                return "【" + type + " Codex】\n" + task.getCodexResultMarkdown();
            }
            if (task.getClaudeResultMarkdown() != null && !task.getClaudeResultMarkdown().isBlank()) {
                return "【" + type + " Claude】\n" + task.getClaudeResultMarkdown();
            }
        }
        return null;
    }

    // ── 私有：解析 + 回寫 stock_evaluation ────────────────────────────────────

    private void autoWriteClaudeScores(LocalDate date, ClaudeSubmitRequest req) {
        if (req.scores() == null || req.scores().isEmpty()) return;

        Map<String, String> thesis = req.thesis() == null ? Map.of() : req.thesis();
        List<String> riskFlags = req.riskFlags() == null ? List.of() : req.riskFlags();

        for (Map.Entry<String, BigDecimal> e : req.scores().entrySet()) {
            String symbol = e.getKey();
            BigDecimal score = e.getValue();
            if (symbol == null || score == null) continue;
            try {
                AiScoreUpdateRequest update = new AiScoreUpdateRequest(
                        date,
                        score,
                        null,                 // confidence: 未提供
                        thesis.get(symbol),
                        riskFlags.isEmpty() ? null : new ArrayList<>(riskFlags),
                        null, null, null
                );
                stockEvaluationService.updateAiScores(symbol, update);
            } catch (Exception ex) {
                log.warn("[AiTaskService] Failed to write Claude score for symbol={}: {}", symbol, ex.getMessage());
            }
        }
    }

    private void autoWriteCodexScores(LocalDate date, CodexSubmitRequest req) {
        if (req.scores() == null || req.scores().isEmpty()) return;

        Map<String, String> reviewIssues = req.reviewIssues() == null ? Map.of() : req.reviewIssues();

        for (Map.Entry<String, BigDecimal> e : req.scores().entrySet()) {
            String symbol = e.getKey();
            BigDecimal score = e.getValue();
            if (symbol == null || score == null) continue;
            try {
                List<String> issues = null;
                if (reviewIssues.containsKey(symbol)) {
                    issues = List.of(reviewIssues.get(symbol));
                }
                AiScoreUpdateRequest update = new AiScoreUpdateRequest(
                        date,
                        null, null, null, null,
                        score,
                        null,          // confidence: 未提供
                        issues
                );
                stockEvaluationService.updateAiScores(symbol, update);
            } catch (Exception ex) {
                log.warn("[AiTaskService] Failed to write Codex score for symbol={}: {}", symbol, ex.getMessage());
            }
        }
    }

    // ── 私有：AiResearchLog 相容寫入 ──────────────────────────────────────────

    private void writeResearchLog(AiTaskEntity task, String content, String researchType) {
        try {
            AiResearchLogEntity entity = new AiResearchLogEntity();
            entity.setTradingDate(task.getTradingDate());
            entity.setResearchType(researchType);
            entity.setSymbol(task.getTargetSymbol());
            entity.setPromptSummary("(AiTaskService auto import)");
            entity.setResearchResult(content);
            entity.setModel("ai-task-queue");
            entity.setTokensUsed(0);
            aiResearchLogRepository.save(entity);
        } catch (Exception e) {
            log.warn("[AiTaskService] Failed to mirror into ai_research_log: {}", e.getMessage());
        }
    }

    // ── 工具 ─────────────────────────────────────────────────────────────────

    private String researchTypeOf(AiTaskEntity task) {
        // 對應舊 ai_research_log.research_type
        String type = task.getTaskType();
        if (type == null) return "UNKNOWN";
        return type;
    }

    /** status 是否已達或超過 target（定義：PENDING < CLAUDE_RUNNING < CLAUDE_DONE < CODEX_* < FINALIZED）。 */
    private boolean isAtOrAfter(String current, String target) {
        int c = statusOrder(current);
        int t = statusOrder(target);
        return c >= t;
    }

    private int statusOrder(String s) {
        if (s == null) return -1;
        return switch (s) {
            case STATUS_PENDING        -> 0;
            case STATUS_CLAUDE_RUNNING -> 1;
            case STATUS_CLAUDE_DONE    -> 2;
            case STATUS_CODEX_RUNNING  -> 3;
            case STATUS_CODEX_DONE     -> 4;
            case STATUS_FINALIZED      -> 5;
            case STATUS_FAILED         -> -2;  // 不納入就緒判斷
            case STATUS_EXPIRED        -> -2;
            default                    -> -1;
        };
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return "null";
        }
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
