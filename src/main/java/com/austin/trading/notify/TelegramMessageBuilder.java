package com.austin.trading.notify;

import java.util.List;
import java.util.Map;

import static com.austin.trading.notify.TelegramSender.escapeHtml;

/**
 * v2.13 Telegram HTML 訊息組裝（與 {@link LineMessageBuilder} 平行）。
 *
 * <p>專注於 Telegram parse_mode=HTML 下的視覺呈現：</p>
 * <ul>
 *   <li>標題加 emoji + {@code <b>}</li>
 *   <li>數字用 {@code <code>}（mono、易讀）</li>
 *   <li>Reason / signal 代碼自動轉中文</li>
 *   <li>分隔線用 {@code ━━━} 而非無意義 dash</li>
 * </ul>
 *
 * <p>所有方法回傳的字串已 HTML escape 過，可直接送 {@link TelegramSender}。</p>
 */
public final class TelegramMessageBuilder {

    private TelegramMessageBuilder() {}

    /** Position action（ADD / REDUCE / EXIT / SWITCH_HINT）reason → 繁中。 */
    private static final Map<String, String> REASON_ZH = Map.ofEntries(
            Map.entry("EXIT_STOP_LOSS",           "觸發停損"),
            Map.entry("EXIT_TRAILING_STOP",       "觸發移動停利"),
            Map.entry("EXIT_PANIC",               "市場恐慌出場"),
            Map.entry("EXIT_STRUCTURE_BREAK",     "結構破位 + 爆量"),
            Map.entry("EXIT_DRAWDOWN",            "從高點回撤過深"),
            Map.entry("REDUCE_BELOW_VWAP",        "跌破 VWAP"),
            Map.entry("REDUCE_LOW_VOLUME",        "量能不足"),
            Map.entry("REDUCE_PROFIT_GIVEBACK",   "利潤回吐"),
            Map.entry("REDUCE_MOMENTUM_WEAKEN",   "動能轉弱"),
            Map.entry("REDUCE_BASELINE_EXIT_HINT","底層訊號疲弱"),
            Map.entry("ADD_STRONG_CONTINUATION",  "強勢續攻"),
            Map.entry("ADD_BREAKOUT",             "突破加碼"),
            Map.entry("NEW_CANDIDATE_STRONGER",   "出現更強同主題候選"),
            Map.entry("HOLD_TREND_INTACT",        "趨勢完整續抱")
    );

    /** Signal code → 繁中。 */
    private static final Map<String, String> SIGNAL_ZH = Map.ofEntries(
            Map.entry("STOP_LOSS_HIT",                  "停損觸發"),
            Map.entry("TRAILING_STOP_HIT",              "移動停利觸發"),
            Map.entry("REGIME_PANIC",                   "市場恐慌"),
            Map.entry("STRUCTURE_BREAK_HIGH_VOLUME",    "結構破位 + 爆量"),
            Map.entry("BASELINE_EXIT",                  "底層出場訊號"),
            Map.entry("BASELINE_WEAKEN",                "底層轉弱"),
            Map.entry("BELOW_VWAP",                     "跌破 VWAP"),
            Map.entry("ABOVE_VWAP",                     "站上 VWAP"),
            Map.entry("LOW_VOLUME",                     "量能不足"),
            Map.entry("HIGH_VOLUME",                    "量能放大"),
            Map.entry("NEAR_HIGH",                      "接近日高"),
            Map.entry("PROFIT_GIVEBACK_OVER_THRESHOLD", "利潤回吐超過門檻"),
            Map.entry("STRONG_CONTINUATION",            "強勢續攻"),
            Map.entry("HOLD_TREND_INTACT",              "趨勢完整"),
            Map.entry("SWITCH_CANDIDATE_AVAILABLE",     "有更強候選")
    );

    /** Action code → 繁中 + emoji。 */
    private static final Map<String, String[]> ACTION_META = Map.of(
            "ADD",         new String[]{"🟢", "加碼"},
            "REDUCE",      new String[]{"🟡", "減碼"},
            "EXIT",        new String[]{"🔴", "出場"},
            "SWITCH_HINT", new String[]{"🔄", "換股提示"},
            "HOLD",        new String[]{"⏸️", "續抱"}
    );

    static String reasonZh(String code) {
        if (code == null || code.isBlank()) return "";
        String z = REASON_ZH.get(code);
        return z != null ? z : code;  // 未對應的 fallback 顯示原 code
    }

