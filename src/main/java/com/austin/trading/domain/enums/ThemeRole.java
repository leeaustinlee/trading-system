package com.austin.trading.domain.enums;

import java.util.Locale;

/**
 * v2 Theme Engine：候選股在題材中扮演的角色（由 Codex 題材掃描 / Claude 題材研究回填）。
 *
 * <ul>
 *   <li>{@link #LEADER}    — 題材領漲股（最強勢）</li>
 *   <li>{@link #FOLLOWER}  — 題材跟漲股（中段補漲）</li>
 *   <li>{@link #LAGGARD}   — 落後股（補漲尾段或弱勢）</li>
 *   <li>{@link #UNKNOWN}   — 未分類 / 資料不足</li>
 * </ul>
 */
public enum ThemeRole {
    LEADER,
    FOLLOWER,
    LAGGARD,
    UNKNOWN;

    public static ThemeRole parseOrUnknown(String raw) {
        if (raw == null) return UNKNOWN;
        String t = raw.trim().toUpperCase(Locale.ROOT);
        if (t.isEmpty()) return UNKNOWN;
        try {
            return ThemeRole.valueOf(t);
        } catch (IllegalArgumentException e) {
            return UNKNOWN;
        }
    }
}
