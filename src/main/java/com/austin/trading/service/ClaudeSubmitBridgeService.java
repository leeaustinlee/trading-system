package com.austin.trading.service;

import com.austin.trading.dto.request.ClaudeSubmitFileRequest;
import com.austin.trading.dto.request.ClaudeSubmitRequest;
import com.austin.trading.entity.AiTaskEntity;
import com.austin.trading.entity.FileBridgeErrorLogEntity;
import com.austin.trading.notify.LineTemplateService;
import com.austin.trading.repository.FileBridgeErrorLogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Claude File Bridge 處理核心（v2.1）。
 *
 * <p>寫檔協定：Claude 先寫 {@code *.tmp}，完成後 rename 成 {@code *.json}；
 * Java watcher 只讀 {@code .json}。</p>
 *
 * <p>結果目錄：</p>
 * <ul>
 *     <li>成功 → {@code processed/}</li>
 *     <li>解析失敗 / 狀態非法 → {@code failed/} + {@code file_bridge_error_log}</li>
 *     <li>可重試錯誤 → {@code retry/}</li>
 * </ul>
 *
 * <p>正式規格下，task 必須由 Java DataPrep 預先建立；
 * 若 {@code ai.file_bridge.allow_auto_create_task=true} 才允許 bridge auto create（開發/應急用）。</p>
 */
@Service
public class ClaudeSubmitBridgeService {

    private static final Logger log = LoggerFactory.getLogger(ClaudeSubmitBridgeService.class);

    private final AiTaskService aiTaskService;
    private final FileBridgeErrorLogRepository errorLogRepository;
    private final LineTemplateService lineTemplateService;
    private final ObjectMapper objectMapper;

    private final boolean allowAutoCreateTask;
    private final Path processedDir;
    private final Path failedDir;
    private final Path retryDir;

    public ClaudeSubmitBridgeService(
            AiTaskService aiTaskService,
            FileBridgeErrorLogRepository errorLogRepository,
            LineTemplateService lineTemplateService,
            @Value("${ai.file_bridge.allow_auto_create_task:false}") boolean allowAutoCreateTask,
            @Value("${ai.file_bridge.processed_dir:${trading.claude-submit.watch-dir:/mnt/d/ai/stock/claude-submit}/processed}") String processedDir,
            @Value("${ai.file_bridge.failed_dir:${trading.claude-submit.watch-dir:/mnt/d/ai/stock/claude-submit}/failed}") String failedDir,
            @Value("${ai.file_bridge.retry_dir:${trading.claude-submit.watch-dir:/mnt/d/ai/stock/claude-submit}/retry}") String retryDir
    ) {
        this.aiTaskService = aiTaskService;
        this.errorLogRepository = errorLogRepository;
        this.lineTemplateService = lineTemplateService;
        this.objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        this.allowAutoCreateTask = allowAutoCreateTask;
        this.processedDir = Path.of(processedDir);
        this.failedDir = Path.of(failedDir);
        this.retryDir = Path.of(retryDir);
        ensureDirs();
    }

    private void ensureDirs() {
        for (Path p : List.of(processedDir, failedDir, retryDir)) {
            try { if (!Files.exists(p)) Files.createDirectories(p); }
            catch (IOException e) { log.warn("[ClaudeSubmitBridge] 無法建立 {}: {}", p, e.getMessage()); }
        }
    }

