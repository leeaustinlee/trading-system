package com.austin.trading.engine;

import com.austin.trading.dto.request.HourlyGateEvaluateRequest;
import com.austin.trading.dto.response.HourlyGateDecisionResponse;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

@Component
public class HourlyGateEngine {

    public HourlyGateDecisionResponse evaluate(HourlyGateEvaluateRequest request) {
        String marketGrade = normalize(request.marketGrade());
        String decision = normalize(request.decision());
        String prevLock = normalizeOrDefault(request.previousDecisionLock(), "NONE");

        String timeDecay = resolveTimeDecay(request.evaluationTime());
        boolean forceHardOffByTime = "LATE".equals(timeDecay) && !request.hasPosition() && !"A".equals(marketGrade);
        boolean earlyOrMid = "EARLY".equals(timeDecay) || "MID".equals(timeDecay);

        // v2.4：decisionLock 只在真正的硬風控情境才 LOCKED，
        //       盤前 / 早盤 B 市場 + decision=REST 不直接鎖死整天。
        String decisionLock;
        if ("C".equals(marketGrade)) {
            decisionLock = "LOCKED";                        // 市場 C 硬鎖
        } else if (forceHardOffByTime) {
            decisionLock = "LOCKED";                        // 10:30 後非 A 且無持倉
        } else if (!request.hasPosition() && !request.hasCandidate() && !earlyOrMid) {
            decisionLock = "LOCKED";                        // 無持倉無候選且已進 LATE 才鎖
        } else if ("LOCKED".equals(prevLock) && ("A".equals(marketGrade) || "B".equals(marketGrade)) && request.hasCriticalEvent()) {
            decisionLock = "RELEASED";
        } else {
            decisionLock = "NONE";                          // 盤前 / 早盤 / B 市場 / REST 全走 NONE
        }

        // hourlyGate：early/mid 時最多 OFF_SOFT；只有市場 C / forceHardOffByTime 才 OFF_HARD
        String hourlyGate;
        if (forceHardOffByTime || "C".equals(marketGrade)
                || ("LOCKED".equals(decisionLock) && !request.hasPosition())) {
            hourlyGate = "OFF_HARD";
        } else if ("B".equals(marketGrade) && !request.hasPosition() && !request.hasCriticalEvent()) {
            hourlyGate = "OFF_SOFT";
        } else {
            hourlyGate = "ON";
        }

        boolean shouldRun5m = "ON".equals(hourlyGate) && (request.hasPosition() || request.hasCandidate()) && !"LOCKED".equals(decisionLock);

        String triggerEvent = resolveTriggerEvent(request, marketGrade, hourlyGate, decisionLock);
        boolean shouldNotify = shouldNotify(request, marketGrade, decision, hourlyGate, triggerEvent);

        String marketPhase = resolveMarketPhase(marketGrade, timeDecay);
        String hourlyReason = buildHourlyReason(hourlyGate, forceHardOffByTime, marketGrade, decisionLock);
        String nextFocus = "A".equals(marketGrade)
                ? "確認候選是否滿足進場條件與突破有效性。"
                : "只追蹤市場是否升級、主流是否重新一致。";

        List<String> reopenConditions = new ArrayList<>();
        reopenConditions.add("台積電止跌回升並重新帶動市場");
        reopenConditions.add("主流族群重新一致且強勢股續強");
        reopenConditions.add("市場等級升回 B 或 A 並出現洗盤轉強");
        reopenConditions.add("若有持倉，接近停損/停利管理點時重啟監控");

        String summary = "grade=" + marketGrade
                + " hourly_gate=" + hourlyGate
                + " decision=" + decision
                + " trigger=" + triggerEvent
                + " notify=" + shouldNotify
                + " run_5m=" + shouldRun5m
                + " lock=" + decisionLock
                + " stage=" + timeDecay;

        return new HourlyGateDecisionResponse(
                LocalDateTime.now(),
                marketGrade,
                marketPhase,
                decision,
                hourlyGate,
                shouldRun5m,
                shouldNotify,
                triggerEvent,
                hourlyReason,
                nextFocus,
                reopenConditions,
                decisionLock,
                10,
                normalizeOrDefault(request.previousEventType(), "NONE"),
                timeDecay,
                summary
        );
    }

