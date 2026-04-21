package com.austin.trading.engine;

import com.austin.trading.dto.request.FinalDecisionCandidateRequest;
import com.austin.trading.dto.request.FinalDecisionEvaluateRequest;
import com.austin.trading.dto.response.FinalDecisionResponse;
import com.austin.trading.dto.response.FinalDecisionSelectedStockResponse;
import com.austin.trading.service.ScoreConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 最終決策引擎（v2.0 BC Sniper）。
 *
 * <h3>分級規則（全部由 score_config 控制）</h3>
 * <ul>
 *   <li>A+：final_rank_score >= scoring.grade_ap_min（預設 8.8），且 RR >= rr_min_ap</li>
 *   <li>A ：final_rank_score >= scoring.grade_a_min（預設 8.2）</li>
 *   <li>B ：final_rank_score >= scoring.grade_b_min（預設 7.4）</li>
 *   <li>C ：其餘</li>
 * </ul>
 *
 * <h3>決策規則</h3>
 * <ul>
 *   <li>A+ 數量 >= 2 → ENTER 前 2 名</li>
 *   <li>A+ 數量 == 1 → ENTER 前 1 名</li>
 *   <li>A+ 數量 == 0 → REST</li>
 * </ul>
 *
 * <p>只有 A+ 等級標的可以進場；A/B/C 一律觀望或休息。</p>
 */
@Component
public class FinalDecisionEngine {

    private static final Logger log = LoggerFactory.getLogger(FinalDecisionEngine.class);

    private final ScoreConfigService config;

    public FinalDecisionEngine(ScoreConfigService config) {
        this.config = config;
    }

    public FinalDecisionResponse evaluate(FinalDecisionEvaluateRequest request) {
        String marketGrade = normalize(request.marketGrade());
        String decisionLock = normalize(request.decisionLock());
        String timeDecay = normalize(request.timeDecayStage());
        boolean hasPosition = Boolean.TRUE.equals(request.hasPosition());

        // ── 市場層級硬性休息條件 ──────────────────────────────────────────────
        if ("C".equals(marketGrade)) {
            log.info("[FinalDecisionEngine] REST: market_grade=C");
            return rest("市場等級為 C，今日建議休息。", List.of("market_grade=C"));
        }
        if ("LOCKED".equals(decisionLock)) {
            log.warn("[FinalDecisionEngine] REST: decision_lock=LOCKED (grade={}, timeDecay={}, hasPos={})",
                    marketGrade, timeDecay, hasPosition);
            return rest("決策鎖啟用，暫不進場。", List.of("decision_lock=LOCKED"));
        }
        if ("LATE".equals(timeDecay) && !"A".equals(marketGrade) && !hasPosition) {
            log.info("[FinalDecisionEngine] REST: late_session_force_rest grade={} hasPos=false", marketGrade);
            return rest("10:30 後且市場非 A，無持倉時強制休息。", List.of("late_session_force_rest"));
        }

        // ── 讀取分級門檻 ──────────────────────────────────────────────────────
        BigDecimal gradeApMin = config.getDecimal("scoring.grade_ap_min", new BigDecimal("8.8"));
        BigDecimal rrMinAp    = config.getDecimal("scoring.rr_min_ap",    new BigDecimal("2.5"));

        List<FinalDecisionCandidateRequest> candidates =
                request.candidates() == null ? List.of() : request.candidates();
        List<String> rejected = new ArrayList<>();
        List<FinalDecisionCandidateRequest> apCandidates = new ArrayList<>();

        for (FinalDecisionCandidateRequest c : candidates) {
            // 已被 VetoEngine 淘汰的直接跳過
            if (Boolean.TRUE.equals(c.isVetoed())) {
                rejected.add(c.stockCode() + " [VETOED]");
                continue;
            }

            // 基本市場條件驗證（非 veto 層的過濾）
            String basicReject = validateBasicConditions(c, marketGrade);
            if (basicReject != null) {
                rejected.add(c.stockCode() + " " + basicReject);
                continue;
            }

            // 必須達到 A+ 等級才能進場
            BigDecimal rankScore = c.finalRankScore();
            double rr = c.riskRewardRatio() == null ? 0.0 : c.riskRewardRatio();

            boolean isAp = rankScore != null
                    && rankScore.compareTo(gradeApMin) >= 0
                    && rr >= rrMinAp.doubleValue();

            if (isAp) {
                apCandidates.add(c);
            } else {
                String grade = gradeLabel(rankScore, gradeApMin);
                rejected.add(c.stockCode() + " 等級" + grade + "（需A+才能進場）");
            }
        }

        // 依 finalRankScore 降序排列 A+ 標的
        apCandidates.sort(Comparator.comparing((FinalDecisionCandidateRequest c) -> {
            if (c.finalRankScore() != null) return c.finalRankScore().doubleValue();
            return c.riskRewardRatio() == null ? 0.0 : c.riskRewardRatio();
        }).reversed());

        if (apCandidates.isEmpty()) {
            return new FinalDecisionResponse(
                    "REST",
                    List.of(),
                    rejected,
                    "無 A+ 等級候選股，今日建議休息。"
            );
        }

        // A+ >= 2 → 取前 2；A+ == 1 → 取前 1
        int maxPick = apCandidates.size() >= 2 ? 2 : 1;
        List<FinalDecisionSelectedStockResponse> selected = apCandidates.stream()
                .limit(maxPick)
                .map(this::toSelected)
                .toList();

        return new FinalDecisionResponse(
                "ENTER",
                selected,
                rejected,
                "A+ 等級標的已確認，請依進場區間與停損紀律執行。"
        );
    }

    /**
     * 基本市場條件驗證（補充 VetoEngine 未涵蓋的量價/技術條件）。
     * 回傳 null 表示通過；回傳字串說明被過濾原因。
     */
    private String validateBasicConditions(FinalDecisionCandidateRequest c, String marketGrade) {
        if (!Boolean.TRUE.equals(c.mainStream())) {
            return "非主流族群";
        }
        if (Boolean.TRUE.equals(c.falseBreakout())) {
            return "假突破風險";
        }
        if (Boolean.TRUE.equals(c.belowOpen()) || Boolean.TRUE.equals(c.belowPrevClose())) {
            return "跌破開盤或昨收";
        }

        String entryType = normalize(c.entryType());
        if (!("PULLBACK".equals(entryType) || "BREAKOUT".equals(entryType) || "REVERSAL".equals(entryType))) {
            return "不符合允許進場型態";
        }

        // entry trigger 檢查：需有明確進場訊號
        boolean requireTrigger = config.getBoolean("decision.require_entry_trigger", true);
        if (requireTrigger && !Boolean.TRUE.equals(c.entryTriggered())) {
            return "尚未觸發進場訊號（突破/回測未確認）";
        }

        return null;
    }

    private String gradeLabel(BigDecimal score, BigDecimal apMin) {
        if (score == null) return "C";
        if (score.compareTo(apMin) >= 0) return "A+";
        BigDecimal aMin = config.getDecimal("scoring.grade_a_min", new BigDecimal("8.2"));
        BigDecimal bMin = config.getDecimal("scoring.grade_b_min", new BigDecimal("7.4"));
        if (score.compareTo(aMin) >= 0) return "A";
        if (score.compareTo(bMin) >= 0) return "B";
        return "C";
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
