package com.austin.trading.dto.internal;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * v2 Theme Engine PR4：單一 gate 的 trace 記錄。
 *
 * <p>對應 {@code theme-engine-implementation-spec.md §6.1 Gate Contract Table}。
 * 每個 gate 輸出一筆，PR4 只用於 trace，不影響 legacy decision。</p>
 *
 * <ul>
 *   <li>{@code gateKey}   — G1_MARKET_REGIME / G2_THEME_VETO / ... / G8_FINAL_RANK</li>
 *   <li>{@code gateName}  — human-readable 名稱</li>
 *   <li>{@code result}    — PASS / WAIT / BLOCK / SKIPPED（BLOCK 短路後下游 gate）</li>
 *   <li>{@code reason}    — short code（PR5 shadow report 用）</li>
 *   <li>{@code humanText} — LINE / 檢討報告可讀文字</li>
 *   <li>{@code payload}   — 決策時的輸入快照（便於盤後檢討）</li>
 * </ul>
 */
public record GateTraceRecordDto(
        String gateKey,
        String gateName,
        Result result,
        String reason,
        String humanText,
        Map<String, Object> payload
) {
    public enum Result { PASS, WAIT, BLOCK, SKIPPED;
        /** Severity ordering：用於計算 overall outcome（SKIPPED 不算）。 */
        public int severity() {
            return switch (this) {
                case PASS     -> 0;
                case WAIT     -> 1;
                case BLOCK    -> 2;
                case SKIPPED  -> -1;
            };
        }
    }

    public static GateTraceRecordDto pass(String key, String name, String human, Map<String, Object> payload) {
        return new GateTraceRecordDto(key, name, Result.PASS, "OK", human, safe(payload));
    }

    public static GateTraceRecordDto wait(String key, String name, String reason, String human,
                                           Map<String, Object> payload) {
        return new GateTraceRecordDto(key, name, Result.WAIT, reason, human, safe(payload));
    }

    public static GateTraceRecordDto block(String key, String name, String reason, String human,
                                            Map<String, Object> payload) {
        return new GateTraceRecordDto(key, name, Result.BLOCK, reason, human, safe(payload));
    }

    public static GateTraceRecordDto skipped(String key, String name, String reason) {
        return new GateTraceRecordDto(key, name, Result.SKIPPED, reason,
                "因上游 BLOCK 短路，本 gate 未執行", Map.of());
    }

    private static Map<String, Object> safe(Map<String, Object> p) {
        return p == null ? new LinkedHashMap<>() : new LinkedHashMap<>(p);
    }
}
