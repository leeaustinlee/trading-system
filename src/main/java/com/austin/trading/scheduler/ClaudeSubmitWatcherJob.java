package com.austin.trading.scheduler;

import com.austin.trading.service.ClaudeSubmitBridgeService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

/**
 * 每 30 秒掃 {@code trading.claude-submit.watch-dir} 下的 *.json（v2.1 安全版）。
 *
 * <p>規格：</p>
 * <ul>
 *   <li>只讀 {@code .json}，不讀 {@code .tmp}（Claude atomic write 協定）</li>
 *   <li>檔案 last-modified 距今須穩定 &ge; 2 秒才處理（避免讀到寫一半）</li>
 *   <li>處理前 rename 為 {@code *.processing}，避免重複處理</li>
 *   <li>成功搬到 {@code processed/}，失敗搬 {@code failed/}，可重試搬 {@code retry/}（由 bridge 決定）</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(prefix = "trading.claude-submit", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class ClaudeSubmitWatcherJob {

    private static final Logger log = LoggerFactory.getLogger(ClaudeSubmitWatcherJob.class);

    private final ClaudeSubmitBridgeService bridgeService;
    private final Path watchDir;
    private final long requiredStableSeconds;

    public ClaudeSubmitWatcherJob(
            ClaudeSubmitBridgeService bridgeService,
            @Value("${trading.claude-submit.watch-dir:D:/ai/stock/claude-submit}") String watchDir,
            @Value("${ai.file_bridge.required_stable_seconds:2}") long requiredStableSeconds
    ) {
        this.bridgeService = bridgeService;
        this.watchDir = Path.of(watchDir);
        this.requiredStableSeconds = requiredStableSeconds;
    }

    @PostConstruct
    public void ensureDir() {
        try {
            if (!Files.exists(watchDir)) {
                Files.createDirectories(watchDir);
                log.info("[ClaudeSubmitWatcher] 建立 watch dir: {}", watchDir);
            } else {
                log.info("[ClaudeSubmitWatcher] watch dir 已存在: {} (stable={}s)",
                        watchDir, requiredStableSeconds);
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
            List<Path> candidates = stream.filter(this::isPendingJson).toList();
            if (candidates.isEmpty()) {
                log.debug("[ClaudeSubmitWatcher] scan tick, 0 pending files in {}", watchDir);
                return;
            }
            for (Path p : candidates) {
                if (!isStable(p)) {
                    log.debug("[ClaudeSubmitWatcher] {} 尚未穩定（< {}s），延後下次掃",
                            p.getFileName(), requiredStableSeconds);
                    continue;
                }
                Path processing = tryClaim(p);
                if (processing == null) {
                    log.debug("[ClaudeSubmitWatcher] {} claim 失敗（另一 worker 搶走），skip",
                            p.getFileName());
                    continue;
                }
                log.info("[ClaudeSubmitWatcher] pick up: {} → {}",
                        p.getFileName(), processing.getFileName());
                bridgeService.processFile(processing);
            }
        } catch (IOException e) {
            log.warn("[ClaudeSubmitWatcher] scan 失敗: {}", e.getMessage());
        }
    }

    /**
     * 只處理 {@code .json} 副檔名，排除 {@code .tmp / .processing / .processed.json / .failed.json / .retry.json}。
     */
    private boolean isPendingJson(Path p) {
        String name = p.getFileName().toString();
        if (!name.endsWith(".json")) return false;
        // 排除 *.tmp 絕不處理
        if (name.endsWith(".tmp")) return false;
        // 已處理/失敗/重試 skip
        if (name.endsWith(".processed.json")
                || name.endsWith(".failed.json")
                || name.endsWith(".retry.json")) return false;
        // .processing 中的檔由另一 worker 擁有，skip
        if (name.endsWith(".processing")) return false;
        return true;
    }

    /** 檔案最後修改時間距今 &ge; requiredStableSeconds 才算穩定 */
    private boolean isStable(Path p) {
        try {
            BasicFileAttributes attrs = Files.readAttributes(p, BasicFileAttributes.class);
            Instant lastModified = attrs.lastModifiedTime().toInstant();
            long elapsed = Instant.now().getEpochSecond() - lastModified.getEpochSecond();
            return elapsed >= requiredStableSeconds;
        } catch (IOException e) {
            return false;
        }
    }

    /** rename 為 {@code *.processing} 作為 lock；若已被搶走（NoSuchFile）則 skip */
    private Path tryClaim(Path p) {
        Path target = p.resolveSibling(p.getFileName().toString() + ".processing");
        try {
            return Files.move(p, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            return null;
        }
    }
}
