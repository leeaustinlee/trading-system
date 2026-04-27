package com.austin.trading.engine.exit;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 出場規則評估器（Paper Trade 與 Backtest 共用）。
 *
 * <p>同一個 evaluator 實例會被 {@code PaperTradeMtmJob} 與 (未來的) {@code BacktestService}
 * 注入,確保「forward live 模擬」與「歷史回測」採用完全一致的出場邏輯,
 * 兩邊的 KPI 才能直接比較。</p>
 *
 * <p>實作必須是 pure function:給定相同 entry + bar,永遠回相同 ExitDecision,
 * 不得讀 DB / 呼叫外部 API。</p>
 */
public interface ExitRuleEvaluator {

    /**
     * 在某一根 daily bar 上評估是否要出場。
     *
     * @param entry 進場時鎖定的價位與規則
     * @param bar   今日 bar 的高低收 + 持倉天數
     * @return 出場決策
     */
    ExitDecision evaluate(EntrySnapshot entry, DailyBar bar);

    /** 進場時鎖定的關鍵價位,evaluator 整個生命週期不變。 */
    record EntrySnapshot(
            LocalDate entryDate,
            BigDecimal entryPrice,
            BigDecimal stopLossPrice,
            BigDecimal target1Price,
            BigDecimal target2Price,
            int maxHoldingDays
    ) {}

    /** 一根日 K 的高低收 + 持倉天數（barDate 為基準算）。 */
    record DailyBar(
            LocalDate barDate,
            BigDecimal high,
            BigDecimal low,
            BigDecimal close,
            int holdingDaysAtBarEnd
    ) {}

    /** 出場決策。{@code shouldExit=false} 時其他欄位皆 null。 */
    record ExitDecision(
            boolean shouldExit,
            ExitReason reason,
            BigDecimal exitPrice,
            BigDecimal mfePct,
            BigDecimal maePct
    ) {
        public static ExitDecision hold(BigDecimal mfePct, BigDecimal maePct) {
            return new ExitDecision(false, null, null, mfePct, maePct);
        }
    }

    enum ExitReason {
        STOP_LOSS,
        TARGET_1,
        TARGET_2,
        TIME_EXIT
    }
}
