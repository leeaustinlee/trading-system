package com.austin.trading.engine;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 14:00 交易檢討評分引擎。
 * 對照今日決策與實際市場，輸出合規檢核、錯誤分類與明日修正建議。
 */
@Component
public class ReviewScoringEngine {

    public ReviewResult evaluate(ReviewRequest request) {
        List<String> violations = new ArrayList<>();
        List<String> suggestions = new ArrayList<>();
        int score = 100;

        // 1. 盤型判斷是否正確
        boolean gradeCorrect = request.decidedMarketGrade() != null &&
                request.decidedMarketGrade().equals(request.actualMarketGrade());
        if (!gradeCorrect) {
            violations.add("盤型判斷錯誤：預估 " + request.decidedMarketGrade()
                    + "，實際 " + request.actualMarketGrade());
            suggestions.add("明日加強 09:00 盤型確認，先觀察台積電與台指期方向再評級");
            score -= 15;
        }

        // 2. 市場 C 級但仍進場
        if ("C".equals(request.actualMarketGrade()) && "ENTER".equals(request.decision())) {
            violations.add("違規：市場出貨/震盪盤（C 級）仍進場");
            suggestions.add("C 盤務必強制休息，不因個股強勢打破規則");
            score -= 30;
        }

        // 3. 無停損紀錄但出現虧損
        if (Boolean.TRUE.equals(request.hadLoss()) && Boolean.FALSE.equals(request.stopLossExecuted())) {
            violations.add("未執行停損，虧損擴大");
            suggestions.add("停損必須預設掛單，不依賴人工監盤");
            score -= 20;
        }

        // 4. 臨時追高
        if (Boolean.TRUE.equals(request.chasedHighEntry())) {
            violations.add("臨時追高進場（未在進場區間內）");
            suggestions.add("嚴格遵守進場區間，錯過不追");
            score -= 15;
        }

        // 5. 超過每日 Decision Lock
        if (Boolean.TRUE.equals(request.exceededDailyLoss())) {
            violations.add("超過單日虧損上限，應立即停手");
            suggestions.add("設置硬性停損提醒，虧損達門檻自動鎖單");
            score -= 20;
        }

        score = Math.max(0, score);

        String compliance = violations.isEmpty() ? "YES（符合策略）" : "NO（共 " + violations.size() + " 項違規）";
        String summary = buildSummary(request, compliance, violations, suggestions, score);

        return new ReviewResult(score, compliance, violations, suggestions, summary);
    }

    private String buildSummary(ReviewRequest req, String compliance,
                                 List<String> violations, List<String> suggestions, int score) {
        StringBuilder sb = new StringBuilder();
        sb.append("📊 今日盤型：").append(req.actualMarketGrade()).append("\n");
        sb.append("📌 決策：").append(req.decision()).append("\n");
        sb.append("✅ 合規：").append(compliance).append("\n");
        sb.append("🎯 評分：").append(score).append("/100\n");
        if (!violations.isEmpty()) {
            sb.append("\n❌ 違規項目：\n");
            violations.forEach(v -> sb.append("  × ").append(v).append("\n"));
        }
        if (!suggestions.isEmpty()) {
            sb.append("\n💡 明日修正：\n");
            suggestions.forEach(s -> sb.append("  → ").append(s).append("\n"));
        }
        return sb.toString();
    }

    // ── 輸入輸出 record ────────────────────────────────────────────────────────

    public record ReviewRequest(
            String decidedMarketGrade,
            String actualMarketGrade,
            String decision,
            Boolean hadLoss,
            Boolean stopLossExecuted,
            Boolean chasedHighEntry,
            Boolean exceededDailyLoss
    ) {}

    public record ReviewResult(
            int score,
            String compliance,
            List<String> violations,
            List<String> suggestions,
            String summary
    ) {}
}
