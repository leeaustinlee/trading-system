package com.austin.trading.engine;

import com.austin.trading.dto.request.StockEvaluateRequest;
import com.austin.trading.dto.request.StopLossTakeProfitEvaluateRequest;
import com.austin.trading.dto.response.StockEvaluateResult;
import com.austin.trading.dto.response.StopLossTakeProfitResponse;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
public class StockEvaluationEngine {

    private final StopLossTakeProfitEngine stopLossTakeProfitEngine;

    public StockEvaluationEngine(StopLossTakeProfitEngine stopLossTakeProfitEngine) {
        this.stopLossTakeProfitEngine = stopLossTakeProfitEngine;
    }

    public StockEvaluateResult evaluate(StockEvaluateRequest request) {
        String marketGrade = normalize(request.marketGrade());
        String entryType   = normalize(request.entryType());

        // 1. 估值模式
        String valuationMode = classifyValuation(request.valuationScore());

        // 2. 計算停損停利
        StopLossTakeProfitResponse sltp = stopLossTakeProfitEngine.evaluate(
                new StopLossTakeProfitEvaluateRequest(
                        request.entryPrice(),
                        request.stopLossPercent(),
                        request.takeProfit1Percent(),
                        request.takeProfit2Percent(),
                        request.volatileStock()
                )
        );

        double stopLoss = sltp.stopLossPrice();
        double tp1      = sltp.takeProfit1();
        double tp2      = sltp.takeProfit2();

        // 3. 風報比（以 TP1 計算主要 RR）
        double riskPer   = request.entryPrice() - stopLoss;
        double rewardPer = tp1 - request.entryPrice();
        double rr = riskPer > 0 ? Math.round(rewardPer / riskPer * 100.0) / 100.0 : 0.0;

        // 4. 進場區間標示（停損~進場價）
        String entryZone = formatZone(stopLoss, request.entryPrice());

        // 5. 是否納入最終計畫
        String rejectReason = checkExclusion(request, marketGrade, entryType, valuationMode, rr);
        boolean include = rejectReason == null;

        String rationale = buildRationale(valuationMode, entryType, rr, sltp.rationale());

        return new StockEvaluateResult(
                request.symbol(),
                valuationMode,
                entryZone,
                stopLoss,
                tp1,
                tp2,
                rr,
                include,
                rejectReason,
                rationale
        );
    }

    // ── 估值分類 ─────────────────────────────────────────────────────────────

    private String classifyValuation(int score) {
        if (score <= 30) return "VALUE_LOW";
        if (score <= 55) return "VALUE_FAIR";
        if (score <= 75) return "VALUE_HIGH";
        return "VALUE_STORY";
    }

    // ── 排除條件檢核 ──────────────────────────────────────────────────────────

    private String checkExclusion(StockEvaluateRequest req, String marketGrade,
                                  String entryType, String valuationMode, double rr) {
        if (!Boolean.TRUE.equals(req.mainStream())) {
            return "非主流族群";
        }
        if (!Boolean.TRUE.equals(req.aboveOpen())) {
            return "跌破開盤價";
        }
        if (!Boolean.TRUE.equals(req.abovePrevClose())) {
            return "跌破昨收";
        }
        if (!("PULLBACK".equals(entryType) || "BREAKOUT".equals(entryType) || "REVERSAL".equals(entryType))) {
            return "不符合允許進場型態（需為 PULLBACK/BREAKOUT/REVERSAL）";
        }
        if (!"A".equals(marketGrade) && ("VALUE_HIGH".equals(valuationMode) || "VALUE_STORY".equals(valuationMode))) {
            return "高估值/題材估值在非 A 盤不做";
        }
        double rrThreshold = "B".equals(marketGrade) ? 2.0 : 1.8;
        if (rr < rrThreshold) {
            return String.format(Locale.ROOT, "風報比 %.2f 低於 %.1f", rr, rrThreshold);
        }
        return null;
    }

    // ── 工具 ─────────────────────────────────────────────────────────────────

    private String formatZone(double lower, double upper) {
        return String.format(Locale.ROOT, "%.2f-%.2f", lower, upper);
    }

    private String buildRationale(String valuationMode, String entryType, double rr, String sltpNote) {
        return "valuationMode=" + valuationMode
                + ", entryType=" + entryType
                + ", rr=" + String.format(Locale.ROOT, "%.2f", rr)
                + ", " + sltpNote;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
