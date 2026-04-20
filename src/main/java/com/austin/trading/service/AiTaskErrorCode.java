package com.austin.trading.service;

/**
 * v2.1 AI 任務 / workflow 錯誤碼。
 * <p>與 docs/workflow-correctness-ai-orchestration-spec.md §6.2 保持一致。</p>
 */
public enum AiTaskErrorCode {
    AI_TASK_INVALID_STATE,
    AI_TASK_ALREADY_ADVANCED,
    AI_NOT_READY,
    AI_TIMEOUT,
    CODEX_MISSING,
    FILE_BRIDGE_PARSE_ERROR,
    TASK_EXPIRED,
    TASK_NOT_FOUND,
    TASK_AUTO_CREATED_BY_BRIDGE
}
