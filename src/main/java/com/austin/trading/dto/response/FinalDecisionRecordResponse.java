package com.austin.trading.dto.response;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record FinalDecisionRecordResponse(
        Long id,
        LocalDate tradingDate,
        String decision,
        String summary,
        String payloadJson,
        LocalDateTime createdAt,
        // v2.1 AI 追溯欄位
        Long aiTaskId,
        String aiStatus,
        String fallbackReason,
        String sourceTaskType,
        LocalDateTime claudeDoneAt,
        LocalDateTime codexDoneAt
) {
    /** 向下相容 constructor（舊程式可能不傳新欄位） */
    public FinalDecisionRecordResponse(Long id, LocalDate tradingDate, String decision,
                                        String summary, String payloadJson, LocalDateTime createdAt) {
        this(id, tradingDate, decision, summary, payloadJson, createdAt,
                null, null, null, null, null, null);
    }
}
