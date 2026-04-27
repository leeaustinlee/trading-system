package com.austin.trading.event;

import com.austin.trading.dto.response.FinalDecisionSelectedStockResponse;

import java.time.LocalDate;
import java.util.List;

/**
 * FinalDecisionService 在持久化最終決策後 publish 此事件,
 * 解耦下游消費者(目前是 PaperTradeService;未來可能是 strategy attribution)。
 *
 * <p>用 {@code @TransactionalEventListener(phase = AFTER_COMMIT)} 訂閱,
 * 確保 paper trade 只在 final_decision row 真的入庫後才嘗試開單。</p>
 */
public record FinalDecisionPersistedEvent(
        Long finalDecisionId,
        LocalDate tradingDate,
        String decisionCode,
        String strategyType,
        Long aiTaskId,
        String aiStatus,
        String sourceTaskType,
        List<FinalDecisionSelectedStockResponse> selectedStocks
) {}