    /**
     * 處理單一 JSON 檔案。成功/失敗/重試皆 rename 並搬到對應目錄，不拋 exception。
     *
     * <p>Watcher 會先 rename 成 {@code *.processing} 再呼叫本方法，確保單一 worker 處理。</p>
     */
    public void processFile(Path file) {
        String originalName = file.getFileName().toString();
        String displayName = originalName.endsWith(".processing")
                ? originalName.substring(0, originalName.length() - ".processing".length())
                : originalName;

        ClaudeSubmitFileRequest req;
        String fileHash;
        try {
            String json = Files.readString(file);
            fileHash = sha256(json);
            req = objectMapper.readValue(json, ClaudeSubmitFileRequest.class);
        } catch (Exception e) {
            logError(displayName, null, null, null,
                    AiTaskErrorCode.FILE_BRIDGE_PARSE_ERROR.name(),
                    "JSON 解析失敗: " + e.getMessage(), null);
            moveToFailed(file, displayName, e);
            sendSystemAlert(displayName, "FILE_BRIDGE_PARSE_ERROR", e.getMessage());
            return;
        }

        // Hash idempotent：同一檔已成功處理過
        if (errorLogRepository.existsByFileHash(fileHash)) {
            log.info("[ClaudeSubmitBridge] skip duplicate file hash {} ({})", fileHash.substring(0, 12), displayName);
        }

        Long taskId = resolveTaskId(req);
        if (taskId == null) {
            if (allowAutoCreateTask && req.taskType() != null && !req.taskType().isBlank()) {
                LocalDate date = Optional.ofNullable(req.tradingDate()).orElse(LocalDate.now());
                AiTaskEntity newTask = aiTaskService.createTask(
                        date, req.taskType().toUpperCase(), null, List.of(),
                        "Auto-created by ClaudeSubmitBridge (emergency fallback)",
                        "claude-submit:" + displayName
                );
                taskId = newTask.getId();
                logError(displayName, taskId, req.taskType(), date,
                        AiTaskErrorCode.TASK_AUTO_CREATED_BY_BRIDGE.name(),
                        "Emergency fallback: auto-created task", fileHash);
                log.warn("[ClaudeSubmitBridge] ⚠ AUTO_CREATED task id={} type={}（allow_auto_create_task=true 時才會出現）",
                        taskId, req.taskType());
            } else {
                logError(displayName, null, req.taskType(), req.tradingDate(),
                        AiTaskErrorCode.TASK_NOT_FOUND.name(),
                        "找不到對應 task（請確認 Java DataPrep 已建 task）", fileHash);
                moveToFailed(file, displayName, new IllegalStateException("TASK_NOT_FOUND"));
                sendSystemAlert(displayName, "TASK_NOT_FOUND",
                        "taskType=" + req.taskType() + " date=" + req.tradingDate());
                return;
            }
        }

        final Long resolvedTaskId = taskId;
        try {
            AiTaskEntity task = aiTaskService.getById(resolvedTaskId)
                    .orElseThrow(() -> new IllegalStateException("Task not found: " + resolvedTaskId));

            // PENDING → claim 變 CLAUDE_RUNNING（允許同一 hash idempotent submit，狀態機層處理）
            if (AiTaskService.STATUS_PENDING.equalsIgnoreCase(task.getStatus())) {
                aiTaskService.claimClaude(resolvedTaskId);
            }

            ClaudeSubmitRequest submitReq = new ClaudeSubmitRequest(
                    req.contentMarkdown(),
                    req.scores(),
                    req.thesis(),
                    req.riskFlags()
            );
            AiTaskService.SubmitResult result = aiTaskService.submitClaudeResult(resolvedTaskId, submitReq);

            moveToProcessed(file, displayName);
            log.info("[ClaudeSubmitBridge] ✅ {} → task {} ({}) status={} idempotent={} scores={}",
                    displayName, resolvedTaskId, result.task().getTaskType(),
                    result.task().getStatus(), result.idempotent(),
                    req.scores() == null ? 0 : req.scores().size());

        } catch (AiTaskInvalidStateException e) {
            logError(displayName, resolvedTaskId, req.taskType(), req.tradingDate(),
                    e.getErrorCode().name(), e.getMessage(), fileHash);
            moveToFailed(file, displayName, e);
            sendSystemAlert(displayName, e.getErrorCode().name(), e.getMessage());
        } catch (Exception e) {
            log.error("[ClaudeSubmitBridge] ❌ {}: {}", displayName, e.getMessage(), e);
            logError(displayName, resolvedTaskId, req.taskType(), req.tradingDate(),
                    "UNEXPECTED_ERROR", e.getMessage(), fileHash);
            moveToRetry(file, displayName, e);
            sendSystemAlert(displayName, "RETRY", e.getMessage());
        }
    }

