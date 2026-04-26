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
        // v2.3: 若含 Momentum 追價單，標題加註
        boolean hasMomentum = decision.selectedStocks().stream()
                .anyMatch(s -> "MOMENTUM_CHASE".equalsIgnoreCase(s.strategyType()));
        boolean hasSetup = decision.selectedStocks().stream()
                .anyMatch(s -> s.strategyType() == null || "SETUP".equalsIgnoreCase(s.strategyType()));
        String titleSuffix;
        if (hasMomentum && hasSetup)       titleSuffix = "（含追價單）";
        else if (hasMomentum)              titleSuffix = "（追價單）";
        else                                titleSuffix = "";
        sb.append("【09:30 今日操作").append(titleSuffix).append("】").append(date).append("\n\n");
        sb.append("🎯 決策：").append(decisionText(decision.decision())).append("\n");
        sb.append("📌 結論：").append(clean(decision.summary())).append("\n");

        // WAIT / REST：在結論下立刻列出 Top 2 阻擋原因，方便 Austin 一眼判斷
        String dec = decision.decision() == null ? "" : decision.decision().toUpperCase(Locale.ROOT);
        if (("WAIT".equals(dec) || "REST".equals(dec)) && !decision.rejectedReasons().isEmpty()) {
            List<String> topReasons = pickTopBlockReasons(decision.rejectedReasons(), 2);
            if (!topReasons.isEmpty()) {
                sb.append("\n🚫 主要阻擋原因\n");
                int idx = 1;
                for (String r : topReasons) {
                    sb.append(idx++).append(". ").append(clean(r)).append("\n");
                }
            }
        }

        if (!decision.selectedStocks().isEmpty()) {
            sb.append("\n✅ 可執行標的\n");
            int count = 0;
            for (FinalDecisionSelectedStockResponse s : decision.selectedStocks()) {
                if (++count > 3) break; // v2.3: 最多 3 檔（Setup 2 + Momentum 1 的合併上限）
                String tag = "MOMENTUM_CHASE".equalsIgnoreCase(s.strategyType())
                        ? "[Momentum] "
                        : "[SETUP]    ";
                sb.append(tag).append(s.stockCode()).append(" ").append(s.stockName());
                if (s.momentumScore() != null) {
                    sb.append(String.format("（score=%.1f）", s.momentumScore()));
                }
                sb.append("\n");
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
            if (hasMomentum) {
                sb.append("⚠️ Momentum 追價提醒：倉位已壓低、停損收緊、持有上限 3 日\n\n");
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

    /**
     * v2.11 Capital Allocation：新倉進場建議金額 + 股數。
     */
    public static String buildBuyAllocation(String symbol, String mode, String bucket,
                                             Double score, Double entryPrice, Double stopLoss,
                                             Double suggestedAmount, Integer suggestedShares,
                                             Double riskPerShare, Double maxLossAmount) {
        return String.join("\n",
                "【進場資金建議】" + symbol,
                "",
                "模式：" + safeStr(mode),
                "建議金額：" + formatAmount(suggestedAmount),
                "建議股數：" + (suggestedShares == null ? "N/A" : suggestedShares + " 股"),
                "進場價：" + formatNullable(entryPrice) +
                        "｜停損：" + formatNullable(stopLoss),
                "單股風險：" + formatNullable(riskPerShare) +
                        "｜最大可承受損失：約 " + formatAmount(maxLossAmount),
                "",
                "📌 原因",
                safeStr(bucket) + (score == null ? "" : String.format(Locale.ROOT, " / score %.2f", score)),
                SOURCE);
    }

    /** 資金不足 / 風險封鎖時的 LINE 提示（僅 trace 用，可選發）。 */
    public static String buildAllocationBlocked(String symbol, String action, java.util.List<String> reasons) {
        return String.join("\n",
                "【配置封鎖】" + symbol,
                "",
                "動作：" + safeStr(action),
                "原因：" + (reasons == null || reasons.isEmpty() ? "—" : String.join("、", reasons)),
                "",
                "📌 建議",
                "暫不入場，觀察其他機會。",
                SOURCE);
    }

    private static String formatAmount(Double value) {
        if (value == null) return "N/A";
        return String.format(Locale.ROOT, "%,.0f", value);
    }

    private static String safeStr(String v) {
        return v == null || v.isBlank() ? "—" : v;
    }

    /**
     * v2.10 PositionManagement LINE 訊息：HOLD/ADD/REDUCE/EXIT/SWITCH_HINT 專用。
     * signals 與 reason 已是繁中語意 key，訊息層只做文案包裝。
     * v2.11 擴充：附帶 suggestedAmount / suggestedShares / suggestedReducePct。
     */
    public static String buildPositionAction(String symbol, String action, String reason,
                                              Double currentPrice, Double entryPrice,
                                              Double pnlPct, java.util.List<String> signals,
                                              String switchTo, Double scoreGap) {
        return buildPositionAction(symbol, action, reason, currentPrice, entryPrice, pnlPct,
                signals, switchTo, scoreGap, null, null, null);
    }

    public static String buildPositionAction(String symbol, String action, String reason,
                                              Double currentPrice, Double entryPrice,
                                              Double pnlPct, java.util.List<String> signals,
                                              String switchTo, Double scoreGap,
                                              Double suggestedAmount, Integer suggestedShares,
                                              Double suggestedReducePct) {
        String title = switch (action) {
            case "ADD" -> "【持倉加碼提示】" + symbol;
            case "REDUCE" -> "【持倉減碼提示】" + symbol;
            case "EXIT" -> "【持倉出場警報】" + symbol;
            case "SWITCH_HINT" -> "【換股提示】" + symbol + (switchTo == null ? "" : " → " + switchTo);
            default -> "【持倉提醒】" + symbol;
        };
        String state = switch (action) {
            case "ADD" -> "續強";
            case "REDUCE" -> "動能轉弱";
            case "EXIT" -> "觸發停損 / 移動停利";
            case "SWITCH_HINT" -> "同主題更強新候選";
            default -> "觀察中";
        };
        String actionHint = switch (action) {
            case "ADD" -> "可考慮加碼 0.3 倉。";
            case "REDUCE" -> "可考慮減碼，保留核心倉。";
            case "EXIT" -> "優先處理出場。";
            case "SWITCH_HINT" -> scoreGap == null
                    ? "可評估減碼本檔，切換部分資金至新候選。"
                    : String.format(Locale.ROOT, "新候選高出 %.2f 分，可評估減碼切換。", scoreGap);
            default -> "先觀察，不追價。";
        };
        String signalLine = (signals == null || signals.isEmpty())
                ? "—" : String.join("、", signals);

        // v2.11：依 action 決定是否附帶金額 / 減碼比例
        String sizingLine = null;
        if (("ADD".equals(action) || "SWITCH_HINT".equals(action))
                && suggestedAmount != null && suggestedAmount > 0) {
            sizingLine = "建議金額：" + formatAmount(suggestedAmount)
                    + (suggestedShares == null ? "" : "（約 " + suggestedShares + " 股）");
        } else if ("REDUCE".equals(action) && suggestedReducePct != null) {
            sizingLine = "建議減碼：" + String.format(Locale.ROOT, "%.0f%%", suggestedReducePct * 100);
        }

        java.util.List<String> lines = new java.util.ArrayList<>();
        lines.add(title);
        lines.add("");
        lines.add("狀態：" + state);
        lines.add("現價：" + formatNullable(currentPrice) + "｜成本：" + formatNullable(entryPrice) +
                "｜損益：" + (pnlPct == null ? "N/A" : String.format(Locale.ROOT, "%+.2f%%", pnlPct)));
        lines.add("原因：" + clean(reason));
        lines.add("訊號：" + signalLine);
        if (sizingLine != null) lines.add(sizingLine);
        lines.add("");
        lines.add("📌 建議");
        lines.add(actionHint);
        lines.add(SOURCE);
        return String.join("\n", lines);
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

    /**
     * 從 rejectedReasons 挑出 Top N 阻擋原因。
     *
     * <p>優先順序（最高至最低）：</p>
     * <ol>
     *   <li>hard gate（REGIME_BLOCKED / market_grade=C / decision_lock / portfolio gate / cooldown）</li>
     *   <li>Codex bucket / Codex 相關（CODEX_NOT_READY / CODEX_MISSING / CODEX_*）</li>
     *   <li>VETO_*（hard veto 與 score divergence）</li>
     *   <li>priceGate（PRICE_GATE / belowOpen / belowPrevClose / VWAP）</li>
     *   <li>allocation（CASH_RESERVE / RISK_BLOCK / EXPOSURE_LIMIT）</li>
     *   <li>AI_NOT_READY / AI_TIMEOUT</li>
     *   <li>其餘維持原順序</li>
     * </ol>
     */
    static List<String> pickTopBlockReasons(List<String> reasons, int limit) {
        if (reasons == null || reasons.isEmpty() || limit <= 0) return List.of();
        java.util.LinkedHashMap<String, Integer> ranked = new java.util.LinkedHashMap<>();
        for (String raw : reasons) {
            if (raw == null || raw.isBlank()) continue;
            String r = raw.trim();
            String upper = r.toUpperCase(Locale.ROOT);
            // v2.15：priority scale ×10，方便插入新 tier（CHASED_HIGH 介於 hard gate 與 Codex 之間）
            int priority;
            if (upper.contains("REGIME_BLOCKED")
                    || upper.contains("MARKET_GRADE=C")
                    || upper.contains("DECISION_LOCK")
                    || upper.contains("PORTFOLIO")
                    || upper.contains("COOLDOWN")
                    || upper.contains("MAX_POSITIONS")
                    || upper.contains("PREMARKET_BIAS_ONLY")
                    || upper.contains("LATE_SESSION_FORCE_REST")) {
                priority = 10;
            } else if (upper.contains("CHASED_HIGH_BLOCK")
                    || upper.contains("CHASED_HIGH_WARN")) {
                // v2.15：追高攔截，hard gate 之後、Codex 之前
                priority = 15;
            } else if (upper.contains("CODEX_NOT_READY")
                    || upper.contains("CODEX_MISSING")
                    || upper.startsWith("CODEX_")
                    || upper.contains("BUCKET")) {
                priority = 20;
            } else if (upper.startsWith("VETO_")
                    || upper.contains("SCORE_DIVERGENCE")
                    || upper.contains("HARD_VETO")) {
                priority = 30;
            } else if (upper.contains("PRICEGATE")
                    || upper.contains("PRICE_GATE")
                    || upper.contains("BELOWOPEN")
                    || upper.contains("BELOWPREVCLOSE")
                    || upper.contains("VWAP")
                    || upper.contains("WAIT_CONFIRMATION")) {
                priority = 40;
            } else if (upper.contains("CASH_RESERVE")
                    || upper.contains("RISK_BLOCK")
                    || upper.contains("EXPOSURE_LIMIT")
                    || upper.contains("MIN_TRADE_AMOUNT")
                    || upper.contains("ZERO_SHARES")) {
                priority = 50;
            } else if (upper.contains("AI_NOT_READY")
                    || upper.contains("AI_TIMEOUT")) {
                priority = 60;
            } else {
                priority = 90;
            }
            ranked.merge(r, priority, Math::min);
        }
        return ranked.entrySet().stream()
                .sorted(java.util.Map.Entry.<String, Integer>comparingByValue())
                .map(java.util.Map.Entry::getKey)
                .limit(limit)
                .toList();
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
