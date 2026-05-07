package com.austin.trading.notify;

import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts verbose Claude/Codex/Java artifacts into short Telegram decision messages.
 * This layer intentionally hides tables, veto dumps, scores, ranks, debug traces, and long reasoning.
 */
@Service
public class TradingNotificationDecisionFormatter {

    private static final Pattern STOCK = Pattern.compile("(?<!\\d)(\\d{4,5}[A-Z]?)(?![\\d-])");
    private static final Pattern GRADE = Pattern.compile("(?i)(?:market[_ -]?grade|等級|市場)\\s*[:=：]?\\s*([ABC])");
    private static final Pattern STOP = Pattern.compile("(?i)(?:suggestedStop|stop|停損)\\s*[:=：]?\\s*([0-9]+(?:\\.[0-9]+)?)");
    private static final Pattern TP = Pattern.compile("(?i)(?:suggestedTakeProfit|takeProfit|tp|停利)\\s*[:=：]?\\s*([0-9]+(?:\\.[0-9]+)?)");

    public String format(String jobType, String rawMessage, LocalDate date) {
        String type = normalizeType(jobType);
        String clean = sanitize(rawMessage);
        return limitLines(switch (type) {
            case "PREMARKET" -> premarket(clean);
            case "OPENING", "FINAL_DECISION" -> opening(clean);
            case "MIDDAY" -> midday(clean);
            case "POSTMARKET", "T86_TOMORROW" -> postmarket(clean);
            case "POSITION_REVIEW" -> positionReview(clean);
            case "NEXT_DAY_STRATEGY", "TOMORROW_PLAN" -> nextDayStrategy(clean);
            default -> fallback(clean);
        });
    }

    private String premarket(String clean) {
        String grade = grade(clean);
        return String.join("\n",
                "🌅 盤前策略",
                "",
                "📊 市場",
                "- 等級：" + grade,
                "- 判斷：" + marketTone(clean, grade),
                "",
                "🎯 策略",
                "- " + strategyMode(clean, grade),
                "",
                "🔥 主流",
                "- " + themes(clean, 2),
                "",
                "⚠️ 風險",
                "- " + risks(clean, 2),
                "",
                "🤖 AI 判斷",
                "- " + aiSummary(clean, grade));
    }

    private String opening(String clean) {
        String grade = grade(clean);
        List<String> enter = symbolsNearAction(clean, List.of("可進場", "ENTER", "BUY", "進場"), 2);
        List<String> watch = symbolsNearAction(clean, List.of("WATCH", "追蹤", "觀察"), 2);
        return String.join("\n",
                "🕘 開盤決策",
                "",
                "📊 市場：" + grade,
                "",
                "💰 可進場",
                "- " + (enter.isEmpty() ? "無明確進場點" : String.join("、", enter)),
                "",
                "🚀 可追蹤",
                "- " + (watch.isEmpty() ? "暫無" : String.join("、", watch)),
                "",
                "⚠️ 重點",
                "- " + risks(clean, 2));
    }

    private String midday(String clean) {
        String grade = grade(clean);
        List<String> positions = positionActions(clean, 4, false);
        String switchLine = switchLine(clean);
        return String.join("\n",
                "🕛 盤中更新",
                "",
                "📊 狀態",
                "- 市場：" + grade,
                "- 策略：" + strategyMode(clean, grade),
                "",
                "📌 持倉",
                positions.isEmpty() ? "- 無持倉資料" : "- " + String.join("\n- ", positions),
                "",
                "🔄 換股",
                "- " + switchLine,
                "",
                "⚠️ 重點",
                "- " + risks(clean, 3));
    }

    private String postmarket(String clean) {
        String grade = grade(clean);
        return String.join("\n",
                "🌙 盤後分析",
                "",
                "📊 市場",
                "- " + grade + " / " + marketTone(clean, grade),
                "",
                "🔥 主流族群",
                "- " + themes(clean, 3),
                "",
                "🚀 新機會",
                "- " + opportunityDirection(clean),
                "",
                "⚠️ 重點",
                "- " + risks(clean, 2));
    }

    private String positionReview(String clean) {
        List<String> positions = positionActions(clean, 5, true);
        long strong = positions.stream().filter(s -> s.contains("HOLD") && !s.contains("不確定")).count();
        long weak = positions.stream().filter(s -> s.contains("EXIT") || s.contains("REDUCE")).count();
        return String.join("\n",
                "🩺 持倉健檢",
                "",
                "📌 持倉",
                positions.isEmpty() ? "- 無持倉資料" : "- " + String.join("\n- ", positions),
                "",
                "📊 評估",
                "- 強勢股數：" + strong + " / 弱勢股數：" + weak);
    }

