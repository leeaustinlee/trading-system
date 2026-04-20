package com.austin.trading.scheduler;

import com.austin.trading.service.ClaudeSubmitBridgeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * 每 30 秒掃 {@code trading.claude-submit.watch-dir} 下的 *.json（排除已處理/失敗）。
 *
 * <p>用途：Claude Code sandbox 若無法連本機 localhost:8080，可改寫 JSON 到此目錄，
 * 由 Java 自動 submit 到 AI task queue。</p>
 *
 * <p>設定 {@code trading.claude-submit.enabled=false} 可停用。</p>
 */
@Component
@ConditionalOnProperty(prefix = "trading.claude-submit", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class ClaudeSubmitWatcherJob {

    private static final Logger log = LoggerFactory.getLogger(ClaudeSubmitWatcherJob.class);

    private final ClaudeSubmitBridgeService bridgeService;
    private final Path watchDir;

    public ClaudeSubmitWatcherJob(
            ClaudeSubmitBridgeService bridgeService,
            @Value("${trading.claude-submit.watch-dir:D:/ai/stock/claude-submit}") String watchDir
    ) {
        this.bridgeService = bridgeService;
        this.watchDir = Path.of(watchDir);
    }

    @PostConstruct
    public void ensureDir() {
        try {
            if (!Files.exists(watchDir)) {
                Files.createDirectories(watchDir);
                log.info("[ClaudeSubmitWatcher] 建立 watch dir: {}", watchDir);
            } else {
                log.info("[ClaudeSubmitWatcher] watch dir 已存在: {}", watchDir);
            }
        } catch (IOException e) {
            log.warn("[ClaudeSubmitWatcher] 無法建立 watch dir {}: {}", watchDir, e.getMessage());
        }
    }

    @Scheduled(fixedDelayString = "${trading.claude-submit.scan-interval-ms:30000}")
    public void scan() {
        if (!Files.isDirectory(watchDir)) {
            log.debug("[ClaudeSubmitWatcher] scan: watchDir not a directory: {}", watchDir);
            return;
        }
        try (Stream<Path> stream = Files.list(watchDir)) {
            long count = stream.filter(this::isPendingJson)
                    .peek(p -> log.info("[ClaudeSubmitWatcher] pick up: {}", p.getFileName()))
                    .peek(bridgeService::processFile)
                    .count();
            if (count == 0) {
                log.debug("[ClaudeSubmitWatcher] scan tick, 0 pending files in {}", watchDir);
            }
        } catch (IOException e) {
            log.warn("[ClaudeSubmitWatcher] scan 失敗: {}", e.getMessage());
        }
    }

    private boolean isPendingJson(Path p) {
        String name = p.getFileName().toString();
        return name.endsWith(".json")
                && !name.endsWith(".processed.json")
                && !name.endsWith(".failed.json");
    }
}
