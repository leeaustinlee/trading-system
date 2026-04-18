package com.austin.trading.dto.request;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Claude 題材評分回填請求。
 *
 * <pre>
 * PUT /api/themes/snapshots/{themeTag}/claude-scores
 * {
 *   "tradingDate":            "2026-04-18",   // 選填，null 補今日
 *   "themeHeatScore":         8.5,            // 題材熱度（0-10）
 *   "themeContinuationScore": 7.0,            // 題材延續性（0-10）
 *   "driverType":             "法說",          // 法說 / 政策 / 報價 / 事件 / 籌碼
 *   "riskSummary":            "高檔追價風險"
 * }
 * </pre>
 */
public record ClaudeThemeScoreRequest(
        LocalDate tradingDate,
        BigDecimal themeHeatScore,
        BigDecimal themeContinuationScore,
        String driverType,
        String riskSummary
) {}
