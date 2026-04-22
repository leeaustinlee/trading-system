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
 * 最終決策引擎（v2.6 MVP Refactor：三層分級）。
 *
 * <h3>分級規則（全部由 score_config 控制）</h3>
 * <ul>
 *   <li>A+：final_rank_score &gt;= scoring.grade_ap_min（預設 8.5），且 RR &gt;= rr_min_ap</li>
 *   <li>A ：final_rank_score &gt;= scoring.grade_a_min（預設 7.6）</li>
 *   <li>B ：final_rank_score &gt;= scoring.grade_b_min（預設 6.8）</li>
 *   <li>C ：其餘</li>
 * </ul>
 *
 * <h3>決策規則（v2.6 MVP 三層：A+ primary / A normal / B trial）</h3>
 * <ul>
 *   <li>有 A+ → ENTER 前 N 檔（decision.max_pick_aplus，預設 2），mode=PRIMARY</li>
 *   <li>無 A+ 但有 A → ENTER 前 N 檔（decision.max_pick_a，預設 2），mode=NORMAL 倉位 0.7x</li>
 *   <li>無 A+/A 但有 B 且 market != C → ENTER 前 N 檔（decision.max_pick_b，預設 1），mode=TRIAL 倉位 0.5x</li>
 *   <li>全無 → REST</li>
 * </ul>
 *
 * <p>原 v2.0「A+ only → REST」改為三層可交易，避免在無完美 setup 時長期空手。
 * 倉位係數由 decision.position_factor_* 控制，交由下游 PositionSizing 消費。</p>
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
        BigDecimal gradeApMin = config.getDecimal("scoring.grade_ap_min", new BigDecimal("8.5"));
        BigDecimal gradeAMin  = config.getDecimal("scoring.grade_a_min",  new BigDecimal("7.6"));
        BigDecimal gradeBMin  = config.getDecimal("scoring.grade_b_min",  new BigDecimal("6.8"));
        BigDecimal rrMinAp    = config.getDecimal("scoring.rr_min_ap",    new BigDecimal("2.2"));
        int maxPickAPlus      = config.getInt("decision.max_pick_aplus",  2);
        int maxPickA          = config.getInt("decision.max_pick_a",      2);
        int maxPickB          = config.getInt("decision.max_pick_b",      1);

        List<FinalDecisionCandidateRequest> candidates =
                request.candidates() == null ? List.of() : request.candidates();
        List<String> rejected = new ArrayList<>();
        List<FinalDecisionCandidateRequest> apBucket = new ArrayList<>();
        List<FinalDecisionCandidateRequest> aBucket  = new ArrayList<>();
        List<FinalDecisionCandidateRequest> bBucket  = new ArrayList<>();

        for (FinalDecisionCandidateRequest c : candidates) {
            // 已被 VetoEngine hard veto 的直接跳過
            if (Boolean.TRUE.equals(c.isVetoed())) {
                rejected.add(c.stockCode() + " [HARD_VETOED]");
                continue;
            }

            // 基本市場條件驗證（非 veto 層的過濾）
            String basicReject = validateBasicConditions(c, marketGrade);
            if (basicReject != null) {
                rejected.add(c.stockCode() + " " + basicReject);
                continue;
            }

            BigDecimal rankScore = c.finalRankScore();
            double rr = c.riskRewardRatio() == null ? 0.0 : c.riskRewardRatio();

            if (rankScore == null) {
                rejected.add(c.stockCode() + " 無排序分數");
                continue;
            }

            // 分桶：A+ / A / B / C
            if (rankScore.compareTo(gradeApMin) >= 0 && rr >= rrMinAp.doubleValue()) {
                apBucket.add(c);
            } else if (rankScore.compareTo(gradeAMin) >= 0) {
                aBucket.add(c);
            } else if (rankScore.compareTo(gradeBMin) >= 0) {
                bBucket.add(c);
            } else {
                rejected.add(c.stockCode() + " 等級 C（score=" + rankScore + "）");
            }
        }

        // 三桶各自按 finalRankScore 降序
        Comparator<FinalDecisionCandidateRequest> byScoreDesc = Comparator.comparing(
                (FinalDecisionCandidateRequest c) -> c.finalRankScore() == null
                        ? 0.0 : c.finalRankScore().doubleValue()
        ).reversed();
        apBucket.sort(byScoreDesc);
        aBucket.sort(byScoreDesc);
        bBucket.sort(byScoreDesc);

        // ── 三層決策：A+ > A > B ───────────────────────────────────────────
        if (!apBucket.isEmpty()) {
            int pick = Math.min(apBucket.size(), maxPickAPlus);
            List<FinalDecisionSelectedStockResponse> selected = apBucket.stream()
                    .limit(pick).map(this::toSelected).toList();
            log.info("[FinalDecisionEngine] ENTER A_PLUS ({} 檔)", pick);
            return new FinalDecisionResponse(
                    "ENTER", selected, rejected,
                    "A+ 等級標的已確認（主攻）。");
        }

        if (!aBucket.isEmpty()) {
            int pick = Math.min(aBucket.size(), maxPickA);
            List<FinalDecisionSelectedStockResponse> selected = aBucket.stream()
                    .limit(pick).map(this::toSelected).toList();
            log.info("[FinalDecisionEngine] ENTER A_NORMAL ({} 檔；倉位 0.7x)", pick);
            return new FinalDecisionResponse(
                    "ENTER", selected, rejected,
                    "無 A+ 主攻，但有 A 等級候選（正常倉位 0.7x）。");
        }

        // B 試單：僅在 market=A 或 B 時允許（market=C 已在前段 REST）
        if (!bBucket.isEmpty()) {
            int pick = Math.min(bBucket.size(), maxPickB);
            List<FinalDecisionSelectedStockResponse> selected = bBucket.stream()
                    .limit(pick).map(this::toSelected).toList();
            log.info("[FinalDecisionEngine] ENTER B_TRIAL ({} 檔；倉位 0.5x)", pick);
            return new FinalDecisionResponse(
                    "ENTER", selected, rejected,
                    "僅有 B 等級候選，以試單倉位進場（0.5x）。");
        }

        log.info("[FinalDecisionEngine] REST: no A+/A/B candidates after scoring");
        return new FinalDecisionResponse(
                "REST", List.of(), rejected,
                "無 A+/A/B 等級候選股，今日建議休息。");
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
