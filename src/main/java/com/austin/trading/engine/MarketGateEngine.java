package com.austin.trading.engine;

import com.austin.trading.dto.request.MarketGateEvaluateRequest;
import com.austin.trading.dto.response.MarketGateDecisionResponse;
import org.springframework.stereotype.Component;

import java.time.LocalTime;

@Component
public class MarketGateEngine {

    public MarketGateDecisionResponse evaluate(MarketGateEvaluateRequest request) {
        int score = 0;
        if (request.tsmcTrendUp()) {
            score += 2;
        }
        if (request.sectorsAligned()) {
            score += 2;
        }
        if (request.leadersStrong()) {
            score += 2;
        }
        if (request.washoutRebound()) {
            score += 1;
        }
        if (request.nearHighNotBreak()) {
            score -= 1;
        }
        if (request.blowoffTopSignal()) {
            score -= 3;
        }

        String marketGrade;
        String decision;
        boolean allowTrade;
        if (request.blowoffTopSignal() || score <= 1) {
            marketGrade = "C";
            decision = "REST";
            allowTrade = false;
        } else if (score >= 5) {
            marketGrade = "A";
            decision = "ENTER";
            allowTrade = true;
        } else {
            marketGrade = "B";
            decision = "WATCH";
            allowTrade = false;
        }

        String marketPhase = resolveMarketPhase(request, marketGrade);
        String reason = buildReason(request, marketGrade, score);

        return new MarketGateDecisionResponse(
                marketGrade,
                marketPhase,
                decision,
                allowTrade,
                score,
                reason
        );
    }

    private String resolveMarketPhase(MarketGateEvaluateRequest request, String marketGrade) {
        LocalTime now = request.evaluationTime() == null ? LocalTime.now() : request.evaluationTime();
        if (now.isBefore(LocalTime.of(9, 15))) {
            return "開盤洗盤期";
        }
        if ("A".equals(marketGrade)) {
            return "主升發動期";
        }
        if ("B".equals(marketGrade) || request.nearHighNotBreak()) {
            return "高檔震盪期";
        }
        return "出貨 / 鈍化期";
    }

    private String buildReason(MarketGateEvaluateRequest request, String marketGrade, int score) {
        if ("A".equals(marketGrade)) {
            return "台積電與主流族群同步，強勢股續強，市場可交易。score=" + score;
        }
        if ("B".equals(marketGrade)) {
            return "市場仍有強勢但結構分歧，僅觀察不追價。score=" + score;
        }
        if (request.blowoffTopSignal()) {
            return "出現爆量開高走低訊號，強制休息。score=" + score;
        }
        return "市場轉弱或不確定性高，先休息。score=" + score;
    }
}
