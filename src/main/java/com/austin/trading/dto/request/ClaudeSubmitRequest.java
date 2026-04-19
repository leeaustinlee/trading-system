package com.austin.trading.dto.request;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Claude 認領任務後提交研究結果的請求 body（PR-2）。
 * <pre>
 * POST /api/ai/tasks/{id}/claude-result
 * {
 *   "contentMarkdown": "完整研究 md 內容",
 *   "scores": {"2303": 8.5, "3231": 7.8, "4938": 7.2},
 *   "thesis": {"2303": "T86 年度級大買，但 4/22 法說風險"},
 *   "riskFlags": ["台積電 T86 大賣，大盤風險偏高"]
 * }
 * </pre>
 */
public record ClaudeSubmitRequest(
        String contentMarkdown,
        Map<String, BigDecimal> scores,
        Map<String, String> thesis,
        List<String> riskFlags
) {
}
