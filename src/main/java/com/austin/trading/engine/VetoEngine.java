package com.austin.trading.engine;

import com.austin.trading.service.ScoreConfigService;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 硬性淘汰引擎（Veto）。
 * <p>
 * 所有 veto 規則在此統一執行。AI 評分不能覆蓋 veto。
 * 先 veto → 再排序，是本系統最重要的優先原則。
 * </p>
 *
 * Veto 原因代碼：
 * <ul>
 *   <li>MARKET_GRADE_C       — 市場等級 C，禁止進場</li>
 *   <li>DECISION_LOCKED      — Decision Lock 啟動</li>
 *   <li>TIME_DECAY_LATE      — 時間衰減 LATE 且市場非 A</li>
 *   <li>RR_BELOW_MIN         — 風報比低於最低門檻</li>
 *   <li>NOT_IN_FINAL_PLAN    — 未標記納入最終計畫</li>
 *   <li>NO_STOP_LOSS         — 無合理停損設定</li>
 *   <li>HIGH_VAL_WEAK_MARKET — 高估值標的在弱市（VALUE_HIGH/STORY + 非 A）</li>
 * </ul>
 */
@Component
public class VetoEngine {

    private final ScoreConfigService config;

    public VetoEngine(ScoreConfigService config) {
        this.config = config;
    }

    public record VetoInput(
            String marketGrade,
            String decisionLock,
            String timeDecayStage,
            BigDecimal riskRewardRatio,
            Boolean includeInFinalPlan,
            BigDecimal stopLossPrice,
            String valuationMode
    ) {}

    public record VetoResult(boolean vetoed, List<String> reasons) {}

    public VetoResult evaluate(VetoInput input) {
        List<String> reasons = new ArrayList<>();

        // Rule 1: 市場等級 C
        if ("C".equalsIgnoreCase(input.marketGrade())) {
            reasons.add("MARKET_GRADE_C");
        }

        // Rule 2: Decision Lock 鎖定
        if ("LOCKED".equalsIgnoreCase(input.decisionLock())) {
            reasons.add("DECISION_LOCKED");
        }

        // Rule 3: 時間衰減 LATE 且市場非 A（可設定允許的最低等級）
        String lateStopGrade = config.getString("scoring.late_stop_market_grade", "A");
        if ("LATE".equalsIgnoreCase(input.timeDecayStage())) {
            boolean marketSufficient = "A".equalsIgnoreCase(input.marketGrade())
                    || ("B".equalsIgnoreCase(lateStopGrade) && "B".equalsIgnoreCase(input.marketGrade()));
            if (!marketSufficient) {
                reasons.add("TIME_DECAY_LATE");
            }
        }

        // Rule 4: 風報比低於門檻
        BigDecimal rrMin = "A".equalsIgnoreCase(input.marketGrade())
                ? config.getDecimal("scoring.rr_min_grade_a", new BigDecimal("1.8"))
                : config.getDecimal("scoring.rr_min_grade_b", new BigDecimal("2.0"));
        if (input.riskRewardRatio() != null && input.riskRewardRatio().compareTo(rrMin) < 0) {
            reasons.add("RR_BELOW_MIN");
        }

        // Rule 5: 未標記納入最終計畫
        if (!Boolean.TRUE.equals(input.includeInFinalPlan())) {
            reasons.add("NOT_IN_FINAL_PLAN");
        }

        // Rule 6: 無停損設定
        if (input.stopLossPrice() == null) {
            reasons.add("NO_STOP_LOSS");
        }

        // Rule 7: 高估值在弱市
        String vm = input.valuationMode();
        if (("VALUE_HIGH".equalsIgnoreCase(vm) || "VALUE_STORY".equalsIgnoreCase(vm))
                && !"A".equalsIgnoreCase(input.marketGrade())) {
            reasons.add("HIGH_VAL_WEAK_MARKET");
        }

        return new VetoResult(!reasons.isEmpty(), reasons);
    }
}
