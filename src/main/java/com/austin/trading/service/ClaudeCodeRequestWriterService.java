package com.austin.trading.service;

import com.austin.trading.config.AiClaudeConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Claude Code 研究請求寫入服務。
 * <p>
 * 無 API Key 模式下的核心橋梁：
 * Java 排程收集完市場資料後，呼叫此服務將「研究請求」寫成 JSON 檔案，
 * 由 Claude Code 排程 Agent 讀取後執行深度研究，再寫回 claude-research-latest.md。
 * </p>
 *
 * <pre>
 * 流程：
 *   PremarketDataPrepJob (08:10) → writeRequest("PREMARKET", ...)
 *   → claude-research-request.json
 *   → Claude Code 排程 Agent (08:20) 讀取並分析
 *   → claude-research-latest.md
 *   → PremarketNotifyJob (08:30) / Codex 讀取使用
 * </pre>
 *
 * <p>
 * 設定路徑（application.yml）：
 * <pre>
 *   trading.ai.claude.request-output-path: "D:/ai/stock/claude-research-request.json"
 * </pre>
 * 若路徑未設定，此服務的所有呼叫將靜默略過（不影響主流程）。
 * </p>
 */
@Service
public class ClaudeCodeRequestWriterService {

    private static final Logger log = LoggerFactory.getLogger(ClaudeCodeRequestWriterService.class);
    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 固定規則檔路徑（Claude Code Agent 必讀）*/
    private static final List<String> RULES_FILES = List.of(
            "D:/ai/stock/AI_RULES_INDEX.md",
            "D:/ai/stock/dual-ai-workflow.md",
            "D:/ai/stock/market-data-protocol.md",
            "D:/ai/stock/market-snapshot.json",
            "D:/ai/stock/capital-summary.md",
            "D:/ai/stock/trade-decision-engine.md",
            "D:/ai/stock/market-gate-self-optimization-engine.md"
    );

    private final AiClaudeConfig config;
    private final ObjectMapper   objectMapper;

    public ClaudeCodeRequestWriterService(AiClaudeConfig config, ObjectMapper objectMapper) {
        this.config       = config;
        this.objectMapper = objectMapper;
    }

    /**
     * 寫出研究請求 JSON 給 Claude Code 排程 Agent 讀取。
     *
     * @param type             研究類型（PREMARKET / POSTMARKET / STOCK_EVAL / FINAL_DECISION / MIDDAY）
     * @param tradingDate      交易日
     * @param candidateSymbols 候選股代號清單（可為空）
     * @param contextPayload   補充 context（已整理的 JSON 字串，可為 null）
     * @return 是否成功寫入
     */
    public boolean writeRequest(
            String type,
            LocalDate tradingDate,
            List<String> candidateSymbols,
            String contextPayload
    ) {
        String path = config.getRequestOutputPath();
        if (path == null || path.isBlank()) {
            log.debug("[ClaudeCodeRequestWriter] request-output-path not set, skip.");
            return false;
        }

        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("requested_at", LocalDateTime.now().format(DT_FMT));
            root.put("type", type);
            root.put("trading_date", tradingDate.toString());

            // 候選股
            ArrayNode symbols = root.putArray("candidates");
            if (candidateSymbols != null) {
                candidateSymbols.forEach(symbols::add);
            }

            // 補充 context（由 caller 傳入，如 txf 報價、大盤漲跌家數等）
            if (contextPayload != null && !contextPayload.isBlank()) {
                root.put("market_context", contextPayload);
            }

            // 規則檔清單（Claude Code Agent 必讀）
            ArrayNode rules = root.putArray("rules_files");
            RULES_FILES.forEach(rules::add);

            // 輸出路徑（Claude Code Agent 研究完要寫入的位置）
            String outputPath = config.getResearchOutputPath();
            if (outputPath != null && !outputPath.isBlank()) {
                root.put("output_path", outputPath);
            }

            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
            Path dest = Paths.get(path);
            if (dest.getParent() != null) {
                Files.createDirectories(dest.getParent());
            }
            Files.writeString(dest, json, StandardCharsets.UTF_8);
            log.info("[ClaudeCodeRequestWriter] Written type={} candidates={} to {}",
                    type, candidateSymbols == null ? 0 : candidateSymbols.size(), path);
            return true;

        } catch (Exception e) {
            log.warn("[ClaudeCodeRequestWriter] Failed to write request: {}", e.getMessage());
            return false;
        }
    }
}
