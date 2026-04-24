package com.austin.trading.dto.internal;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * v2.9 Gate 6/7 Refactor：price gate 評估結果。
 *
 * <p>用途：把「belowOpen / belowPrevClose 是否該 hard block」的判斷從 boolean 擴展為三態
 * {@link Action}：</p>
 * <ul>
 *     <li>{@link Action#PASS}  — 價格條件通過，candidate 可直接進入分桶</li>
 *     <li>{@link Action#WAIT}  — 疑似洗盤 / 小幅回測 / VWAP 不明，不 hard block 但也不 ENTER，
 *         降級為 WAIT 等候盤中確認</li>
 *     <li>{@link Action#BLOCK} — 真弱勢，hard block 剔除候選</li>
 * </ul>
 *
 * <p>trace map 會被 Service 原樣塞進 decisionTrace.priceGate，方便盤後檢討。</p>
 */
public record PriceGateDecision(
        Action action,
        String reason,
        Map<String, Object> trace
) {
    public enum Action { PASS, WAIT, BLOCK }

    public boolean isPass()  { return action == Action.PASS; }
    public boolean isWait()  { return action == Action.WAIT; }
    public boolean isBlock() { return action == Action.BLOCK; }

    public static PriceGateDecision pass(Map<String, Object> trace) {
        return new PriceGateDecision(Action.PASS, "PRICE_GATE_PASS", safeTrace(trace));
    }

    public static PriceGateDecision wait(String reason, Map<String, Object> trace) {
        return new PriceGateDecision(Action.WAIT, reason, safeTrace(trace));
    }

    public static PriceGateDecision block(String reason, Map<String, Object> trace) {
        return new PriceGateDecision(Action.BLOCK, reason, safeTrace(trace));
    }

    private static Map<String, Object> safeTrace(Map<String, Object> trace) {
        return trace == null ? new LinkedHashMap<>() : new LinkedHashMap<>(trace);
    }
}
