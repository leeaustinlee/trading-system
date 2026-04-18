package com.austin.trading.dto.request;

import org.springframework.format.annotation.DateTimeFormat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Claude / Codex AI 評分回填請求。
 * <p>
 * Codex 完成個股審核後，呼叫 {@code PUT /api/candidates/{symbol}/ai-scores}
 * 將 claudeScore / codexScore 寫入 stock_evaluation，
 * 系統自動重算 ai_weighted_score 與 final_rank_score。
 * </p>
 */
public record AiScoreUpdateRequest(
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        LocalDate tradingDate,           // null 表示今日

        BigDecimal claudeScore,          // Claude 研究評分（0-10）
        BigDecimal claudeConfidence,     // Claude 信心度（0-1）
        String     claudeThesis,         // Claude 研究摘要
        List<String> claudeRiskFlags,    // Claude 風險標記

        BigDecimal codexScore,           // Codex 審核評分（0-10）
        BigDecimal codexConfidence,      // Codex 信心度（0-1）
        List<String> codexReviewIssues   // Codex 審核問題
) {}
