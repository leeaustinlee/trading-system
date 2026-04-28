package com.austin.trading.dto.response;

import java.util.List;

/**
 * 候選股 batch upsert 結果。
 * <p>
 * 由 {@link com.austin.trading.engine.MomentumCandidateEngine} hard gate 判斷，
 * 通過者寫入 candidate_stock 並標記 {@code is_momentum_candidate=true}；
 * 未通過者列在 {@code rejections} 中，不會落地。
 * </p>
 *
 * @param received   收到的請求筆數
 * @param accepted   通過 gate 並成功寫入的筆數
 * @param rejected   被 gate 擋掉的筆數
 * @param rejections 每筆退件原因（symbol, reason code, details）
 * @param items      通過後當日所有候選股（含先前已存在者）
 */
public record CandidateBatchSaveResponse(
        int received,
        int accepted,
        int rejected,
        List<Rejection> rejections,
        List<CandidateResponse> items
) {
    /**
     * 退件理由。
     *
     * <p>常見 reason code：</p>
     * <ul>
     *   <li>{@code HARD_VETO_CODEX}            — Codex 已標 veto</li>
     *   <li>{@code HARD_VETO_CLAUDE_LOW_SCORE} — Claude 分 &lt; 4.0</li>
     *   <li>{@code HARD_VETO_RISK_FLAG}        — 命中 hard risk flag</li>
     *   <li>{@code INSUFFICIENT_CONDITIONS}    — 5 條基本條件僅符合 &lt; 3 條</li>
     * </ul>
     */
    public record Rejection(String symbol, String reason, String details) { }
}