    /**
     * 找對應 task（v2.1）：優先用 taskId；否則用 taskType+tradingDate 找最新「非終結」task。
     * 不再用 reduce 取舊 task，而是用 orderByCreatedAtDesc 取第一筆。
     */
    private Long resolveTaskId(ClaudeSubmitFileRequest req) {
        if (req.taskId() != null) return req.taskId();
        if (req.taskType() == null || req.taskType().isBlank()) return null;

        LocalDate date = Optional.ofNullable(req.tradingDate()).orElse(LocalDate.now());
        // getByDate 已 order by createdAt desc，取第一筆非 terminal 的
        return aiTaskService.getByDate(date).stream()
                .filter(t -> req.taskType().equalsIgnoreCase(t.getTaskType()))
                .filter(t -> !AiTaskService.STATUS_FINALIZED.equalsIgnoreCase(t.getStatus())
                          && !AiTaskService.STATUS_EXPIRED.equalsIgnoreCase(t.getStatus())
                          && !AiTaskService.STATUS_FAILED.equalsIgnoreCase(t.getStatus()))
                .findFirst()
                .map(AiTaskEntity::getId)
                .orElse(null);
    }

    // ── 檔案操作 ─────────────────────────────────────────────────────────

    private void moveToProcessed(Path file, String displayName) {
        move(file, processedDir.resolve(displayName.replace(".json", ".processed.json")));
    }

    private void moveToFailed(Path file, String displayName, Exception e) {
        Path target = failedDir.resolve(displayName.replace(".json", ".failed.json"));
        try {
            if (Files.exists(file)) {
                String original = Files.readString(file);
                String appended = original + "\n\n__ERROR__ " + e.getClass().getSimpleName()
                        + ": " + (e.getMessage() == null ? "" : e.getMessage()) + "\n";
                Files.writeString(target, appended);
                Files.delete(file);
            }
        } catch (IOException ioe) {
            log.error("[ClaudeSubmitBridge] moveToFailed 失敗: {}", ioe.getMessage());
        }
    }

    private void moveToRetry(Path file, String displayName, Exception e) {
        Path target = retryDir.resolve(displayName.replace(".json", ".retry.json"));
        move(file, target);
    }

    private void move(Path from, Path to) {
        try {
            if (Files.exists(from)) {
                Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.error("[ClaudeSubmitBridge] move {} → {} 失敗: {}", from, to, e.getMessage());
        }
    }

    // ── 錯誤記錄 / 通知 ──────────────────────────────────────────────────

    private void logError(String fileName, Long taskId, String taskType,
                          LocalDate tradingDate, String errorCode, String message, String hash) {
        try {
            FileBridgeErrorLogEntity entity = new FileBridgeErrorLogEntity();
            entity.setFileName(fileName);
            entity.setTaskId(taskId);
            entity.setTaskType(taskType);
            entity.setTradingDate(tradingDate);
            entity.setErrorCode(errorCode);
            entity.setErrorMessage(message == null ? null : message.substring(0, Math.min(message.length(), 1000)));
            entity.setFileHash(hash);
            errorLogRepository.save(entity);
        } catch (Exception ex) {
            log.warn("[ClaudeSubmitBridge] 寫 file_bridge_error_log 失敗: {}", ex.getMessage());
        }
    }

    private void sendSystemAlert(String fileName, String errorCode, String message) {
        try {
            String body = String.format("File Bridge 事件\n檔案：%s\n代碼：%s\n訊息：%s\n來源：Trading System",
                    fileName, errorCode, message == null ? "-" : message);
            lineTemplateService.notifySystemAlert("📁 Claude File Bridge 異常", body);
        } catch (Exception e) {
            log.warn("[ClaudeSubmitBridge] 發系統通知失敗: {}", e.getMessage());
        }
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
}
