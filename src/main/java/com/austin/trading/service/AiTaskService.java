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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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
    // 短 hash 長度：16 chars（約 64 bit），idempotency 用足夠。
    // merge 後格式「claude:xxxxxxxxxxxxxxxx,codex:yyyyyyyyyyyyyyyy」= 45 chars，遠小於 DB column。
    private static final int RESULT_HASH_SHORT_LEN = 16;
    private static final int RESULT_HASH_MAX_LEN = 64;

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
            // v2.2：遇 FAILED/EXPIRED 時重置狀態讓 AI 可重試，避免 UNIQUE (trading_date,task_type,target_symbol) 衝突。
            //   若 Claude 已完成 → 回到 CLAUDE_DONE（Codex 可直接接手，Claude 不重跑）
            //   否則 → 回到 PENDING（整個流程重新走）
            if (STATUS_FAILED.equals(task.getStatus()) || STATUS_EXPIRED.equals(task.getStatus())) {
                String prev = task.getStatus();
                if (task.getClaudeDoneAt() != null) {
                    task.setStatus(STATUS_CLAUDE_DONE);
                    task.setCodexStartedAt(null);
                    task.setCodexDoneAt(null);
                    task.setCodexResultMarkdown(null);
                    task.setCodexScoresJson(null);
                    task.setCodexVetoSymbolsJson(null);
                } else {
                    task.setStatus(STATUS_PENDING);
                    task.setClaudeStartedAt(null);
                }
                task.setErrorMessage(null);
                recordTransition(task, "recreate: reset from " + prev);
            }
            // UPSERT：更新候選 + prompt，但若狀態不在 PENDING/CLAUDE_DONE 就不動
            task.setTargetCandidatesJson(toJson(candidates));
            if (promptSummary != null)  task.setPromptSummary(truncate(promptSummary, 2000));
            if (promptFilePath != null) task.setPromptFilePath(promptFilePath);
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

    /**
     * 顯式認領指定 id 的任務（Claude）。
     * 只允許 {@code PENDING → CLAUDE_RUNNING}，其他狀態回 {@link AiTaskInvalidStateException}。
     */
    @Transactional
    public AiTaskEntity claim(Long taskId) {
        return claimClaude(taskId);
    }

    /** v2.1：明確的 claim-claude 方法。 */
    @Transactional
    public AiTaskEntity claimClaude(Long taskId) {
        AiTaskEntity task = requireTask(taskId);
        requireState(task, Set.of(STATUS_PENDING), "claim-claude");
        task.setStatus(STATUS_CLAUDE_RUNNING);
        task.setClaudeStartedAt(LocalDateTime.now());
        recordTransition(task, "claim-claude");
        return aiTaskRepository.save(task);
    }

    /**
     * v2.1：Codex 顯式認領，{@code CLAUDE_DONE → CODEX_RUNNING}。
     */
    @Transactional
    public AiTaskEntity claimCodex(Long taskId) {
        AiTaskEntity task = requireTask(taskId);
        requireState(task, Set.of(STATUS_CLAUDE_DONE), "claim-codex");
        task.setStatus(STATUS_CODEX_RUNNING);
        task.setCodexStartedAt(LocalDateTime.now());
        recordTransition(task, "claim-codex");
        AiTaskEntity saved = aiTaskRepository.save(task);
        log.info("[AiTaskService] CLAIM Codex id={} type={} → CODEX_RUNNING",
                saved.getId(), saved.getTaskType());
        return saved;
    }

    // ── 提交結果 ────────────────────────────────────────────────────────────

    /**
     * Claude 提交研究結果：儲存 markdown + scoresJson，並自動回寫 stock_evaluation.claude_score。
     * <p>v2.1：嚴格狀態機，只允許 {@code PENDING / CLAUDE_RUNNING}；支援 idempotent 重送。</p>
     *
     * @return 提交結果，含 task 與 {@code idempotent} 旗標
     */
    @Transactional
    public SubmitResult submitClaudeResult(Long taskId, ClaudeSubmitRequest req) {
        AiTaskEntity task = requireTask(taskId);

        // 計算 request hash（用於 idempotent check）
        String hash = computeClaudeHash(req);

        // Idempotent：同 task、狀態已 ≥ CLAUDE_DONE、且 hash 相同 → 直接回 OK
        if (STATUS_CLAUDE_DONE.equals(task.getStatus()) && hashMatches(task.getResultHash(), hash, "claude")) {
            log.info("[AiTaskService] Idempotent SUBMIT Claude id={} (same hash)", taskId);
            return new SubmitResult(task, true);
        }

        // 狀態機：只允許 PENDING / CLAUDE_RUNNING
        requireState(task, Set.of(STATUS_PENDING, STATUS_CLAUDE_RUNNING), "claude-result");

        task.setClaudeResultMarkdown(req.contentMarkdown());
        task.setClaudeScoresJson(toJson(req.scores() == null ? Collections.emptyMap() : req.scores()));
        task.setClaudeDoneAt(LocalDateTime.now());
        task.setStatus(STATUS_CLAUDE_DONE);
        task.setResultHash(mergeHash(task.getResultHash(), "claude", hash));
        recordTransition(task, "claude-result");

        // 自動解析分數 → 寫入 stock_evaluation
        autoWriteClaudeScores(task.getTradingDate(), req);

        // 舊 API 相容：同步寫一筆 ai_research_log
        writeResearchLog(task, req.contentMarkdown(), researchTypeOf(task));

        AiTaskEntity saved = aiTaskRepository.save(task);
        log.info("[AiTaskService] SUBMIT Claude id={} type={} scores={} → CLAUDE_DONE",
                saved.getId(), saved.getTaskType(),
                req.scores() == null ? 0 : req.scores().size());
        return new SubmitResult(saved, false);
    }

    /**
     * Codex 提交審核結果：儲存 markdown + scoresJson + vetoSymbolsJson，
     * 並自動回寫 stock_evaluation.codex_score / codex_review_issues。
     * <p>v2.1：嚴格狀態機，只允許 {@code CLAUDE_DONE / CODEX_RUNNING}；支援 idempotent 重送。</p>
     */
    @Transactional
    public SubmitResult submitCodexResult(Long taskId, CodexSubmitRequest req) {
        AiTaskEntity task = requireTask(taskId);

        String hash = computeCodexHash(req);

        if (STATUS_CODEX_DONE.equals(task.getStatus()) && hashMatches(task.getResultHash(), hash, "codex")) {
            log.info("[AiTaskService] Idempotent SUBMIT Codex id={} (same hash)", taskId);
            return new SubmitResult(task, true);
        }

        requireState(task, Set.of(STATUS_CLAUDE_DONE, STATUS_CODEX_RUNNING), "codex-result");

        task.setCodexResultMarkdown(req.contentMarkdown());
        task.setCodexScoresJson(toJson(req.scores() == null ? Collections.emptyMap() : req.scores()));
        task.setCodexVetoSymbolsJson(toJson(req.vetoSymbols() == null ? List.of() : req.vetoSymbols()));
        task.setCodexDoneAt(LocalDateTime.now());
        task.setStatus(STATUS_CODEX_DONE);
        task.setResultHash(mergeHash(task.getResultHash(), "codex", hash));
        recordTransition(task, "codex-result");

        autoWriteCodexScores(task.getTradingDate(), req);

        writeResearchLog(task, req.contentMarkdown(), researchTypeOf(task) + "_CODEX");

        AiTaskEntity saved = aiTaskRepository.save(task);
        log.info("[AiTaskService] SUBMIT Codex id={} type={} scores={} veto={} → CODEX_DONE",
                saved.getId(), saved.getTaskType(),
                req.scores()      == null ? 0 : req.scores().size(),
                req.vetoSymbols() == null ? 0 : req.vetoSymbols().size());
        return new SubmitResult(saved, false);
    }

    /**
     * v2.1：只允許 {@code CODEX_DONE → FINALIZED}。
     */
    @Transactional
    public AiTaskEntity finalizeTask(Long taskId) {
        return finalizeTask(taskId, "finalize");
    }

    /**
     * v2.2: 允許指定 reason（例如 "final-decision-consumed"、"catchup-sweep"）便於追蹤。
     */
    @Transactional
    public AiTaskEntity finalizeTask(Long taskId, String reason) {
        AiTaskEntity task = requireTask(taskId);
        if (STATUS_FINALIZED.equals(task.getStatus())) {
            // idempotent：已 FINALIZED 直接回
            return task;
        }
        requireState(task, Set.of(STATUS_CODEX_DONE), "finalize");
        task.setStatus(STATUS_FINALIZED);
        task.setFinalizedAt(LocalDateTime.now());
        recordTransition(task, reason == null || reason.isBlank() ? "finalize" : reason);
        return aiTaskRepository.save(task);
    }

    /**
     * v2.1：只允許 {@code PENDING / CLAUDE_RUNNING / CODEX_RUNNING} 才可標記 FAILED。
     * 已 terminal（FINALIZED/FAILED/EXPIRED）不得改回 running。
     */
    @Transactional
    public AiTaskEntity failTask(Long taskId, String errorMessage) {
        AiTaskEntity task = requireTask(taskId);
        if (STATUS_FAILED.equals(task.getStatus())) return task;
        String prev = task.getStatus();
        requireState(task, Set.of(STATUS_PENDING, STATUS_CLAUDE_RUNNING, STATUS_CODEX_RUNNING), "fail");
        task.setStatus(STATUS_FAILED);
        task.setErrorMessage(truncate(errorMessage, 1000));
        recordTransition(task, "fail: " + truncate(errorMessage, 60));
        AiTaskEntity saved = aiTaskRepository.save(task);
        log.warn("[AiTaskService] FAIL id={} type={} prev={} reason={}",
                saved.getId(), saved.getTaskType(), prev, errorMessage);
        return saved;
    }

    /**
     * v2.1：任何 non-terminal 狀態可以 {@code → EXPIRED}（通常由 timeout sweep 呼叫）。
     */
    @Transactional
    public AiTaskEntity expireTask(Long taskId, String reason) {
        AiTaskEntity task = requireTask(taskId);
        if (STATUS_EXPIRED.equals(task.getStatus())) return task;
        // terminal 不得 expire
        if (STATUS_FINALIZED.equals(task.getStatus()) || STATUS_FAILED.equals(task.getStatus())) {
            throw new AiTaskInvalidStateException(
                    AiTaskErrorCode.AI_TASK_INVALID_STATE,
                    taskId, task.getStatus(),
                    List.of(STATUS_PENDING, STATUS_CLAUDE_RUNNING, STATUS_CLAUDE_DONE,
                            STATUS_CODEX_RUNNING, STATUS_CODEX_DONE),
                    "Cannot expire task in terminal status=" + task.getStatus());
        }
        task.setStatus(STATUS_EXPIRED);
        task.setErrorMessage(truncate(reason, 1000));
        recordTransition(task, "expire: " + truncate(reason, 60));
        return aiTaskRepository.save(task);
    }

    /** Submit 結果 wrapper，含 idempotent 旗標 */
    public record SubmitResult(AiTaskEntity task, boolean idempotent) {}

    // ── 狀態機 helper ──────────────────────────────────────────────────────

    private AiTaskEntity requireTask(Long taskId) {
        return aiTaskRepository.findById(taskId)
                .orElseThrow(() -> new AiTaskInvalidStateException(
                        AiTaskErrorCode.TASK_NOT_FOUND, taskId, null, List.of(),
                        "Task not found: " + taskId));
    }

    private void requireState(AiTaskEntity task, Set<String> allowed, String action) {
        if (!allowed.contains(task.getStatus())) {
            throw new AiTaskInvalidStateException(
                    terminalCode(task.getStatus()),
                    task.getId(),
                    task.getStatus(),
                    List.copyOf(allowed),
                    "Cannot " + action + " when task status is " + task.getStatus());
        }
    }

    private AiTaskErrorCode terminalCode(String currentStatus) {
        if (STATUS_EXPIRED.equals(currentStatus)) return AiTaskErrorCode.TASK_EXPIRED;
        // CODEX_DONE / FINALIZED 收到 Claude result 等，視為已前進
        if (STATUS_CODEX_DONE.equals(currentStatus) || STATUS_FINALIZED.equals(currentStatus)) {
            return AiTaskErrorCode.AI_TASK_ALREADY_ADVANCED;
        }
        return AiTaskErrorCode.AI_TASK_INVALID_STATE;
    }

    private void recordTransition(AiTaskEntity task, String reason) {
        task.setLastTransitionAt(LocalDateTime.now());
        task.setLastTransitionReason(truncate(reason, 100));
    }

    // ── Hash helpers：for idempotency ─────────────────────────────────────

    private String computeClaudeHash(ClaudeSubmitRequest req) {
        return shortHash(sha256("claude:"
                + safe(req.contentMarkdown()) + "|"
                + toJson(req.scores()) + "|"
                + toJson(req.thesis()) + "|"
                + toJson(req.riskFlags())));
    }

    private String computeCodexHash(CodexSubmitRequest req) {
        return shortHash(sha256("codex:"
                + safe(req.contentMarkdown()) + "|"
                + toJson(req.scores()) + "|"
                + toJson(req.vetoSymbols()) + "|"
                + toJson(req.reviewIssues())));
    }

    /** result_hash 欄位格式：{@code claude:abc,codex:def}，分別比對 */
    private boolean hashMatches(String stored, String newHash, String provider) {
        if (stored == null || newHash == null) return false;
        for (String part : stored.split(",")) {
            int i = part.indexOf(':');
            if (i > 0 && provider.equals(part.substring(0, i))) {
                String oldHash = part.substring(i + 1);
                // 與舊資料相容：允許 full-hash 與 short-hash 互比
                if (newHash.equals(oldHash) || oldHash.startsWith(newHash) || newHash.startsWith(oldHash)) {
                    return true;
                }
            }
        }
        return false;
    }

    private String mergeHash(String stored, String provider, String newHash) {
        String prefix = provider + ":" + shortHash(newHash);
        if (stored == null || stored.isBlank()) return prefix;
        // 移除舊的同 provider 記錄後附加新的
        StringBuilder sb = new StringBuilder();
        for (String part : stored.split(",")) {
            int i = part.indexOf(':');
            if (i > 0 && !provider.equals(part.substring(0, i))) {
                if (sb.length() > 0) sb.append(',');
                sb.append(part);
            }
        }
        if (sb.length() > 0) sb.append(',');
        sb.append(prefix);
        String merged = sb.toString();
        return merged.length() <= RESULT_HASH_MAX_LEN
                ? merged
                : merged.substring(0, RESULT_HASH_MAX_LEN);
    }

    private String shortHash(String hash) {
        if (hash == null) return null;
        return hash.length() <= RESULT_HASH_SHORT_LEN ? hash : hash.substring(0, RESULT_HASH_SHORT_LEN);
    }

    private String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bytes = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return String.valueOf(input.hashCode());
        }
    }

    private String safe(String s) { return s == null ? "" : s; }

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
            String trimmed = symbol.trim();
            if (trimmed.isEmpty() || trimmed.length() > 20 || trimmed.contains(" ")) {
                log.warn("[AiTaskService] skip invalid Claude score key (malformed symbol): '{}'", symbol);
                continue;
            }
            try {
                AiScoreUpdateRequest update = new AiScoreUpdateRequest(
                        date,
                        score,
                        null,                 // confidence: 未提供
                        thesis.get(trimmed),
                        riskFlags.isEmpty() ? null : new ArrayList<>(riskFlags),
                        null, null, null
                );
                stockEvaluationService.updateAiScores(trimmed, update);
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
            // 防禦：symbol 必須是單一股票代號（<=20 字元、不含空白）。
            // Codex 若 scores 格式錯誤將多個 symbol 串成一個 key，這邊直接跳過避免炸 DB。
            String trimmed = symbol.trim();
            if (trimmed.isEmpty() || trimmed.length() > 20 || trimmed.contains(" ")) {
                log.warn("[AiTaskService] skip invalid Codex score key (malformed symbol): '{}'", symbol);
                continue;
            }
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
                stockEvaluationService.updateAiScores(trimmed, update);
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