    private String nextDayStrategy(String clean) {
        String grade = grade(clean);
        List<String> positions = positionActions(clean, 4, false);
        return String.join("\n",
                "🧭 明日策略",
                "",
                "📊 市場",
                "- " + grade + " / " + marketTone(clean, grade),
                "",
                "🎯 策略",
                "- " + strategyMode(clean, grade),
                "",
                "📌 持倉",
                positions.isEmpty() ? "- 無持倉資料" : "- " + String.join("、", positions),
                "",
                "🔄 換股",
                "- " + switchLine(clean),
                "",
                "💰 明日進場方向",
                "- " + opportunityDirection(clean),
                "",
                "📊 結論",
                "- " + aiSummary(clean, grade));
    }

    private String fallback(String clean) {
        String grade = grade(clean);
        return String.join("\n",
                "🧭 交易通知",
                "",
                "📊 市場",
                "- 等級：" + grade + " / " + marketTone(clean, grade),
                "",
                "📌 結論",
                "- " + aiSummary(clean, grade));
    }

    String sanitize(String raw) {
        if (raw == null || raw.isBlank()) return "";
        List<String> lines = new ArrayList<>();
        for (String line : raw.replace("\r", "").split("\n")) {
            String l = line.strip();
            if (l.isBlank()) continue;
            String lower = l.toLowerCase(Locale.ROOT);
            if (l.startsWith("|") || lower.contains("candidate table") || lower.contains("veto")
                    || lower.contains("排除清單") || lower.contains("debug") || lower.contains("trace")
                    || lower.contains("codex reasoning") || lower.contains("claude thesis")) {
                continue;
            }
            l = l.replaceAll("(?i)score\\s*[:=]?\\s*[-+]?[0-9]+(?:\\.[0-9]+)?", "");
            l = l.replaceAll("(?i)rank\\s*[:=]?\\s*[-+]?[0-9]+", "");
            l = l.replaceAll("(?i)score", "");
            l = l.replaceAll("(?i)rank", "");
            lines.add(l);
        }
        return String.join("\n", lines).strip();
    }

    private String grade(String clean) {
        Matcher m = GRADE.matcher(clean == null ? "" : clean);
        if (m.find()) return m.group(1).toUpperCase(Locale.ROOT);
        String s = clean == null ? "" : clean.toUpperCase(Locale.ROOT);
        if (s.contains("偏空") || s.contains("防守") || s.contains("REST")) return "C";
        if (s.contains("震盪") || s.contains("觀察") || s.contains("WATCH")) return "B";
        if (s.contains("偏多") || s.contains("進攻") || s.contains("ENTER")) return "A";
        return "B";
    }

    private String marketTone(String clean, String grade) {
        if (containsAny(clean, "偏空", "防守", "出貨", "轉弱", "marketBias DEFENSIVE")) return "偏空";
        if (containsAny(clean, "震盪", "觀察", "WATCH", "不確定")) return "震盪";
        if (containsAny(clean, "偏多", "強勢", "主升", "續強")) return "偏多";
        return switch (grade) { case "A" -> "偏多"; case "C" -> "偏空"; default -> "震盪"; };
    }

    private String strategyMode(String clean, String grade) {
        if (containsAny(clean, "防守", "REST", "偏空")) return "防守";
        if (containsAny(clean, "進攻", "可進場", "ENTER", "BUY") || "A".equals(grade)) return "進攻";
        return "觀察";
    }

    private String themes(String clean, int limit) {
        List<String> found = new ArrayList<>();
        addIf(clean, found, "半導體");
        addIf(clean, found, "AI伺服器");
        addIf(clean, found, "AI 伺服器");
        addIf(clean, found, "記憶體");
        addIf(clean, found, "PCB");
        addIf(clean, found, "散熱");
        addIf(clean, found, "電力");
        addIf(clean, found, "金融");
        if (found.isEmpty()) return "主流未明確";
        return String.join("、", found.stream().limit(limit).toList());
    }

    private String risks(String clean, int limit) {
        List<String> found = new ArrayList<>();
        addIf(clean, found, "追高風險");
        addIf(clean, found, "量縮");
        addIf(clean, found, "爆量長黑");
        addIf(clean, found, "高檔風險");
        addIf(clean, found, "題材未確認");
        addIf(clean, found, "外資轉賣");
        addIf(clean, found, "跌破停損");
        addIf(clean, found, "轉弱");
        if (found.isEmpty()) return "無明確新增風險";
        return String.join("；", found.stream().limit(limit).toList());
    }

    private String opportunityDirection(String clean) {
        List<String> found = new ArrayList<>();
        addIf(clean, found, "breakout");
        addIf(clean, found, "continuation");
        addIf(clean, found, "pullback");
        if (found.isEmpty()) return "觀察主流續強，不追無買點標的";
        return String.join(" / ", found.stream().limit(3).toList());
    }

