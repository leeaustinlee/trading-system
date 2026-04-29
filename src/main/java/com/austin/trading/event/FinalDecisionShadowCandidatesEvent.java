package com.austin.trading.event;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * P0.6b (2026-04-29): FinalDecision 評分結束後，把「final_score >= paper.shadow.score_min
 * 但未實際 ENTER」的候選股 publish 出來，給 {@code PaperTradeService.onShadowCandidates}
 * 寫入 shadow paper_trade（forward testing pipeline）。
 *
 * <p>用 {@code @TransactionalEventListener(phase = AFTER_COMMIT)} 接收，
 * 確保 final_decision row 真的入庫後才嘗試開 shadow trade（與 ENTER live trade 走同樣
 * commit boundary）。</p>
 *
 * <p>publisher 端負責過濾 score 門檻 + 排除 ENTER 已 cover 的 symbol，listener 端只負責
 * 寫 paper_trade。</p>
 */
public record FinalDecisionShadowCandidatesEvent(
        LocalDate tradingDate,
        String triggerDecisionCode,    // 觸發此次 evaluate 的最終 decision: ENTER / WAIT / REST 等
        String sourceTaskType,          // PREMARKET / OPENING / MIDDAY / POSTMARKET / T86_TOMORROW
        List<ShadowCandidate> shadowCandidates
) {

    public record ShadowCandidate(
            String symbol,
            String stockName,
            BigDecimal finalRankScore,
            BigDecimal entryPrice,        // 必填，> 0；若 candidate 沒 currentPrice 不能 publish
            BigDecimal stopLossPrice,     // 可 null（PaperTradeService 用 -5% fallback）
            BigDecimal takeProfit1,       // 可 null（+8% fallback）
            BigDecimal takeProfit2,       // 可 null（+15% fallback）
            String entryGrade,            // A_PLUS / A / B / SHADOW
            String strategyType,          // SETUP / MOMENTUM_CHASE
            String themeTag
    ) {}
}
