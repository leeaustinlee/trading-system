package com.austin.trading.engine;

import com.austin.trading.dto.request.FinalDecisionCandidateRequest;
import com.austin.trading.dto.request.FinalDecisionEvaluateRequest;
import com.austin.trading.dto.response.FinalDecisionResponse;
import com.austin.trading.dto.response.FinalDecisionSelectedStockResponse;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Component
public class FinalDecisionEngine {

    public FinalDecisionResponse evaluate(FinalDecisionEvaluateRequest request) {
        String marketGrade = normalize(request.marketGrade());
        String decisionLock = normalize(request.decisionLock());
        String timeDecay = normalize(request.timeDecayStage());
        boolean hasPosition = Boolean.TRUE.equals(request.hasPosition());

        if ("C".equals(marketGrade)) {
            return rest("市場等級為 C，今日建議休息。", List.of("market_grade=C"));
        }
        if ("LOCKED".equals(decisionLock)) {
            return rest("決策鎖啟用，暫不進場。", List.of("decision_lock=LOCKED"));
        }
        if ("LATE".equals(timeDecay) && !"A".equals(marketGrade) && !hasPosition) {
            return rest("10:30 後且市場非 A，無持倉時強制休息。", List.of("late_session_force_rest"));
        }

        List<FinalDecisionCandidateRequest> candidates = request.candidates() == null ? List.of() : request.candidates();
        List<String> rejected = new ArrayList<>();
        List<FinalDecisionCandidateRequest> qualified = new ArrayList<>();

        for (FinalDecisionCandidateRequest c : candidates) {
            String reject = validateCandidate(c, marketGrade);
            if (reject == null) {
                qualified.add(c);
            } else {
                rejected.add(c.stockCode() + " " + reject);
            }
        }

        // 優先依 finalRankScore 降序；無分數時 fallback 至 riskRewardRatio
        qualified.sort(Comparator.comparing((FinalDecisionCandidateRequest c) -> {
            if (c.finalRankScore() != null) return c.finalRankScore().doubleValue();
            return c.riskRewardRatio() == null ? 0.0 : c.riskRewardRatio();
        }).reversed());

        int maxPick = "B".equals(marketGrade) ? 1 : 2;
        List<FinalDecisionSelectedStockResponse> selected = qualified.stream()
                .limit(maxPick)
                .map(this::toSelected)
                .toList();

        if (selected.isEmpty()) {
            return new FinalDecisionResponse(
                    "WATCH",
                    List.of(),
                    rejected,
                    "現在不進場，等待更明確的量價與風報比條件。"
            );
        }

        return new FinalDecisionResponse(
                "ENTER",
                selected,
                rejected,
                "符合條件標的已收斂，請依進場區間與停損紀律執行。"
        );
    }

    private String validateCandidate(FinalDecisionCandidateRequest c, String marketGrade) {
        if (!Boolean.TRUE.equals(c.includeInFinalPlan())) {
            return "不在最終計畫名單";
        }
        if (!Boolean.TRUE.equals(c.mainStream())) {
            return "非主流族群";
        }
        if (Boolean.TRUE.equals(c.falseBreakout())) {
            return "假突破風險";
        }
        if (Boolean.TRUE.equals(c.belowOpen()) || Boolean.TRUE.equals(c.belowPrevClose())) {
            return "跌破開盤或昨收";
        }
        if (Boolean.TRUE.equals(c.nearDayHigh()) && !Boolean.TRUE.equals(c.stopLossReasonable())) {
            return "接近日高且停損不合理";
        }

        double rr = c.riskRewardRatio() == null ? 0.0 : c.riskRewardRatio();
        if (rr < 1.8d) {
            return "風報比低於 1.8";
        }
        if ("B".equals(marketGrade) && rr < 2.0d) {
            return "B 盤風報比低於 2.0";
        }

        String valuation = normalize(c.valuationMode());
        if (!"A".equals(marketGrade) && ("VALUE_HIGH".equals(valuation) || "VALUE_STORY".equals(valuation))) {
            return "高估值/題材估值在非 A 盤不做";
        }

        String entryType = normalize(c.entryType());
        if (!("PULLBACK".equals(entryType) || "BREAKOUT".equals(entryType) || "REVERSAL".equals(entryType))) {
            return "不符合允許進場型態";
        }

        return null;
    }

    private FinalDecisionSelectedStockResponse toSelected(FinalDecisionCandidateRequest c) {
        return new FinalDecisionSelectedStockResponse(
                c.stockCode(),
                c.stockName(),
                normalize(c.entryType()),
                c.entryPriceZone(),
                c.stopLossPrice(),
                c.takeProfit1(),
                c.takeProfit2(),
                c.riskRewardRatio(),
                c.rationale(),
                null,
                null
        );
    }

    private FinalDecisionResponse rest(String summary, List<String> rejectedReasons) {
        return new FinalDecisionResponse("REST", List.of(), rejectedReasons, summary);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