    private String aiSummary(String clean, String grade) {
        if (containsAny(clean, "動能延續", "續強", "主升")) return "動能延續，但仍以風控確認買點。";
        if (containsAny(clean, "題材未確認")) return "題材未確認，先觀察不追價。";
        if (containsAny(clean, "高檔", "追高")) return "高檔風險升高，避免無條件追價。";
        return switch (grade) {
            case "A" -> "市場偏強，可找明確買點。";
            case "C" -> "市場偏弱，優先保護持倉。";
            default -> "訊號未完全一致，先觀察。";
        };
    }

    private List<String> positionActions(String clean, int limit, boolean withStops) {
        List<String> rows = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String line : clean.split("\n")) {
            Matcher sm = STOCK.matcher(line);
            List<SymbolHit> hits = new ArrayList<>();
            while (sm.find()) {
                hits.add(new SymbolHit(sm.group(1), sm.start(), sm.end()));
            }
            for (int i = 0; i < hits.size() && rows.size() < limit; i++) {
                SymbolHit hit = hits.get(i);
                if (!seen.add(hit.symbol())) continue;
                int end = (i + 1 < hits.size()) ? hits.get(i + 1).start() : line.length();
                String segment = line.substring(hit.start(), end);
                String action = actionFor(segment);
                String reason = shortReason(segment);
                String stopOrTp = withStops ? stopOrTp(segment) : "";
                rows.add(hit.symbol() + " → " + action + (stopOrTp.isBlank() ? "" : "（" + stopOrTp + "）") + " → " + reason);
            }
            if (rows.size() >= limit) break;
        }
        return rows;
    }

    private record SymbolHit(String symbol, int start, int end) {}

    private String actionFor(String line) {
        if (containsAny(line, "EXIT", "出場", "跌破停損")) return "EXIT";
        if (containsAny(line, "REDUCE", "減碼")) return "REDUCE";
        if (containsAny(line, "HIGH_HOLD", "HOLD", "續抱")) return "HOLD";
        if (containsAny(line, "WEAK", "轉弱")) return "REDUCE";
        return "HOLD";
    }

    private String shortReason(String line) {
        if (containsAny(line, "STRONG", "強勢", "主升", "續強")) return "強";
        if (containsAny(line, "WEAK", "轉弱", "跌破", "HIGH")) return "弱";
        if (containsAny(line, "NEUTRAL", "不確定", "震盪")) return "不確定";
        return "不確定";
    }

    private String stopOrTp(String line) {
        Matcher stop = STOP.matcher(line);
        if (stop.find()) return "停損 " + stop.group(1);
        Matcher tp = TP.matcher(line);
        if (tp.find()) return "停利 " + tp.group(1);
        return "";
    }

    private String switchLine(String clean) {
        if (containsAny(clean, "無更強標的", "暫無更強標的", "switchPlan 無", "換股建議=0")) return "暫無更強標的";
        Matcher m = Pattern.compile("(?i)(?:switch to|轉進|建議轉進)\\s*[:=：]?\\s*(\\d{4}[A-Z]?)").matcher(clean);
        if (m.find()) return "建議轉進 " + m.group(1);
        if (containsAny(clean, "SWITCH", "PARTIAL_SWITCH", "換股")) {
            List<String> symbols = symbols(clean, 1);
            return symbols.isEmpty() ? "有換股訊號，需人工確認" : "建議轉進 " + symbols.get(0);
        }
        return "暫無更強標的";
    }

    private List<String> symbolsNearAction(String clean, List<String> actions, int limit) {
        List<String> rows = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String line : clean.split("\n")) {
            if (!containsAny(line, actions.toArray(String[]::new))) continue;
            Matcher m = STOCK.matcher(line);
            while (m.find() && rows.size() < limit) {
                if (seen.add(m.group(1))) rows.add(m.group(1));
            }
        }
        return rows;
    }

    private List<String> symbols(String clean, int limit) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = STOCK.matcher(clean);
        while (m.find() && out.size() < limit) out.add(m.group(1));
        return new ArrayList<>(out);
    }

    private String normalizeType(String jobType) {
        if (jobType == null) return "";
        return jobType.trim().toUpperCase(Locale.ROOT);
    }

    private boolean containsAny(String s, String... needles) {
        String hay = s == null ? "" : s.toLowerCase(Locale.ROOT);
        for (String n : needles) {
            if (n != null && hay.contains(n.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    private void addIf(String clean, List<String> found, String token) {
        if (containsAny(clean, token) && found.stream().noneMatch(x -> x.equalsIgnoreCase(token))) {
            found.add(token.replace("AI 伺服器", "AI伺服器"));
        }
    }

    private String limitLines(String message) {
        List<String> lines = new ArrayList<>();
        for (String line : message.split("\n")) {
            if (lines.size() >= 20) break;
            lines.add(line);
        }
        return String.join("\n", lines).stripTrailing();
    }
}
