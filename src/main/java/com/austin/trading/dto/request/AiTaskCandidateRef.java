package com.austin.trading.dto.request;

import java.math.BigDecimal;

/**
 * 建立 AI 任務時帶入的候選股參考。
 * 用於序列化為 ai_task.target_candidates_json。
 */
public record AiTaskCandidateRef(
        String symbol,
        String stockName,
        String themeTag,
        BigDecimal javaStructureScore
) {
}
