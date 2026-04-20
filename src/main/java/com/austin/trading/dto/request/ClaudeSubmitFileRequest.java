package com.austin.trading.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Claude 透過「寫 JSON 檔 + Java 掃檔」方式提交研究結果（File Bridge 方案 A）。
 *
 * <p>Claude Code 若跑在 cloud sandbox 無法連 Windows localhost:8080，
 * 改為寫 JSON 到 {@code trading.claude-submit.watch-dir}，Java 自動 pick up。</p>
 *
 * <p>對應 {@link ClaudeSubmitRequest} 但多 3 個 routing 欄位用於找 task：</p>
 * <ul>
 *     <li>{@code taskId} 最優先，直接指定</li>
 *     <li>{@code taskType} + {@code tradingDate} 次要，找對應 task 的最新一筆</li>
 * </ul>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ClaudeSubmitFileRequest(
        Long taskId,
        String taskType,
        LocalDate tradingDate,
        String contentMarkdown,
        Map<String, BigDecimal> scores,
        Map<String, String> thesis,
        List<String> riskFlags
) {
}
