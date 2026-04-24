package com.austin.trading.domain.enums;

/**
 * v2.11 Capital Allocation MVP：資金配置建議行動（僅建議，不下單）。
 *
 * <ul>
 *   <li>{@link #BUY_SIZE_SUGGESTION}    新倉進場的建議金額/股數</li>
 *   <li>{@link #ADD_SIZE_SUGGESTION}    加碼金額/股數建議</li>
 *   <li>{@link #REDUCE_SIZE_SUGGESTION} 減碼比例/股數建議（30%–50%）</li>
 *   <li>{@link #SWITCH_SIZE_SUGGESTION} 換股時原倉減碼 + 新倉配置</li>
 *   <li>{@link #CASH_RESERVE}           資金不足或觸發保留比例 → 保留現金不配置</li>
 *   <li>{@link #RISK_BLOCK}             風險門檻卡關（無效停損、超出市場/題材曝險等）</li>
 * </ul>
 */
public enum AllocationAction {
    BUY_SIZE_SUGGESTION,
    ADD_SIZE_SUGGESTION,
    REDUCE_SIZE_SUGGESTION,
    SWITCH_SIZE_SUGGESTION,
    CASH_RESERVE,
    RISK_BLOCK
}
