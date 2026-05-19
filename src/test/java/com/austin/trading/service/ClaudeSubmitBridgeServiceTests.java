package com.austin.trading.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ClaudeSubmitBridgeServiceTests {

    @TempDir
    Path tempDir;

    @Test
    void moveToFailed_shouldKeepJsonPayloadParseableAndNotAppendPlainTextError() throws Exception {
        Path processedDir = tempDir.resolve("processed");
        Path failedDir = tempDir.resolve("failed");
        Path retryDir = tempDir.resolve("retry");
        ClaudeSubmitBridgeService service = new ClaudeSubmitBridgeService(
                null, null, null, false,
                processedDir.toString(), failedDir.toString(), retryDir.toString());

        String originalJson = "{\"taskId\":123,\"taskType\":\"PREMARKET\",\"contentMarkdown\":\"測試\"}";
        Path processing = Files.writeString(
                tempDir.resolve("claude-PREMARKET-2026-05-19-0820-task-123.json.processing"),
                originalJson);

        Method method = ClaudeSubmitBridgeService.class.getDeclaredMethod(
                "moveToFailed", Path.class, String.class, Exception.class);
        method.setAccessible(true);
        method.invoke(service, processing,
                "claude-PREMARKET-2026-05-19-0820-task-123.json",
                new IllegalStateException("TASK_NOT_FOUND"));

        Path failed = failedDir.resolve("claude-PREMARKET-2026-05-19-0820-task-123.failed.json");
        assertThat(Files.exists(processing)).isFalse();
        assertThat(failed).exists();
        String failedText = Files.readString(failed);
        assertThat(failedText).isEqualTo(originalJson);
        assertThat(failedText).doesNotContain("__ERROR__");
        assertThat(new ObjectMapper().readTree(failedText).get("taskId").asLong()).isEqualTo(123L);
    }
}
