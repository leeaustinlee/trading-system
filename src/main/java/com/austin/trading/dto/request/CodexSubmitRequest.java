package com.austin.trading.dto.request;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Codex 審核結果提交 body（PR-2）。
 * <pre>
 * POST /api/ai/tasks/{id}/codex-result
 * {
 *   "contentMarkdown": "...",
 *   "scores": {"2303": 8.0, "3231": 7.5},
 *   "vetoSymbols": ["6213"],
 *   "reviewIssues": {"6213": "法說風險未揭露"}
 * }
 * </pre>
 */
public record CodexSubmitRequest(
        String contentMarkdown,
        Map<String, BigDecimal> scores,
        List<String> vetoSymbols,
        Map<String, String> reviewIssues
) {
}
