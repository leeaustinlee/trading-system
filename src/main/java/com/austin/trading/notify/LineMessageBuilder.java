package com.austin.trading.notify;

import com.austin.trading.dto.response.FinalDecisionResponse;
import com.austin.trading.dto.response.FinalDecisionSelectedStockResponse;
import com.austin.trading.dto.response.HourlyGateDecisionResponse;
import com.austin.trading.dto.response.MonitorDecisionResponse;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * 將各決策結果轉為 LINE Notify 通知文字。
 * 規格：圖示分段、繁體中文行動語句、署名 Codex。
 * 禁止直接暴露程式參數名（market_grade, decision_lock 等）。
 */
public class LineMessageBuilder {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    private LineMessageBuilder() {}

    // ── 08:30 盤前通知 ─────────────────────────────────────────────────────────

    public static String buildPremarket(String marketSummary, String topCandidates, LocalDate date) {
        return "\n📋 【盤前情報】" + date + "\n" +
               "━━━━━━━━━━━━━━\n" +
               "📊 全球市場概況\n" + marketSummary + "\n\n" +
               "🔵 今日候選關注\n" + topCandidates + "\n\n" +
               "⏰ 09:30 最終決策請等待確認\n" +
               "來源：Codex";
    }

    // ── 09:30 最終決策通知 ─────────────────────────────────────────────────────

    public static String buildFinalDecision(FinalDecisionResponse decision, LocalDate date) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n🎯 【09:30 最終決策】").append(date).append("\n");
        sb.append("━━━━━━━━━━━━━━\n");

        String decisionLabel = switch (decision.decision()) {
            case "ENTER" -> "✅ 可進場";
            case "WATCH" -> "👀 只觀察";
            case "REST"  -> "🚫 今日休息";
            default      -> decision.decision();
        };
        sb.append("📌 決策：").append(decisionLabel).append("\n");
        sb.append("📝 ").append(decision.summary()).append("\n");

        if (!decision.selectedStocks().isEmpty()) {
            sb.append("\n🔵 入選標的\n");
            for (FinalDecisionSelectedStockResponse s : decision.selectedStocks()) {
                sb.append("  ▶ ").append(s.stockCode()).append(" ").append(s.stockName()).append("\n");
                sb.append("    進場區：").append(s.entryPriceZone()).append("\n");
                if (s.stopLossPrice() != null) {
                    sb.append("    停損：").append(formatPrice(s.stopLossPrice())).append("\n");
                }
                if (s.takeProfit1() != null) {
                    sb.append("    第一停利：").append(formatPrice(s.takeProfit1())).append("\n");
                }
                if (s.takeProfit2() != null) {
                    sb.append("    第二停利：").append(formatPrice(s.takeProfit2())).append("\n");
                }
                if (s.suggestedPositionSize() != null) {
                    sb.append("    建議倉位：")
                      .append(String.format(Locale.ROOT, "%.0f", s.suggestedPositionSize()))
                      .append(" 元\n");
                }
            }
        }

        if (!decision.rejectedReasons().isEmpty()) {
            sb.append("\n🚫 排除原因\n");
            for (String reason : decision.rejectedReasons()) {
                sb.append("  × ").append(reason).append("\n");
            }
        }

        sb.append("\n來源：Codex");
        return sb.toString();
    }

    // ── 每小時 Gate 通知 ───────────────────────────────────────────────────────

    public static String buildHourlyGate(HourlyGateDecisionResponse decision, LocalTime time) {
        String gateLabel = switch (decision.hourlyGate()) {
            case "ON"       -> "🟢 監控開啟";
            case "OFF_SOFT" -> "🟡 暫停監控";
            case "OFF_HARD" -> "🔴 今日停止";
            default         -> decision.hourlyGate();
        };

        return "\n📡 【整點行情閘】" + time.format(TIME_FMT) + "\n" +
               "━━━━━━━━━━━━━━\n" +
               "📊 行情：" + translateGrade(decision.marketGrade()) + "／" + translatePhase(decision.marketPhase()) + "\n" +
               "🔑 監控：" + gateLabel + "\n" +
               "📝 " + decision.summaryForLog() + "\n\n" +
               "來源：Codex";
    }

    // ── 5 分鐘監控通知 ─────────────────────────────────────────────────────────

    public static String buildMonitor(MonitorDecisionResponse decision, LocalTime time) {
        if (!decision.shouldNotify()) return null;

        return "\n⚡ 【盤中監控】" + time.format(TIME_FMT) + "\n" +
               "━━━━━━━━━━━━━━\n" +
               "📊 行情：" + translateGrade(decision.marketGrade()) + "\n" +
               "📌 " + decision.summaryForLog() + "\n\n" +
               "來源：Codex";
    }

    // ── 14:00 交易檢討 ─────────────────────────────────────────────────────────

    public static String buildReview1400(String reviewSummary, LocalDate date) {
        return "\n📋 【14:00 交易檢討】" + date + "\n" +
               "━━━━━━━━━━━━━━\n" +
               reviewSummary + "\n\n" +
               "來源：Codex";
    }

    // ── 15:30 盤後通知 ─────────────────────────────────────────────────────────

    public static String buildPostmarket(String candidates, LocalDate date) {
        return "\n🌙 【15:30 盤後候選】" + date + "\n" +
               "━━━━━━━━━━━━━━\n" +
               "🔵 明日觀察名單\n" + candidates + "\n\n" +
               "⚠️ 以上為盤後候選，明日 09:30 才做最終決策\n" +
               "來源：Codex";
    }

    // ── 工具 ─────────────────────────────────────────────────────────────────

    private static String translateGrade(String grade) {
        return switch (grade == null ? "" : grade) {
            case "A" -> "A（主升段）";
            case "B" -> "B（強勢震盪）";
            case "C" -> "C（震盪出貨）";
            default  -> grade;
        };
    }

    private static String translatePhase(String phase) {
        return switch (phase == null ? "" : phase) {
            case "EARLY_WASH" -> "開盤洗盤期";
            case "MAIN_RISE"  -> "主升發動期";
            case "HIGH_CHOP"  -> "高檔震盪期";
            case "DISTRIBUTION" -> "出貨鈍化期";
            default           -> phase == null ? "未知" : phase;
        };
    }

    private static String formatPrice(double price) {
        return String.format(Locale.ROOT, "%.2f", price);
    }
}
