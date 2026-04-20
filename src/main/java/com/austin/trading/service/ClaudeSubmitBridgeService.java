package com.austin.trading.service;

import com.austin.trading.dto.request.ClaudeSubmitFileRequest;
import com.austin.trading.dto.request.ClaudeSubmitRequest;
import com.austin.trading.entity.AiTaskEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.Optional;

/**
 * Claude File Bridge 的處理核心：收到一個 claude-submit JSON 檔案 → submit 到 task queue。
 *
 * <p>檔案命名建議：{@code claude-submit-{taskType}-{date}-{hhmm}.json}，例如
 * {@code claude-submit-PREMARKET-2026-04-20-0820.json}。</p>
 *
 * <p>處理完成後 rename：</p>
 * <ul>
 *     <li>成功 → {@code *.processed.json}</li>
 *     <li>失敗 → {@code *.failed.json}（內容會附加 __ERROR__ 區塊）</li>
 * </ul>
 */
@Service
public class ClaudeSubmitBridgeService {

    private static final Logger log = LoggerFactory.getLogger(ClaudeSubmitBridgeService.class);

    private final AiTaskService aiTaskService;
    private final ObjectMapper objectMapper;

    public ClaudeSubmitBridgeService(AiTaskService aiTaskService) {
        this.aiTaskService = aiTaskService;
        this.objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    /**
     * 處理單一 JSON 檔案。成功/失敗皆 rename，不拋 exception（讓 watcher 繼續處理下一個）。
     */
    public void processFile(Path file) {
        String fileName = file.getFileName().toString();
        try {
            String json = Files.readString(file);
            ClaudeSubmitFileRequest req = objectMapper.readValue(json, ClaudeSubmitFileRequest.class);

            Long taskId = resolveTaskId(req);
            if (taskId == null) {
                // 找不到對應 task → 自動建一個 PENDING task（應對 Java 還沒有對應 DataPrepJob 的時段，例如 OPENING / MIDDAY / T86_TOMORROW）
                if (req.taskType() == null || req.taskType().isBlank()) {
                    throw new IllegalStateException("JSON 缺 taskType，且沒有 taskId 可用");
                }
                LocalDate date = Optional.ofNullable(req.tradingDate()).orElse(LocalDate.now());
                AiTaskEntity newTask = aiTaskService.createTask(
                        date, req.taskType().toUpperCase(), null, java.util.List.of(),
                        "Auto-created by ClaudeSubmitBridge (" + fileName + ")",
                        "claude-submit:" + fileName
                );
                taskId = newTask.getId();
                log.info("[ClaudeSubmitBridge] ⚙️ 自動建新 task id={} type={} date={}",
                        taskId, req.taskType(), date);
            }

            final Long resolvedTaskId = taskId;
            AiTaskEntity task = aiTaskService.getById(resolvedTaskId)
                    .orElseThrow(() -> new IllegalStateException("Task not found: " + resolvedTaskId));

            // 若 task 是 PENDING → 先 claim 變 CLAUDE_RUNNING
            if ("PENDING".equalsIgnoreCase(task.getStatus())) {
                aiTaskService.claim(resolvedTaskId);
            }

            ClaudeSubmitRequest submitReq = new ClaudeSubmitRequest(
                    req.contentMarkdown(),
                    req.scores(),
                    req.thesis(),
                    req.riskFlags()
            );
            AiTaskEntity saved = aiTaskService.submitClaudeResult(resolvedTaskId, submitReq);

            moveTo(file, ".processed.json");
            log.info("[ClaudeSubmitBridge] ✅ {} → task {} ({}) status={}, scores={}",
                    fileName, taskId, saved.getTaskType(), saved.getStatus(),
                    req.scores() == null ? 0 : req.scores().size());

        } catch (Exception e) {
            log.error("[ClaudeSubmitBridge] ❌ {}: {}", fileName, e.getMessage(), e);
            try {
                String original = Files.exists(file) ? Files.readString(file) : "(原檔已遺失)";
                Path failed = file.resolveSibling(
                        fileName.replace(".json", ".failed.json"));
                Files.writeString(failed,
                        original + "\n\n__ERROR__ " + e.getClass().getSimpleName()
                                + ": " + e.getMessage() + "\n");
                if (Files.exists(file)) Files.delete(file);
            } catch (IOException ioe) {
                log.error("[ClaudeSubmitBridge] 連寫 .failed.json 都失敗：{}", ioe.getMessage());
            }
        }
    }

    /**
     * 找對應 task。優先用 req.taskId；否則用 taskType+tradingDate 找最新非終結狀態的 task。
     */
    private Long resolveTaskId(ClaudeSubmitFileRequest req) {
        if (req.taskId() != null) return req.taskId();
        if (req.taskType() == null || req.taskType().isBlank()) return null;

        LocalDate date = Optional.ofNullable(req.tradingDate()).orElse(LocalDate.now());
        return aiTaskService.getByDate(date).stream()
                .filter(t -> req.taskType().equalsIgnoreCase(t.getTaskType()))
                .filter(t -> !"FINALIZED".equalsIgnoreCase(t.getStatus())
                          && !"EXPIRED".equalsIgnoreCase(t.getStatus()))
                .reduce((first, second) -> second)   // 取最新一筆
                .map(AiTaskEntity::getId)
                .orElse(null);
    }

    private void moveTo(Path file, String suffix) throws IOException {
        String newName = file.getFileName().toString().replace(".json", suffix);
        Path target = file.resolveSibling(newName);
        Files.move(file, target, StandardCopyOption.REPLACE_EXISTING);
    }
}
