package com.austin.trading.notify;

import com.austin.trading.dto.response.FinalDecisionResponse;
import com.austin.trading.dto.response.FinalDecisionSelectedStockResponse;
import com.austin.trading.dto.response.HourlyGateDecisionResponse;
import com.austin.trading.dto.response.MonitorDecisionResponse;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

public class LineMessageBuilder {

    public static final String SOURCE = "來源：Trading System";
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    private LineMessageBuilder() {}

    public static String buildPremarket(String marketSummary, String topCandidates, LocalDate date) {
        return String.join("\n",
                "【盤前分析】" + date,
                "",
                "📊 盤前重點",
                clean(marketSummary),
                "",
                "🎯 今日候選",
                clean(topCandidates),
                "",
                "📌 行動",
                "09:30 以前不追價；等開盤方向確認後再決策。",
                SOURCE);
    }

    public static String buildFinalDecision(FinalDecisionResponse decision, LocalDate date) {
        StringBuilder sb = new StringBuilder();
        sb.append("【09:30 今日操作】").append(date).append("\n\n");
        sb.append("🎯 決策：").append(decisionText(decision.decision())).append("\n");
        sb.append("📌 結論：").append(clean(decision.summary())).append("\n");

        if (!decision.selectedStocks().isEmpty()) {
            sb.append("\n✅ 可執行標的\n");
            int count = 0;
            for (FinalDecisionSelectedStockResponse s : decision.selectedStocks()) {
                if (++count > 2) break;
                sb.append(s.stockCode()).append(" ").append(s.stockName()).append("\n");
                sb.append("進場：").append(entryText(s)).append("\n");
                if (s.stopLossPrice() != null) sb.append("停損：").append(formatPrice(s.stopLossPrice())).append("\n");
                if (s.takeProfit1() != null || s.takeProfit2() != null) {
                    sb.append("停利：")
                            .append(s.takeProfit1() == null ? "N/A" : formatPrice(s.takeProfit1()))
                            .append(" / ")
                            .append(s.takeProfit2() == null ? "N/A" : formatPrice(s.takeProfit2()))
                            .append("\n");
                }
                if (s.riskRewardRatio() != null) sb.append("風報比：").append(formatOne(s.riskRewardRatio())).append("\n");
                if (s.suggestedPositionSize() != null) sb.append("倉位：").append(formatPosition(s.suggestedPositionSize())).append("\n");
                if (s.rationale() != null && !s.rationale().isBlank()) sb.append("理由：").append(clean(s.rationale())).append("\n");
                sb.append("\n");
            }
        }

        if (!decision.rejectedReasons().isEmpty()) {
            sb.append("🚫 不進場原因\n");
            decision.rejectedReasons().stream().limit(3)
                    .forEach(reason -> sb.append("- ").append(clean(reason)).append("\n"));
        }

        String downgradeReason = inferFallbackReason(decision.rejectedReasons());
        if (downgradeReason != null) {
            sb.append("\nAI 狀態：").append(inferAiStatus(downgradeReason)).append("\n");
            sb.append("降級原因：").append(downgradeReason).append("\n");
        }

        sb.append("\n📌 行動指令\n");
        sb.append(switch (decision.decision()) {
            case "ENTER" -> "只做上方條件，未到價不追。";
            case "WATCH" -> "只觀察，等回測不破或轉強再說。";
            case "REST" -> "現在不進場。";
            default -> "依上方條件執行。";
        });
        sb.append("\n").append(SOURCE);
        return sb.toString();
    }

    public static String buildHourlyGate(HourlyGateDecisionResponse decision, LocalTime time) {
        StringBuilder sb = new StringBuilder();
        sb.append("【盤中每小時監控】").append(time.format(TIME_FMT)).append("\n");
        sb.append("五分鐘監控：").append(gateText(decision.hourlyGate())).append("\n\n");
        sb.append("📊 Gate\n");
        sb.append("盤勢：").append(gradeText(decision.marketGrade())).append("\n");
        sb.append("階段：").append(phaseText(decision.marketPhase())).append("\n");
        sb.append("決策：").append(decisionText(decision.decision())).append("\n");
        sb.append("事件：").append(eventText(decision.triggerEvent())).append("\n\n");
        sb.append("📌 判斷\n").append(clean(firstNonBlank(decision.hourlyReason(), decision.summaryForLog()))).append("\n");
        if (decision.reopenConditions() != null && !decision.reopenConditions().isEmpty()) {
            sb.append("\n🔄 重開條件\n");
            decision.reopenConditions().stream().limit(3)
                    .forEach(c -> sb.append("- ").append(clean(c)).append("\n"));
        }
        sb.append("\n🎯 下一輪\n").append(clean(decision.nextCheckFocus())).append("\n");
        sb.append(SOURCE);
        return sb.toString();
    }

    public static String buildMonitor(MonitorDecisionResponse decision, LocalTime time) {
        if (!decision.shouldNotify()) return null;
        return String.join("\n",
                "【5分鐘事件監控】" + time.format(TIME_FMT),
                "監控模式：" + monitorText(decision.monitorMode()),
                "",
                "📊 狀態",
                "盤勢：" + gradeText(decision.marketGrade()),
                "階段：" + phaseText(decision.marketPhase()),
                "決策：" + decisionText(decision.decision()),
                "事件：" + eventText(decision.triggerEvent()),
                "",
                "📌 判斷",
                clean(firstNonBlank(decision.monitorReason(), decision.summaryForLog())),
                "",
                "🎯 下一步",
                clean(decision.nextCheckFocus()),
                SOURCE);
    }