    static String signalZh(String code) {
        if (code == null || code.isBlank()) return "";
        String z = SIGNAL_ZH.get(code);
        return z != null ? z : code;
    }

    static String[] actionMeta(String action) {
        if (action == null) return new String[]{"📌", "—"};
        return ACTION_META.getOrDefault(action.toUpperCase(), new String[]{"📌", action});
    }

    /** 損益 emoji：profit / loss / flat。 */
    private static String pnlEmoji(Double pct) {
        if (pct == null) return "⚪";
        if (pct >= 0.5) return "🟢";
        if (pct <= -0.5) return "🔴";
        return "⚪";
    }

    /** 格式化價格（移除尾端 .0）。 */
    private static String fmtPrice(Double v) {
        if (v == null) return "—";
        if (v == v.intValue()) return Integer.toString(v.intValue());
        return String.format("%.2f", v);
    }

    private static String fmtPct(Double v) {
        if (v == null) return "—";
        return String.format("%+.2f%%", v);
    }

    /** 千分位金額。 */
    private static String fmtMoney(Double v) {
        if (v == null) return "—";
        long lv = Math.round(v);
        return String.format("%,d", lv);
    }

    private static final String DIVIDER = "━━━━━━━━━━━━";

    // ══════════════════════════════════════════════════════════════════════
    // Position Action：ADD / REDUCE / EXIT / SWITCH_HINT
    // ══════════════════════════════════════════════════════════════════════

    public static String buildPositionAction(
            String symbol, String action, String reason,
            Double currentPrice, Double entryPrice, Double pnlPct,
            List<String> signals, String switchTo, Double scoreGap,
            Double suggestedAmount, Integer suggestedShares,
            Double suggestedReducePct) {
        String[] meta = actionMeta(action);
        String emoji = meta[0];
        String actionZh = meta[1];
        String reasonText = reasonZh(reason);

        StringBuilder sb = new StringBuilder();
        sb.append(emoji).append(" <b>持倉").append(actionZh).append("｜").append(escapeHtml(symbol)).append("</b>\n");
        sb.append(DIVIDER).append("\n");

        // 動作 + 主要原因
        sb.append("<b>動作</b>　").append(actionZh);
        if (!reasonText.isEmpty()) sb.append("（").append(escapeHtml(reasonText)).append("）");
        sb.append("\n");

        // 量價數據
        sb.append("\n📊 <b>數據</b>\n");
        sb.append("　現價　<code>").append(fmtPrice(currentPrice)).append("</code>\n");
        if (entryPrice != null) sb.append("　成本　<code>").append(fmtPrice(entryPrice)).append("</code>\n");
        if (pnlPct != null) {
            sb.append("　損益　<code>").append(fmtPct(pnlPct)).append("</code> ").append(pnlEmoji(pnlPct)).append("\n");
        }

        // 訊號
        if (signals != null && !signals.isEmpty()) {
            sb.append("\n💡 <b>訊號</b>\n");
            for (String s : signals) {
                String zh = signalZh(s);
                if (!zh.isEmpty()) sb.append("　• ").append(escapeHtml(zh)).append("\n");
            }
        }

        // 換股建議
        if (switchTo != null && !switchTo.isBlank()) {
            sb.append("\n🔄 <b>換股建議</b>\n");
            sb.append("　目標　<b>").append(escapeHtml(switchTo)).append("</b>");
            if (scoreGap != null) sb.append("（評分高 ").append(String.format("%+.2f", scoreGap)).append("）");
            sb.append("\n");
        }

        // 資金配置（v2.11）
        boolean hasAlloc = suggestedAmount != null && suggestedAmount > 0
                || suggestedShares != null && suggestedShares > 0
                || suggestedReducePct != null;
        if (hasAlloc) {
            sb.append("\n💰 <b>建議配置</b>\n");
            if (suggestedAmount != null && suggestedAmount > 0) {
                sb.append("　金額　<code>").append(fmtMoney(suggestedAmount)).append("</code> 元\n");
            }
            if (suggestedShares != null && suggestedShares > 0) {
                sb.append("　股數　<code>").append(String.format("%,d", suggestedShares)).append("</code> 股\n");
            }
            if (suggestedReducePct != null) {
                sb.append("　減碼　<code>").append(String.format("%.0f%%", suggestedReducePct * 100)).append("</code>\n");
            }
        }

        // 行動建議
        sb.append("\n");
        sb.append(actionAdvice(action));
        sb.append("\n");

        sb.append("\n<i>來源：Trading System</i>");
        return sb.toString();
    }

