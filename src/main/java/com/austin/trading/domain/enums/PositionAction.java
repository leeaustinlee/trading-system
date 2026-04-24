package com.austin.trading.domain.enums;

/**
 * v2.10 Position Management MVP：對 OPEN 持倉每 5 分鐘計算的行動建議。
 *
 * <p>與既有 {@link com.austin.trading.engine.PositionDecisionEngine.PositionStatus} 的關係：
 * PositionDecisionEngine 的 STRONG/HOLD/WEAKEN/EXIT/TRAIL_UP 是「底層結構訊號」，
 * PositionAction 是「要對 Austin 做什麼」的高階建議，會參考底層 status + VWAP / volumeRatio /
 * peak giveback / regime 做出加 / 減 / 出 / 換股提示。</p>
 *
 * <ul>
 *     <li>{@link #HOLD} — 保持不動；trace 落地但不發 LINE</li>
 *     <li>{@link #ADD} — 建議加碼 0.3 倉（僅提示，不自動下單）</li>
 *     <li>{@link #REDUCE} — 建議減碼保留核心倉（僅提示）</li>
 *     <li>{@link #EXIT} — 建議立刻出場（停損 / 移動停利 / PANIC / 結構破位）</li>
 *     <li>{@link #SWITCH_HINT} — 有更強新候選同主題替代，建議換股（僅提示）</li>
 * </ul>
 */
public enum PositionAction {
    HOLD,
    ADD,
    REDUCE,
    EXIT,
    SWITCH_HINT
}
