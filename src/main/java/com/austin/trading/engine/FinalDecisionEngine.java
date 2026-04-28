package com.austin.trading.engine;

import com.austin.trading.domain.enums.DecisionPlanningMode;
import com.austin.trading.domain.enums.MarketSession;
import com.austin.trading.dto.internal.PriceGateDecision;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 最終決策引擎（v2.6 MVP Refactor：三層分級）。
 *
 * <h3>分級規則（全部由 score_config 控制）</h3>
 * <ul>
 *   <li>A+：final_rank_score &gt;= scoring.grade_ap_min（預設 8.2），且 RR &gt;= rr_min_ap（預設 2.2）</li>
 *   <li>A ：final_rank_score &gt;= scoring.grade_a_min（預設 7.5）</li>
 *   <li>B ：final_rank_score &gt;= scoring.grade_b_min（預設 6.5）</li>
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
    private final PriceGateEvaluator priceGateEvaluator;
    /** v2.15：ChasedHigh hard gate，shadow / live 由 entry.chased-high-gate.enabled 控制。 */
    private final ChasedHighEntryEngine chasedHighEntryEngine;

    @org.springframework.beans.factory.annotation.Autowired
    public FinalDecisionEngine(ScoreConfigService config, PriceGateEvaluator priceGateEvaluator,
                                ChasedHighEntryEngine chasedHighEntryEngine) {
        this.config = config;
        this.priceGateEvaluator = priceGateEvaluator;
        this.chasedHighEntryEngine = chasedHighEntryEngine;
    }

    /** Test-friendly ctor（無 chased-high 引擎時退回 shadow 行為）。 */
    public FinalDecisionEngine(ScoreConfigService config, PriceGateEvaluator priceGateEvaluator) {
        this(config, priceGateEvaluator, new ChasedHighEntryEngine());
    }

    /** ChasedHigh 評估結果：用於 trace + 決策。 */
    public enum ChasedHighOutcome { OK, WARN, BLOCK, NO_DATA }

    public FinalDecisionResponse evaluate(FinalDecisionEvaluateRequest request) {
        return evaluate(request, MarketSession.fromTime(LocalTime.now(MARKET_ZONE)),
                DecisionPlanningMode.INTRADAY_ENTRY);
    }

    /**
     * v2.7 Session-Aware overload（維持向下相容）：預設盤中進場模式。
     */
    public FinalDecisionResponse evaluate(FinalDecisionEvaluateRequest request, MarketSession session) {
        return evaluate(request, session, DecisionPlanningMode.INTRADAY_ENTRY);
    }

    /**
     * v2.8 主入口：允許呼叫方明確指定 session + planning mode。
     *
     * <h3>兩種模式差異</h3>
     * <ul>
     *   <li>{@link DecisionPlanningMode#INTRADAY_ENTRY} — 盤中進場（原行為）：
     *       受 session gate、受 decisionLock 管控，輸出 ENTER/REST/WAIT + A+/A/B bucket</li>
     *   <li>{@link DecisionPlanningMode#POSTCLOSE_TOMORROW_PLAN} — 盤後明日規劃：
     *       忽略 session（盤後本來就是收盤後）、忽略 decisionLock（日內 lock 不該污染明日規劃）、
     *       輸出 decision=PLAN + primary/backup/sectorIndicators/avoidSymbols，
     *       summary 改為人可讀的盤後語意</li>
     * </ul>
     */
    public FinalDecisionResponse evaluate(FinalDecisionEvaluateRequest request,
                                           MarketSession session,
                                           DecisionPlanningMode mode) {
        if (mode == DecisionPlanningMode.POSTCLOSE_TOMORROW_PLAN) {
            return evaluatePostclosePlan(request);
        }
        return evaluateIntradayEntry(request, session);
    }

    /**
     * 盤中進場模式（v2.7 session-aware + v2.6 三層分級）。
     */
    private FinalDecisionResponse evaluateIntradayEntry(FinalDecisionEvaluateRequest request,
                                                          MarketSession session) {
        // v2.16：trading.status.allow_trade 全域 kill switch（最高優先；早於 session/grade）
        if (!config.getBoolean("trading.status.allow_trade", true)) {
            log.warn("[FinalDecisionEngine] REST: trading.status.allow_trade=false (kill switch)");
            return rest("交易 kill switch 啟動，今日全面禁止進場。",
                    List.of("TRADING_DISABLED"));
        }

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
        BigDecimal gradeApMin       = config.getDecimal("scoring.grade_ap_min", new BigDecimal("8.2"));
        BigDecimal gradeAMin        = config.getDecimal("scoring.grade_a_min",  new BigDecimal("7.5"));
        BigDecimal gradeBMin        = config.getDecimal("scoring.grade_b_min",  new BigDecimal("6.5"));
        BigDecimal rrMinAp          = config.getDecimal("scoring.rr_min_ap",    new BigDecimal("2.2"));
        BigDecimal mainStreamBoost  = config.getDecimal("ranking.main_stream_boost", new BigDecimal("0.3"));
        int maxPickAPlus            = config.getInt("decision.max_pick_aplus",  2);
        int maxPickA                = config.getInt("decision.max_pick_a",      2);
        int maxPickB                = config.getInt("decision.max_pick_b",      1);

        // v2.15：ChasedHigh 接到 ENTER path
        boolean chasedHighEnabled   = config.getBoolean("entry.chased-high-gate.enabled", false);
        BigDecimal chasedThreshold  = config.getDecimal("entry.chased-high-gate.threshold", new BigDecimal("0.02"));
        BigDecimal chasedWarn       = config.getDecimal("entry.chased-high-gate.warn_threshold", new BigDecimal("0.04"));

        // 2026-04-29 P0.3：tradabilityTag gate（PowerShell screener 自我標示「不列主進場」/「僅參考」）
        boolean respectTradabilityTag = config.getBoolean(
                "final_decision.respect_tradability_tag.enabled", true);
        BigDecimal tradabilitySoftPenalty = config.getDecimal(
                "final_decision.tradability_tag.soft_penalty", new BigDecimal("1.0"));

        List<FinalDecisionCandidateRequest> candidates =
                request.candidates() == null ? List.of() : request.candidates();
        List<String> rejected = new ArrayList<>();
        List<FinalDecisionCandidateRequest> apBucket = new ArrayList<>();
        List<FinalDecisionCandidateRequest> aBucket  = new ArrayList<>();
        List<FinalDecisionCandidateRequest> bBucket  = new ArrayList<>();
        // v2.9 Gate 6/7: 被 priceGate 判 WAIT 的候選不進分桶也不 reject，落到 waitBucket
        List<FinalDecisionCandidateRequest> waitBucket = new ArrayList<>();

        for (FinalDecisionCandidateRequest c : candidates) {
            // 已被 VetoEngine hard veto 的直接跳過
            if (Boolean.TRUE.equals(c.isVetoed())) {
                rejected.add(c.stockCode() + " [HARD_VETOED]");
                continue;
            }

            // 基本市場條件驗證（非 veto 層的過濾；v2.7 依 session 差異化；v2.9 移除 belowOpen/belowPrevClose）
            String basicReject = validateBasicConditions(c, marketGrade, session);
            if (basicReject != null) {
                rejected.add(c.stockCode() + " " + basicReject);
                continue;
            }

            // v2.9 Gate 6/7 Refactor：belowOpen / belowPrevClose 改條件式 hard block
            PriceGateDecision priceGate = priceGateEvaluator.evaluate(c, session);
            if (priceGate.isBlock()) {
                rejected.add(c.stockCode() + " priceGate=" + priceGate.reason());
                continue;
            }
            if (priceGate.isWait()) {
                log.info("[FinalDecisionEngine] priceGate WAIT: {} reason={}",
                        c.stockCode(), priceGate.reason());
                waitBucket.add(c);
                continue;
            }

            // v2.15 ChasedHigh hard gate（feature flag）
            ChasedHighOutcome chasedOutcome = evaluateChasedHigh(c, chasedThreshold, chasedWarn);
            if (chasedOutcome == ChasedHighOutcome.BLOCK) {
                if (chasedHighEnabled) {
                    log.info("[FinalDecisionEngine] CHASED_HIGH_BLOCK: {} cur={} entryZone={}",
                            c.stockCode(), c.currentPrice(), c.entryPriceZone());
                    rejected.add(c.stockCode() + " CHASED_HIGH_BLOCK（離日高 < " + chasedThreshold + "）");
                    continue;
                } else {
                    // shadow：記入 trace 但不擋
                    log.info("[FinalDecisionEngine] [SHADOW] CHASED_HIGH would block: {} cur={} entryZone={}",
                            c.stockCode(), c.currentPrice(), c.entryPriceZone());
                }
            } else if (chasedOutcome == ChasedHighOutcome.WARN) {
                log.debug("[FinalDecisionEngine] CHASED_HIGH_WARN: {} cur={} entryZone={}",
                        c.stockCode(), c.currentPrice(), c.entryPriceZone());
            }

            // 2026-04-29 P0.3：tradabilityTag block / soft penalty
            // 「不列主進場」→ hard block；「漲幅過大」/「僅參考」→ -soft_penalty 分數扣分
            // PowerShell screener 已自我標示為高風險，舊版引擎完全忽略此 tag，現在納入決策。
            if (respectTradabilityTag) {
                String tag = c.tradabilityTag();
                if (tag != null && !tag.isBlank()) {
                    if (tag.contains("不列主進場")) {
                        log.info("[FinalDecisionEngine] TRADABILITY_TAG_BLOCK: {} tag={}",
                                c.stockCode(), tag);
                        rejected.add(c.stockCode() + " TRADABILITY_TAG_BLOCK：tradabilityTag="
                                + tag + " 系統已標示不列主進場");
                        continue;
                    }
                    if (tag.contains("漲幅過大") || tag.contains("僅參考")) {
                        BigDecimal originalScore = c.finalRankScore();
                        if (originalScore != null) {
                            BigDecimal penalized = originalScore.subtract(tradabilitySoftPenalty);
                            log.info("[FinalDecisionEngine] TRADABILITY_TAG_SOFT_PENALTY: {} tag={} "
                                    + "score {} -> {}",
                                    c.stockCode(), tag, originalScore, penalized);
                            c = c.withFinalRankScore(penalized);
                        } else {
                            log.debug("[FinalDecisionEngine] TRADABILITY_TAG_SOFT_PENALTY skip: {} "
                                    + "tag={} but finalRankScore is null", c.stockCode(), tag);
                        }
                    }
                }
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

        // v2.9 Gate 6/7：若沒有可進場候選但有 WAIT 候選，輸出 WAIT 等待盤中確認
        if (!waitBucket.isEmpty()) {
            String symbols = waitBucket.stream()
                    .map(FinalDecisionCandidateRequest::stockCode)
                    .toList().toString();
            log.info("[FinalDecisionEngine] WAIT: priceGate pending {} candidate(s) {}",
                    waitBucket.size(), symbols);
            return new FinalDecisionResponse(
                    "WAIT", List.of(), rejected,
                    "價格 gate 疑似洗盤，等待站回 VWAP / 確認再進場：" + symbols);
        }

        log.info("[FinalDecisionEngine] REST: no A+/A/B candidates after scoring");
        return new FinalDecisionResponse(
                "REST", List.of(), rejected,
                "無 A+/A/B 等級候選股，今日建議休息。");
    }

    /**
     * 基本市場條件驗證（v2.7 session-aware / v2.9 拆出 priceGate）。
     *
     * <p>v2.7 變更：</p>
     * <ul>
     *   <li>A5: {@code entryTriggered} 檢查移到 {@link ExecutionTimingEngine}，本方法不再檢查</li>
     *   <li>A6: {@code mainStream} 從 hard block 改 ranking boost，本方法不再檢查</li>
     *   <li>A7: {@code belowOpen} / {@code belowPrevClose} 只在 {@link MarketSession#LIVE_TRADING} 做 hard block</li>
     * </ul>
     *
     * <p>v2.9 變更：</p>
     * <ul>
     *   <li>Gate 6/7: {@code belowOpen} / {@code belowPrevClose} 從一律 hard block 改為條件式，
     *       判斷移到 {@link PriceGateEvaluator}，本方法只保留 falseBreakout + entryType 檢查</li>
     * </ul>
     *
     * @return null 表示通過；非 null 為排除原因
     */
    private String validateBasicConditions(FinalDecisionCandidateRequest c, String marketGrade,
                                            MarketSession session) {
        if (Boolean.TRUE.equals(c.falseBreakout())) {
            return "假突破風險";
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

    /**
     * v2.15 / v2.16：每筆候選的 chased-high 判斷。
     *
     * <p>v2.16：若 candidate 帶有實際當日 dayHigh（從 live-quotes 取得）優先使用；
     * 否則 fallback 到 entryPriceZone 上緣（v2.15 行為）。</p>
     *
     * <p>若 currentPrice 缺、且 dayHigh 與 entryPriceZone 都不可用 → NO_DATA。</p>
     */
    public ChasedHighOutcome evaluateChasedHigh(FinalDecisionCandidateRequest c,
                                                 BigDecimal blockThreshold,
                                                 BigDecimal warnThreshold) {
        BigDecimal cur = c.currentPrice();
        if (cur == null || cur.signum() <= 0) return ChasedHighOutcome.NO_DATA;

        // v2.16：優先用真實 dayHigh，fallback 到 entryPriceZone 上緣
        Double reference = null;
        if (c.dayHigh() != null && c.dayHigh().signum() > 0) {
            reference = c.dayHigh().doubleValue();
        } else {
            Double zoneUpper = parseEntryZoneUpper(c.entryPriceZone());
            if (zoneUpper != null && zoneUpper > 0) reference = zoneUpper;
        }
        if (reference == null) return ChasedHighOutcome.NO_DATA;

        double curD = cur.doubleValue();
        double blockT = blockThreshold == null ? 0.02 : blockThreshold.doubleValue();
        double warnT  = warnThreshold  == null ? 0.04 : warnThreshold.doubleValue();
        if (chasedHighEntryEngine.isChased(curD, reference, blockT)) return ChasedHighOutcome.BLOCK;
        if (chasedHighEntryEngine.isChased(curD, reference, warnT))  return ChasedHighOutcome.WARN;
        return ChasedHighOutcome.OK;
    }

    /** 解析 "510.22-531.26" 格式的上緣，失敗回 null。 */
    static Double parseEntryZoneUpper(String entryPriceZone) {
        if (entryPriceZone == null || entryPriceZone.isBlank()) return null;
        String s = entryPriceZone.trim();
        int dash = s.indexOf('-');
        try {
            if (dash > 0 && dash < s.length() - 1) {
                return Double.parseDouble(s.substring(dash + 1).trim());
            }
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // v2.8 盤後明日規劃模式 (POSTCLOSE_TOMORROW_PLAN)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 盤後明日規劃：不輸出 ENTER/REST，而是輸出明日準備清單。
     *
     * <p>與 INTRADAY_ENTRY 差異：</p>
     * <ul>
     *   <li>忽略 {@code decisionLock}（日內 gate，不該污染明日規劃）</li>
     *   <li>忽略 session（盤後本來就不在 LIVE_TRADING 時段）</li>
     *   <li>market=C 時仍輸出 PLAN（盤後看明日，今日 C 不等於明日 C）</li>
     *   <li>分組：primary（首選）/ backup（備援）/ sectorIndicators（族群燈號不追）/ avoid（明確排除）</li>
     *   <li>summary 為人可讀盤後語意：「明日首選 8150，備援 6770；2454/8028 族群燈號不追價」</li>
     * </ul>
     */
    private FinalDecisionResponse evaluatePostclosePlan(FinalDecisionEvaluateRequest request) {
        // v2.8 P0.9：盤後規劃專用門檻。盤中 grade_a/b 對 RR/NOT_IN_PLAN 等常態 penalty
        // 後的 finalRank 分佈太嚴，導致盤後規劃永遠全 REJECTED。
        // 盤後門檻預設比盤中低 ~1.5 分，配合實際 penalty 後分佈。
        BigDecimal planPrimaryMin   = config.getDecimal("plan.primary_min",         new BigDecimal("5.5"));
        BigDecimal planBackupMin    = config.getDecimal("plan.backup_min",          new BigDecimal("4.0"));
        BigDecimal planSectorHigh   = config.getDecimal("plan.sector_indicator_min", new BigDecimal("7.8"));
        BigDecimal planAvoidMax     = config.getDecimal("plan.avoid_score_max",      new BigDecimal("3.5"));
        BigDecimal mainStreamBoost  = config.getDecimal("ranking.main_stream_boost", new BigDecimal("0.3"));
        int maxPrimary              = config.getInt("plan.max_primary", 2);
        int maxBackup               = config.getInt("plan.max_backup",  3);

        List<FinalDecisionCandidateRequest> candidates =
                request.candidates() == null ? List.of() : request.candidates();

        List<FinalDecisionCandidateRequest> primary         = new ArrayList<>();
        List<FinalDecisionCandidateRequest> backup          = new ArrayList<>();
        List<FinalDecisionCandidateRequest> sectorIndicator = new ArrayList<>();
        List<String> avoidSymbols = new ArrayList<>();
        List<String> rejected     = new ArrayList<>();

        for (FinalDecisionCandidateRequest c : candidates) {
            if (Boolean.TRUE.equals(c.isVetoed())) {
                avoidSymbols.add(c.stockCode() + " [HARD_VETOED]");
                continue;
            }
            if (Boolean.TRUE.equals(c.falseBreakout())) {
                avoidSymbols.add(c.stockCode() + " [FALSE_BREAKOUT]");
                continue;
            }

            BigDecimal rankScore = c.finalRankScore();
            if (rankScore == null) {
                rejected.add(c.stockCode() + " 無排序分數");
                continue;
            }

            double rr = c.riskRewardRatio() == null ? 0.0 : c.riskRewardRatio();
            BigDecimal adjustedRank = rankScore;
            if (Boolean.TRUE.equals(c.mainStream())) {
                adjustedRank = rankScore.add(mainStreamBoost);
            }

            // v2.8 P0.9 分類規則（盤後語意，用 plan.* 專用門檻避免被盤中 A/B 門檻誤殺）：
            // - score >= sector_indicator_min (7.8) 且 extended（如漲停）→ sectorIndicator（不追）
            // - score >= plan.primary_min (5.5)    → primary（明日首選）
            // - score >= plan.backup_min  (4.0)    → backup（明日備援）
            // - score <= plan.avoid_score_max (3.5) → avoidSymbols（明確排除）
            // - 其他                                → 平庸（不入規劃但不排除）
            boolean extended = Boolean.TRUE.equals(c.entryTooExtended());
            if (adjustedRank.compareTo(planSectorHigh) >= 0 && extended) {
                sectorIndicator.add(c);
            } else if (adjustedRank.compareTo(planPrimaryMin) >= 0) {
                primary.add(c);
            } else if (adjustedRank.compareTo(planBackupMin) >= 0) {
                backup.add(c);
            } else if (adjustedRank.compareTo(planAvoidMax) <= 0) {
                avoidSymbols.add(c.stockCode() + " [LOW_SCORE score=" + rankScore + "]");
            } else {
                rejected.add(c.stockCode() + " 中段評分，非主規劃（score=" + rankScore + "）");
            }
        }

        // 各桶按 rank 降序
        Comparator<FinalDecisionCandidateRequest> byScoreDesc = Comparator.comparing(
                (FinalDecisionCandidateRequest c) -> c.finalRankScore() == null
                        ? 0.0 : c.finalRankScore().doubleValue()
        ).reversed();
        primary.sort(byScoreDesc);
        backup.sort(byScoreDesc);
        sectorIndicator.sort(byScoreDesc);

        // trim to max
        if (primary.size() > maxPrimary) primary = primary.subList(0, maxPrimary);
        if (backup.size()  > maxBackup)  backup  = backup.subList(0, maxBackup);

        // 建 selectedStocks（primary 合併 backup）方便向下相容的 UI 顯示
        List<FinalDecisionSelectedStockResponse> selected = new ArrayList<>();
        primary.forEach(c -> selected.add(toSelected(c)));
        backup.forEach(c -> selected.add(toSelected(c)));

        // 人可讀 summary
        String summary = buildPostcloseSummary(primary, backup, sectorIndicator, avoidSymbols);

        // 結構化 planningPayload
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("primaryCandidates",  primary.stream().map(FinalDecisionCandidateRequest::stockCode).toList());
        payload.put("backupCandidates",   backup.stream().map(FinalDecisionCandidateRequest::stockCode).toList());
        payload.put("sectorIndicators",   sectorIndicator.stream().map(FinalDecisionCandidateRequest::stockCode).toList());
        payload.put("avoidSymbols",       avoidSymbols);
        payload.put("tomorrowExecutionNotes", buildExecutionNotes(primary, backup, sectorIndicator));
        payload.put("mode", "POSTCLOSE_TOMORROW_PLAN");

        log.info("[FinalDecisionEngine] PLAN primary={} backup={} sector={} avoid={}",
                primary.size(), backup.size(), sectorIndicator.size(), avoidSymbols.size());

        return new FinalDecisionResponse(
                "PLAN",
                selected,
                rejected,
                summary,
                payload
        );
    }

    private String buildPostcloseSummary(List<FinalDecisionCandidateRequest> primary,
                                          List<FinalDecisionCandidateRequest> backup,
                                          List<FinalDecisionCandidateRequest> sectorIndicator,
                                          List<String> avoidSymbols) {
        StringBuilder sb = new StringBuilder();
        if (primary.isEmpty() && backup.isEmpty() && sectorIndicator.isEmpty()) {
            sb.append("明日無主規劃標的（評分全 C）；持倉管理為主。");
        } else {
            if (!primary.isEmpty()) {
                sb.append("明日首選 ")
                  .append(primary.stream().map(FinalDecisionCandidateRequest::stockCode)
                          .reduce((a, b) -> a + "/" + b).orElse(""));
            }
            if (!backup.isEmpty()) {
                if (sb.length() > 0) sb.append("，");
                sb.append("備援 ")
                  .append(backup.stream().map(FinalDecisionCandidateRequest::stockCode)
                          .reduce((a, b) -> a + "/" + b).orElse(""));
            }
            if (!sectorIndicator.isEmpty()) {
                if (sb.length() > 0) sb.append("；");
                sb.append(sectorIndicator.stream().map(FinalDecisionCandidateRequest::stockCode)
                         .reduce((a, b) -> a + "/" + b).orElse(""))
                  .append(" 為族群燈號不追價");
            }
            sb.append("。");
        }
        if (!avoidSymbols.isEmpty()) {
            sb.append(" 排除 ").append(avoidSymbols.size()).append(" 檔。");
        }
        return sb.toString();
    }

    private List<String> buildExecutionNotes(List<FinalDecisionCandidateRequest> primary,
                                              List<FinalDecisionCandidateRequest> backup,
                                              List<FinalDecisionCandidateRequest> sectorIndicator) {
        List<String> notes = new ArrayList<>();
        primary.forEach(c -> notes.add(
                String.format("%s 明日主攻：進場 %s / 停損 %s / TP1 %s / TP2 %s",
                        c.stockCode(),
                        c.entryPriceZone() == null ? "-" : c.entryPriceZone(),
                        c.stopLossPrice() == null ? "-" : c.stopLossPrice(),
                        c.takeProfit1()   == null ? "-" : c.takeProfit1(),
                        c.takeProfit2()   == null ? "-" : c.takeProfit2())));
        backup.forEach(c -> notes.add(
                String.format("%s 備援：守 %s 可試 / 跌破即降級",
                        c.stockCode(),
                        c.stopLossPrice() == null ? "-" : c.stopLossPrice())));
        if (!sectorIndicator.isEmpty()) {
            notes.add("族群燈號觀察：" +
                    sectorIndicator.stream().map(FinalDecisionCandidateRequest::stockCode)
                            .reduce((a, b) -> a + "/" + b).orElse("") +
                    " 漲停打開爆量長黑即全面降級");
        }
        return notes;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