    /** 行動建議文案。 */
    private static String actionAdvice(String action) {
        String a = action == null ? "" : action.toUpperCase();
        return switch (a) {
            case "EXIT"        -> "📌 <b>建議</b>　優先處理出場";
            case "REDUCE"      -> "📌 <b>建議</b>　考慮減碼一部分以降低風險";
            case "ADD"         -> "📌 <b>建議</b>　可評估加碼，注意風險控制";
            case "SWITCH_HINT" -> "📌 <b>建議</b>　評估是否換股至更強候選";
            default            -> "📌 <b>建議</b>　依持倉計畫處理";
        };
    }

    // ══════════════════════════════════════════════════════════════════════
    // Position Alert：較簡潔的「狀態變動警報」
    // ══════════════════════════════════════════════════════════════════════

    public static String buildPositionAlert(
            String symbol, String status, String reason,
            Double currentPrice, Double entryPrice, Double pnlPct) {
        String[] meta = actionMeta(status);
        StringBuilder sb = new StringBuilder();
        sb.append(meta[0]).append(" <b>持倉警報｜").append(escapeHtml(symbol)).append("</b>\n");
        sb.append(DIVIDER).append("\n");
        sb.append("<b>狀態</b>　").append(escapeHtml(meta[1]));
        String reasonText = reasonZh(reason);
        if (!reasonText.isEmpty()) sb.append("（").append(escapeHtml(reasonText)).append("）");
        sb.append("\n\n");
        sb.append("📊 <b>數據</b>\n");
        sb.append("　現價　<code>").append(fmtPrice(currentPrice)).append("</code>\n");
        if (entryPrice != null) sb.append("　成本　<code>").append(fmtPrice(entryPrice)).append("</code>\n");
        if (pnlPct != null) {
            sb.append("　損益　<code>").append(fmtPct(pnlPct)).append("</code> ").append(pnlEmoji(pnlPct)).append("\n");
        }
        sb.append("\n<i>來源：Trading System</i>");
        return sb.toString();
    }

    // ══════════════════════════════════════════════════════════════════════
    // Buy Allocation（新倉資金建議）
    // ══════════════════════════════════════════════════════════════════════

    public static String buildBuyAllocation(
            String symbol, String mode, String bucket,
            Double score, Double entryPrice, Double stopLoss,
            Double suggestedAmount, Integer suggestedShares,
            Double riskPerShare, Double maxLossAmount) {
        StringBuilder sb = new StringBuilder();
        sb.append("💰 <b>進場資金建議｜").append(escapeHtml(symbol)).append("</b>\n");
        sb.append(DIVIDER).append("\n");
        if (mode != null) sb.append("<b>模式</b>　").append(escapeHtml(mode)).append("\n");
        if (bucket != null) sb.append("<b>分類</b>　").append(escapeHtml(bucket)).append("\n");
        if (score != null) sb.append("<b>評分</b>　<code>").append(String.format("%.1f", score)).append("</code>\n");

        sb.append("\n📊 <b>價格</b>\n");
        if (entryPrice != null) sb.append("　進場　<code>").append(fmtPrice(entryPrice)).append("</code>\n");
        if (stopLoss != null) sb.append("　停損　<code>").append(fmtPrice(stopLoss)).append("</code>\n");

        sb.append("\n💰 <b>建議配置</b>\n");
        if (suggestedAmount != null) sb.append("　金額　<code>").append(fmtMoney(suggestedAmount)).append("</code> 元\n");
        if (suggestedShares != null) sb.append("　股數　<code>").append(String.format("%,d", suggestedShares)).append("</code> 股\n");
        if (riskPerShare != null) sb.append("　每股風險　<code>").append(fmtPrice(riskPerShare)).append("</code>\n");
        if (maxLossAmount != null) sb.append("　最大虧損　<code>").append(fmtMoney(maxLossAmount)).append("</code> 元\n");

        sb.append("\n📌 <b>建議</b>　依此金額／股數進場，並嚴守停損。\n");
        sb.append("\n<i>來源：Trading System</i>");
        return sb.toString();
    }
}
