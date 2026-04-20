package com.austin.trading.client;

import com.austin.trading.config.AiClaudeConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Codex 整合客戶端。
 * <p>
 * 負責將 Claude 研究結果輸出給 Codex 使用：
 * 1. 寫入 {@code trading.ai.claude.research-output-path} 設定的 Markdown 檔案
 *    （供 Codex 自動讀取，預設 D:/ai/stock/claude-research-latest.md）
 * 2. 寫入成功後記錄 log。
 * </p>
 */
@Component
public class AiCodexClient {

    private static final Logger log = LoggerFactory.getLogger(AiCodexClient.class);
    private static final DateTimeFormatter DT_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final AiClaudeConfig config;

    public AiCodexClient(AiClaudeConfig config) {
        this.config = config;
    }

    /**
     * 將研究內容寫入 claude-research-latest.md（若已設定路徑）。
     *
     * @param title    研究標題（用於 Markdown 標頭）
     * @param content  研究正文
     * @param symbol   個股代號（市場層級研究時為 null）
     * @return 是否成功寫入
     */
    public boolean publishResearch(String title, String content, String symbol) {
        String outputPath = config.getResearchOutputPath();
        if (outputPath == null || outputPath.isBlank()) {
            log.debug("[AiCodexClient] research-output-path not set, skip file write.");
            return false;
        }

        try {
            String timestamp = LocalDateTime.now().format(DT_FMT);
            String symbolLine = symbol != null ? "**標的**：" + symbol + "\n" : "";
            String markdown = "# " + title + "\n\n" +
                    "> 生成時間：" + timestamp + "\n" +
                    (symbolLine.isEmpty() ? "" : "> " + symbolLine) +
                    "\n---\n\n" + content + "\n\n---\n_研究來源：Claude_\n";

            Path path = Paths.get(outputPath);
            // 確保父目錄存在
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Files.writeString(path, markdown, StandardCharsets.UTF_8);
            log.info("[AiCodexClient] Written to {}", outputPath);
            return true;
        } catch (IOException e) {
            log.warn("[AiCodexClient] Failed to write {}: {}", outputPath, e.getMessage());
            return false;
        }
    }
}
