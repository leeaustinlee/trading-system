package com.austin.trading.dto.request;

import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;
import java.util.List;

/**
 * 手動建立 AI 任務的 API body（PR-2）。
 */
public record AiTaskCreateRequest(
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        LocalDate tradingDate,           // null → 今日

        String taskType,                 // PREMARKET / POSTMARKET / ...
        String targetSymbol,             // STOCK_EVAL 才會有
        List<AiTaskCandidateRef> candidates,
        String promptSummary,
        String promptFilePath
) {
}
