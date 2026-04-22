package com.austin.trading.engine;

import com.austin.trading.domain.enums.MarketSession;
import com.austin.trading.dto.request.FinalDecisionCandidateRequest;
import com.austin.trading.dto.request.FinalDecisionEvaluateRequest;
import com.austin.trading.dto.response.FinalDecisionResponse;
import com.austin.trading.dto.response.FinalDecisionSelectedStockResponse;
import com.austin.trading.service.ScoreConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.ZoneId;
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

    /** v2.7 P1 fix: 明確綁定台股時區，避免 JVM 預設時區（如 UTC）造成 session 切點誤判。 */
    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Taipei");

    private final ScoreConfigService config;

    public FinalDecisionEngine(ScoreConfigService config) {
        this.config = config;
    }

    public FinalDecisionResponse evaluate(FinalDecisionEvaluateRequest request) {
        return evaluate(request, MarketSession.fromTime(LocalTime.now(MARKET_ZONE)));
    }

    /**
     * v2.7 Session-Aware overload：允許呼叫方（或測試）明確指定 session。
     *
     * <p>非 {@link MarketSession#LIVE_TRADING} 時直接回 {@code WAIT}：
     * <ul>
     *   <li>PREMARKET（08:30-09:00）：試撮時段，不可用試撮價做決策</li>
     *   <li>OPEN_VALIDATION（09:00-09:30）：開盤驗證中，資料更新但未到決策時機</li>
     * </ul>
     */
    public FinalDecisionResponse evaluate(FinalDecisionEvaluateRequest request, MarketSession session) {
        // v2.7 Session-Aware gate
        if (!session.allowsFinalDecision()) {
            String summary = session == MarketSession.PREMARKET
                    ? "盤前觀察中，09:30 再出決策。"
                    : "開盤驗證中，等 09:30 決策。";
            log.info("[FinalDecisionEngine] WAIT: session={}", session);
            return new FinalDecisionResponse(
                    "WAIT",
                    List.of(),
                    List.of("session=" + session.name()),
                    summary
            );
        }

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
        BigDecimal gradeApMin       = config.getDecimal("scoring.grade_ap_min", new BigDecimal("8.5"));
        BigDecimal gradeAMin        = config.getDecimal("scoring.grade_a_min",  new BigDecimal("7.6"));
        BigDecimal gradeBMin        = config.getDecimal("scoring.grade_b_min",  new BigDecimal("6.8"));
        BigDecimal rrMinAp          = config.getDecimal("scoring.rr_min_ap",    new BigDecimal("2.2"));
        BigDecimal mainStreamBoost  = config.getDecimal("ranking.main_stream_boost", new BigDecimal("0.3"));
        int maxPickAPlus            = config.getInt("decision.max_pick_aplus",  2);
        int maxPickA                = config.getInt("decision.max_pick_a",      2);
        int maxPickB                = config.getInt("decision.max_pick_b",      1);

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

            // 基本市場條件驗證（非 veto 層的過濾；v2.7 依 session 差異化）
            String basicReject = validateBasicConditions(c, marketGrade, session);
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

            // v2.7 A6: mainStream 從 hard block 改 ranking boost（主流族群標的加分）
            BigDecimal adjustedRank = rankScore;
            if (Boolean.TRUE.equals(c.mainStream())) {
                adjustedRank = rankScore.add(mainStreamBoost);
            }

            // 分桶：A+ / A / B / C（用 adjustedRank）
            if (adjustedRank.compareTo(gradeApMin) >= 0 && rr >= rrMinAp.doubleValue()) {
                apBucket.add(c);
            } else if (adjustedRank.compareTo(gradeAMin) >= 0) {
                aBucket.add(c);
            } else if (adjustedRank.compareTo(gradeBMin) >= 0) {
                bBucket.add(c);
            } else {
                rejected.add(c.stockCode() + " 等級 C（rank=" + rankScore
                        + (Boolean.TRUE.equals(c.mainStream()) ? " +boost=" + mainStreamBoost : "")
                        + "）");
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
     * 基本市場條件驗證（v2.7：依 session 差異化）。
     *
     * <p>v2.7 變更：</p>
     * <ul>
     *   <li>A5: {@code entryTriggered} 檢查移到 {@link ExecutionTimingEngine}，本方法不再檢查</li>
     *   <li>A6: {@code mainStream} 從 hard block 改 ranking boost，本方法不再檢查</li>
     *   <li>A7: {@code belowOpen} / {@code belowPrevClose} 只在 {@link MarketSession#LIVE_TRADING} 做 hard block</li>
     * </ul>
     *
     * @return null 表示通過；非 null 為排除原因
     */
    private String validateBasicConditions(FinalDecisionCandidateRequest c, String marketGrade,
                                            MarketSession session) {
        if (Boolean.TRUE.equals(c.falseBreakout())) {
            return "假突破風險";
        }

        // v2.7 A7: 盤前試撮 / 開盤驗證中，belowOpen/belowPrevClose 不可作為 hard block
        if (session.allowsPriceGateHardBlock()) {
            if (Boolean.TRUE.equals(c.belowOpen()) || Boolean.TRUE.equals(c.belowPrevClose())) {
                return "跌破開盤或昨收";
            }
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
