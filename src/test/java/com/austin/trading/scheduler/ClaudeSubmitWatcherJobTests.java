package com.austin.trading.scheduler;

import com.austin.trading.service.ClaudeSubmitBridgeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ClaudeSubmitWatcherJobTests {

    @TempDir
    Path tempDir;

    @Test
    void scan_shouldSkipTmpAndProcessStableJson() throws IOException {
        RecordingBridgeService bridge = new RecordingBridgeService(tempDir);
        ClaudeSubmitWatcherJob watcher = new ClaudeSubmitWatcherJob(bridge, tempDir.toString(), 2);

        Path tmp = Files.writeString(tempDir.resolve("claude-OPENING-2026-04-20-0920-task-1.tmp"), "{}");
        Path json = Files.writeString(tempDir.resolve("claude-OPENING-2026-04-20-0920-task-1.json"), "{}");
        setLastModifiedSecondsAgo(tmp, 5);
        setLastModifiedSecondsAgo(json, 5);

        watcher.scan();

        assertThat(bridge.processedFiles()).hasSize(1);
        assertThat(bridge.processedFiles().get(0).getFileName().toString())
                .isEqualTo("claude-OPENING-2026-04-20-0920-task-1.json.processing");
        assertThat(Files.exists(tmp)).isTrue();
        assertThat(Files.exists(json)).isFalse();
    }

    @Test
    void scan_shouldRespectStableWindow() throws IOException {
        RecordingBridgeService bridge = new RecordingBridgeService(tempDir);
        ClaudeSubmitWatcherJob watcher = new ClaudeSubmitWatcherJob(bridge, tempDir.toString(), 2);

        Path json = Files.writeString(tempDir.resolve("claude-MIDDAY-2026-04-20-1050-task-2.json"), "{}");
        setLastModifiedSecondsAgo(json, 0);

        watcher.scan();
        assertThat(bridge.processedFiles()).isEmpty();

        setLastModifiedSecondsAgo(json, 5);
        watcher.scan();

        assertThat(bridge.processedFiles()).containsExactly(
                tempDir.resolve("claude-MIDDAY-2026-04-20-1050-task-2.json.processing"));
    }

    @Test
    void scan_shouldSkipWhenProcessingLockAlreadyExists() throws IOException {
        RecordingBridgeService bridge = new RecordingBridgeService(tempDir);
        ClaudeSubmitWatcherJob watcher = new ClaudeSubmitWatcherJob(bridge, tempDir.toString(), 2);

        Path json = Files.writeString(tempDir.resolve("claude-PREMARKET-2026-04-20-0820-task-3.json"), "{}");
        Files.createDirectory(tempDir.resolve("claude-PREMARKET-2026-04-20-0820-task-3.json.processing"));
        setLastModifiedSecondsAgo(json, 5);

        watcher.scan();

        assertThat(bridge.processedFiles()).isEmpty();
        assertThat(Files.exists(json)).isTrue();
    }

    private static void setLastModifiedSecondsAgo(Path path, long secondsAgo) throws IOException {
        Files.setLastModifiedTime(path, FileTime.from(Instant.now().minusSeconds(secondsAgo)));
    }

    private static class RecordingBridgeService extends ClaudeSubmitBridgeService {
        private final List<Path> processedFiles = new ArrayList<>();

        private RecordingBridgeService(Path baseDir) {
            super(null, null, null, false,
                    baseDir.resolve("processed").toString(),
                    baseDir.resolve("failed").toString(),
                    baseDir.resolve("retry").toString());
        }

        @Override
        public void processFile(Path file) {
            processedFiles.add(file);
        }

        private List<Path> processedFiles() {
            return processedFiles;
        }
    }
}