    private String resolveTriggerEvent(HourlyGateEvaluateRequest request, String grade, String gate, String decisionLock) {
        String prevGrade = normalizeOrDefault(request.previousMarketGrade(), grade);
        String prevGate = normalizeOrDefault(request.previousHourlyGate(), gate);

        if (isUpgrade(prevGrade, grade)) {
            return "MARKET_UPGRADE";
        }
        if (isDowngrade(prevGrade, grade)) {
            return "MARKET_DOWNGRADE";
        }
        if (!prevGate.equals(gate) && "ON".equals(gate) && "RELEASED".equals(decisionLock)) {
            return "REOPEN_READY";
        }
        if (!request.hasPosition() && !request.hasCandidate()) {
            return "NO_OPPORTUNITY";
        }
        return "NONE";
    }

    private boolean shouldNotify(HourlyGateEvaluateRequest request, String grade, String decision, String gate, String triggerEvent) {
        String prevGrade = normalizeOrDefault(request.previousMarketGrade(), grade);
        String prevDecision = normalizeOrDefault(request.previousDecision(), decision);
        String prevGate = normalizeOrDefault(request.previousHourlyGate(), gate);

        return !prevGrade.equals(grade)
                || !prevDecision.equals(decision)
                || !prevGate.equals(gate)
                || "MARKET_UPGRADE".equals(triggerEvent)
                || "MARKET_DOWNGRADE".equals(triggerEvent)
                || "REOPEN_READY".equals(triggerEvent);
    }

    private String resolveTimeDecay(LocalTime evaluationTime) {
        LocalTime now = evaluationTime == null ? LocalTime.now() : evaluationTime;
        if (now.isBefore(LocalTime.of(10, 0))) {
            return "EARLY";
        }
        if (now.isBefore(LocalTime.of(10, 30))) {
            return "MID";
        }
        return "LATE";
    }

    private String resolveMarketPhase(String marketGrade, String timeDecay) {
        if ("EARLY".equals(timeDecay)) {
            return "開盤洗盤期";
        }
        if ("A".equals(marketGrade)) {
            return "主升發動期";
        }
        if ("B".equals(marketGrade)) {
            return "高檔震盪期";
        }
        return "出貨 / 鈍化期";
    }

    private String buildHourlyReason(String hourlyGate, boolean forceHardOffByTime, String marketGrade, String decisionLock) {
        if (forceHardOffByTime) {
            return "10:30 後無持倉且市場非 A，強制休息並停止高頻監控。";
        }
        if ("OFF_HARD".equals(hourlyGate)) {
            return "市場風險偏高或決策鎖啟用，今日原則停止五分鐘監控。";
        }
        if ("OFF_SOFT".equals(hourlyGate)) {
            return "市場仍在震盪，暫停高頻監控，等待結構改善。";
        }
        if ("RELEASED".equals(decisionLock)) {
            return "市場條件改善且解鎖，恢復五分鐘監控。";
        }
        return "市場允許監控，維持五分鐘事件追蹤。";
    }

    private boolean isUpgrade(String prev, String curr) {
        return rank(curr) > rank(prev);
    }

    private boolean isDowngrade(String prev, String curr) {
        return rank(curr) < rank(prev);
    }

    private int rank(String grade) {
        return switch (normalize(grade)) {
            case "A" -> 3;
            case "B" -> 2;
            default -> 1;
        };
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase();
    }

    private String normalizeOrDefault(String value, String fallback) {
        String normalized = normalize(value);
        return normalized.isEmpty() ? normalize(fallback) : normalized;
    }
}