    public static String buildReview1400(String reviewSummary, LocalDate date) {
        return String.join("\n",
                "【今日操作檢討】" + date,
                "",
                clean(reviewSummary),
                "",
                SOURCE);
    }

    public static String buildPostmarket(String candidates, LocalDate date) {
        return String.join("\n",
                "【盤後選股】" + date,
                "",
                "🎯 明日候選",
                clean(candidates),
                "",
                "📌 行動",
                "明日 08:30 先看盤前方向，09:30 再做最終進場決策。",
                SOURCE);
    }

    public static String buildPositionAlert(String symbol, String status, String reason,
                                            Double currentPrice, Double entryPrice, Double pnlPct) {
        String action = switch (status) {
            case "EXIT" -> "出場警報";
            case "WEAKEN" -> "轉弱警報";
            case "TRAIL_UP" -> "移動停利";
            default -> "持倉提醒";
        };
        return String.join("\n",
                "【持倉警報】" + symbol,
                "",
                "狀態：" + action,
                "現價：" + formatNullable(currentPrice) + "｜成本：" + formatNullable(entryPrice),
                "損益：" + (pnlPct == null ? "N/A" : String.format(Locale.ROOT, "%+.2f%%", pnlPct)),
                "原因：" + clean(reason),
                "",
                "📌 行動",
                status.equals("EXIT") ? "請確認券商庫存與即時報價；若仍持有，優先處理出場。" : "先觀察，不追價加碼。",
                SOURCE);
    }

    private static String decisionText(String decision) {
        return switch (decision == null ? "" : decision) {
            case "ENTER" -> "可進場";
            case "WATCH" -> "觀察";
            case "REST" -> "休息";
            default -> clean(decision);
        };
    }

    private static String gateText(String gate) {
        return switch (gate == null ? "" : gate) {
            case "ON" -> "開啟";
            case "OFF_SOFT" -> "暫停";
            case "OFF_HARD" -> "今日停止";
            default -> clean(gate);
        };
    }

    private static String monitorText(String mode) {
        return switch (mode == null ? "" : mode) {
            case "ACTIVE" -> "主動監控";
            case "WATCH" -> "觀察";
            case "OFF" -> "關閉";
            default -> clean(mode);
        };
    }

    private static String gradeText(String grade) {
        return switch (grade == null ? "" : grade) {
            case "A" -> "A 主升盤";
            case "B" -> "B 強勢震盪";
            case "C" -> "C 震盪 / 出貨";
            default -> clean(grade);
        };
    }

    private static String phaseText(String phase) {
        return switch (phase == null ? "" : phase) {
            case "EARLY_WASH", "開盤洗盤期" -> "開盤洗盤期";
            case "MAIN_RISE", "主升發動期" -> "主升發動期";
            case "HIGH_CHOP", "高檔震盪期" -> "高檔震盪期";
            case "DISTRIBUTION", "出貨 / 鈍化期" -> "出貨 / 鈍化期";
            default -> clean(phase);
        };
    }

    private static String eventText(String event) {
        return switch (event == null ? "" : event) {
            case "NONE" -> "無重大事件";
            case "MARKET_UPGRADE" -> "市場轉強";
            case "MARKET_DOWNGRADE" -> "市場轉弱";
            case "MONITOR_ON" -> "開啟監控";
            case "MONITOR_OFF" -> "關閉監控";
            case "REOPEN_READY" -> "接近重開條件";
            case "RISK_ONLY" -> "僅風控";
            case "NO_OPPORTUNITY" -> "無新機會";
            case "ENTRY_READY" -> "接近進場";
            case "BREAKOUT" -> "突破";
            case "PULLBACK_HOLD" -> "回測守住";
            case "STOP_LOSS" -> "停損";
            case "TAKE_PROFIT" -> "停利";
            case "POSITION_MANAGE" -> "持倉管理";
            case "CANDIDATE_CANCEL" -> "候選取消";
            case "WATCHLIST_UPDATE" -> "觀察名單更新";
            default -> clean(event);
        };
    }

    private static String entryText(FinalDecisionSelectedStockResponse s) {
        String type = switch (s.entryType() == null ? "" : s.entryType()) {
            case "PULLBACK" -> "回測進場";
            case "BREAKOUT" -> "突破進場";
            case "REVERSAL" -> "反轉進場";
            default -> clean(s.entryType());
        };
        return (type.isBlank() ? "" : type + " ") + clean(s.entryPriceZone());
    }

    private static String formatPrice(double price) {
        return String.format(Locale.ROOT, "%.2f", price);
    }

    private static String formatNullable(Double price) {
        return price == null ? "N/A" : formatPrice(price);
    }

    private static String formatOne(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static String formatPosition(double value) {
        if (value <= 1.0) return String.format(Locale.ROOT, "%.0f%%", value * 100);
        return String.format(Locale.ROOT, "%.0f", value);
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    private static String inferFallbackReason(List<String> reasons) {
        if (reasons == null || reasons.isEmpty()) return null;
        for (String reason : reasons) {
            String code = clean(reason);
            if ("CODEX_MISSING".equals(code)
                    || "AI_NOT_READY".equals(code)
                    || "AI_TIMEOUT".equals(code)) {
                return code;
            }
        }
        return null;
    }

    private static String inferAiStatus(String fallbackReason) {
        if ("CODEX_MISSING".equals(fallbackReason)) return "PARTIAL_AI_READY";
        return "AI_NOT_READY";
    }

    private static String clean(String text) {
        return text == null ? "" : text.trim();
    }
}
