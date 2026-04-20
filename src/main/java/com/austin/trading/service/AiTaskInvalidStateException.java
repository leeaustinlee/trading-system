package com.austin.trading.service;

import java.util.List;

/**
 * AI task 狀態機轉移違規時丟出（v2.1）。
 * <p>對應 HTTP 409。</p>
 */
public class AiTaskInvalidStateException extends RuntimeException {

    private final AiTaskErrorCode errorCode;
    private final Long taskId;
    private final String currentStatus;
    private final List<String> expectedStatuses;

    public AiTaskInvalidStateException(AiTaskErrorCode errorCode, Long taskId,
                                        String currentStatus, List<String> expectedStatuses,
                                        String message) {
        super(message);
        this.errorCode = errorCode;
        this.taskId = taskId;
        this.currentStatus = currentStatus;
        this.expectedStatuses = expectedStatuses;
    }

    public AiTaskErrorCode getErrorCode() { return errorCode; }
    public Long getTaskId() { return taskId; }
    public String getCurrentStatus() { return currentStatus; }
    public List<String> getExpectedStatuses() { return expectedStatuses; }
}
