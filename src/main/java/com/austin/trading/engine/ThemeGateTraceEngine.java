package com.austin.trading.engine;

import com.austin.trading.domain.enums.CrowdingRisk;
import com.austin.trading.domain.enums.RotationSignal;
import com.austin.trading.domain.enums.TrendStage;
import com.austin.trading.dto.internal.GateTraceRecordDto;
import com.austin.trading.dto.internal.GateTraceRecordDto.Result;
import com.austin.trading.dto.internal.ThemeContextDto;
import com.austin.trading.dto.internal.ThemeGateTraceResultDto;
import com.austin.trading.service.ScoreConfigService;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v2 Theme Engine PR4：8-gate trace pipeline（trace-only）。
 *
 * <h3>Gate 順序（對齊 spec §6，不可重排）</h3>
 * <ol>
 *   <li>G1_MARKET_REGIME    — regime.trade_allowed</li>
 *   <li>G2_THEME_VETO       — themeStrength &gt;= strength_min</li>
 *   <li>G3_THEME_ROTATION   — rotationSignal != OUT；rotation=IN 時 themeStrength &gt;= entry_strength_min（未達 WAIT）；NONE 直接 PASS</li>
 *   <li>G4_LIQUIDITY        — avgTurnover &gt;= min_liquidity_turnover</li>
 *   <li>G5_SCORE_DIVERGENCE — max(java/claude/codex) − min &lt;= max_score_divergence</li>
 *   <li>G6_RR               — rr &gt;= rr_min</li>
 *   <li>G7_POSITION_SIZING  — openPositions &lt; maxPositions（trace-only，不改 capital）</li>
 *   <li>G8_FINAL_RANK       — theme_final_score &gt;= final_rank_a_min</li>
 * </ol>
 *
 * <h3>短路規則</h3>
 * <ul>
 *   <li>BLOCK 立即短路，下游 gate 標 SKIPPED（reason=SHORT_CIRCUITED_AFTER_BLOCK）</li>
 *   <li>WAIT 不短路（可能因資料缺失，其他 gate 仍值得跑以產出完整 trace）</li>
 * </ul>
 *
 * <h3>overall outcome</h3>
 * <ul>
 *   <li>有任一 BLOCK → BLOCK</li>
 *   <li>無 BLOCK 但有 WAIT → WAIT</li>
 *   <li>全 PASS → PASS</li>
 * </ul>
 */
@Component
public class ThemeGateTraceEngine {

    public static final String G1 = "G1_MARKET_REGIME";
    public static final String G2 = "G2_THEME_VETO";
    public static final String G3 = "G3_THEME_ROTATION";
    public static final String G4 = "G4_LIQUIDITY";
    public static final String G5 = "G5_SCORE_DIVERGENCE";
    public static final String G6 = "G6_RR";
    public static final String G7 = "G7_POSITION_SIZING";
    public static final String G8 = "G8_FINAL_RANK";

    private static final List<String> GATE_ORDER = List.of(G1, G2, G3, G4, G5, G6, G7, G8);
    private static final Map<String, String> GATE_NAMES = Map.of(
            G1, "market_regime",
            G2, "theme_veto",
            G3, "theme_rotation",
            G4, "liquidity",
            G5, "score_divergence",
            G6, "rr",
            G7, "position_sizing",
            G8, "final_rank"
    );

    private final ScoreConfigService config;

    public ThemeGateTraceEngine(ScoreConfigService config) {
        this.config = config;
    }

    /** Gate 順序常數，測試可用。 */
    public static List<String> gateOrder() { return GATE_ORDER; }

    // ══════════════════════════════════════════════════════════════════════
    // evaluate
    // ══════════════════════════════════════════════════════════════════════

    public ThemeGateTraceResultDto evaluate(Input in) {
        List<GateTraceRecordDto> records = new ArrayList<>(8);
        boolean shortCircuited = false;

        for (String key : GATE_ORDER) {
            if (shortCircuited) {
                records.add(GateTraceRecordDto.skipped(key, GATE_NAMES.get(key),
                        "SHORT_CIRCUITED_AFTER_BLOCK"));
                continue;
            }
            GateTraceRecordDto rec = switch (key) {
                case G1 -> gate1MarketRegime(in);
                case G2 -> gate2ThemeVeto(in);
                case G3 -> gate3ThemeRotation(in);
                case G4 -> gate4Liquidity(in);
                case G5 -> gate5ScoreDivergence(in);
                case G6 -> gate6Rr(in);
                case G7 -> gate7PositionSizing(in);
                case G8 -> gate8FinalRank(in);
                default -> GateTraceRecordDto.skipped(key, GATE_NAMES.get(key), "UNKNOWN_GATE");
            };
            records.add(rec);
            if (rec.result() == Result.BLOCK) {
                shortCircuited = true;
            }
        }

        Result overall = computeOverall(records);
        BigDecimal multiplier = computeThemeMultiplier(in.themeContext());
        BigDecimal themeFinal = computeThemeFinalScore(in.baseScore(), multiplier);
        BigDecimal sizeFactor = computeSizeFactor(in.themeContext());

        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("shortCircuited", shortCircuited);
        extras.put("themeMultiplier", multiplier);
        extras.put("themeSizeFactor", sizeFactor);

        String summary = String.format("%s %s → %s (gates=%d)",
                in.symbol() == null ? "-" : in.symbol(),
                in.themeContext() == null ? "no_context" : in.themeContext().themeTag(),
                overall, records.size());

        return new ThemeGateTraceResultDto(
                in.symbol(),
                Collections.unmodifiableList(records),
                overall,
                multiplier,
                themeFinal,
                sizeFactor,
                summary,
                Collections.unmodifiableMap(extras)
        );
    }

    // ══════════════════════════════════════════════════════════════════════
    // Gates
    // ══════════════════════════════════════════════════════════════════════

    private GateTraceRecordDto gate1MarketRegime(Input in) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("regime", in.marketRegime());
        p.put("tradeAllowed", in.tradeAllowed());
        p.put("riskMultiplier", in.riskMultiplier());

        if (in.marketRegime() == null) {
            return GateTraceRecordDto.wait(G1, GATE_NAMES.get(G1),
                    "REGIME_UNKNOWN", "市場狀態不明", p);
        }
        if (!in.tradeAllowed()) {
            return GateTraceRecordDto.block(G1, GATE_NAMES.get(G1),
                    "TRADE_NOT_ALLOWED",
                    "市場狀態限制：" + in.marketRegime(), p);
        }
        return GateTraceRecordDto.pass(G1, GATE_NAMES.get(G1),
                "市場允許交易 (" + in.marketRegime() + ")", p);
    }

    private GateTraceRecordDto gate2ThemeVeto(Input in) {
        Map<String, Object> p = new LinkedHashMap<>();
        ThemeContextDto ctx = in.themeContext();
        BigDecimal min = config.getDecimal("theme.gate.strength_min", new BigDecimal("7.0"));
        p.put("strengthMin", min);

        if (ctx == null) {
            p.put("themeContext", null);
            return GateTraceRecordDto.wait(G2, GATE_NAMES.get(G2),
                    "THEME_CONTEXT_MISSING", "題材資料缺失", p);
        }
        p.put("themeTag", ctx.themeTag());
        p.put("themeStrength", ctx.themeStrength());

        if (ctx.themeStrength() == null) {
            return GateTraceRecordDto.wait(G2, GATE_NAMES.get(G2),
                    "STRENGTH_MISSING", "題材強度資料缺", p);
        }
        if (ctx.themeStrength().compareTo(min) < 0) {
            return GateTraceRecordDto.block(G2, GATE_NAMES.get(G2),
                    "THEME_STRENGTH_BELOW_MIN",
                    "題材強度不足：" + ctx.themeTag() + "/" + ctx.themeStrength(), p);
        }
        return GateTraceRecordDto.pass(G2, GATE_NAMES.get(G2),
                "題材強度合格 " + ctx.themeStrength(), p);
    }

    private GateTraceRecordDto gate3ThemeRotation(Input in) {
        Map<String, Object> p = new LinkedHashMap<>();
        ThemeContextDto ctx = in.themeContext();
        if (ctx == null) {
            return GateTraceRecordDto.wait(G3, GATE_NAMES.get(G3),
                    "THEME_CONTEXT_MISSING", "題材資料缺失", p);
        }
        RotationSignal rot = ctx.rotationSignal();
        TrendStage stage = ctx.trendStage();
        CrowdingRisk crowd = ctx.crowdingRisk();
        p.put("themeTag", ctx.themeTag());
        p.put("rotationSignal", rot);
        p.put("trendStage", stage);
        p.put("crowdingRisk", crowd);

        if (rot == null || rot == RotationSignal.UNKNOWN) {
            return GateTraceRecordDto.wait(G3, GATE_NAMES.get(G3),
                    "ROTATION_UNKNOWN", "題材輪動訊號不明", p);
        }
        if (rot == RotationSignal.OUT) {
            return GateTraceRecordDto.block(G3, GATE_NAMES.get(G3),
                    "THEME_ROTATION_OUT",
                    "題材資金轉出：" + ctx.themeTag(), p);
        }
        // entry_strength_min：只對 rotation=IN 套用（此 config 描述即為 rotation IN 情境下的進場門檻）。
        // NONE 視為 rotation neutral → 直接 PASS，不因 entry threshold 被降級成 WAIT。
        if (rot == RotationSignal.IN) {
            BigDecimal entryMin = config.getDecimal("theme.gate.entry_strength_min", new BigDecimal("7.5"));
            p.put("entryStrengthMin", entryMin);
            p.put("themeStrength", ctx.themeStrength());
            if (ctx.themeStrength() != null && ctx.themeStrength().compareTo(entryMin) < 0) {
                return GateTraceRecordDto.wait(G3, GATE_NAMES.get(G3),
                        "THEME_STRENGTH_BELOW_ENTRY_MIN",
                        "題材強度未達進場門檻：" + ctx.themeStrength() + " < " + entryMin, p);
            }
        }
        return GateTraceRecordDto.pass(G3, GATE_NAMES.get(G3),
                "題材輪動訊號：" + rot + "，強度 " + ctx.themeStrength(), p);
    }

    private GateTraceRecordDto gate4Liquidity(Input in) {
        Map<String, Object> p = new LinkedHashMap<>();
        BigDecimal min = config.getDecimal("theme.gate.min_liquidity_turnover",
                new BigDecimal("30000000"));
        p.put("turnoverMin", min);
        p.put("avgTurnover", in.avgTurnover());
        p.put("volumeRatio", in.volumeRatio());

        if (in.avgTurnover() == null) {
            return GateTraceRecordDto.wait(G4, GATE_NAMES.get(G4),
                    "TURNOVER_MISSING", "成交金額資料缺（stale quote）", p);
        }
        if (in.avgTurnover().compareTo(min) < 0) {
            return GateTraceRecordDto.block(G4, GATE_NAMES.get(G4),
                    "LIQUIDITY_BELOW_MIN",
                    "流動性不足：" + in.symbol() + " (" + in.avgTurnover() + ")", p);
        }
        return GateTraceRecordDto.pass(G4, GATE_NAMES.get(G4),
                "流動性合格", p);
    }

    private GateTraceRecordDto gate5ScoreDivergence(Input in) {
        Map<String, Object> p = new LinkedHashMap<>();
        BigDecimal threshold = config.getDecimal("theme.gate.max_score_divergence",
                new BigDecimal("2.5"));
        p.put("threshold", threshold);
        p.put("java", in.javaScore());
        p.put("claude", in.claudeScore());
        p.put("codex", in.codexScore());

        // Collect non-null scores
        List<BigDecimal> scores = new ArrayList<>(3);
        if (in.javaScore() != null) scores.add(in.javaScore());
        if (in.claudeScore() != null) scores.add(in.claudeScore());
        if (in.codexScore() != null) scores.add(in.codexScore());

        if (scores.size() < 2) {
            return GateTraceRecordDto.wait(G5, GATE_NAMES.get(G5),
                    "SCORES_MISSING", "缺少至少 2 個評分來源", p);
        }

        BigDecimal max = scores.stream().max(BigDecimal::compareTo).orElseThrow();
        BigDecimal min = scores.stream().min(BigDecimal::compareTo).orElseThrow();
        BigDecimal divergence = max.subtract(min);
        p.put("divergence", divergence);

        if (divergence.compareTo(threshold) > 0) {
            return GateTraceRecordDto.block(G5, GATE_NAMES.get(G5),
                    "SCORE_DIVERGENCE_HIGH",
                    "評分分歧過高：" + in.symbol() + " (diff=" + divergence + ")", p);
        }
        return GateTraceRecordDto.pass(G5, GATE_NAMES.get(G5),
                "評分分歧可接受 diff=" + divergence, p);
    }

    private GateTraceRecordDto gate6Rr(Input in) {
        Map<String, Object> p = new LinkedHashMap<>();
        BigDecimal min = config.getDecimal("theme.gate.rr_min", new BigDecimal("2.0"));
        p.put("rrMin", min);
        p.put("rr", in.rr());

        if (in.rr() == null) {
            return GateTraceRecordDto.wait(G6, GATE_NAMES.get(G6),
                    "RR_MISSING", "RR 資料缺", p);
        }
        if (in.rr().compareTo(min) < 0) {
            return GateTraceRecordDto.block(G6, GATE_NAMES.get(G6),
                    "RR_BELOW_MIN",
                    "風報比不足：" + in.symbol() + "/" + in.rr(), p);
        }
        return GateTraceRecordDto.pass(G6, GATE_NAMES.get(G6),
                "RR 合格 " + in.rr(), p);
    }

    private GateTraceRecordDto gate7PositionSizing(Input in) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("openPositions", in.openPositions());
        p.put("maxPositions", in.maxPositions());
        p.put("availableCash", in.availableCash());
        BigDecimal sizeFactor = computeSizeFactor(in.themeContext());
        p.put("themeSizeFactor", sizeFactor);

        if (in.maxPositions() <= 0) {
            return GateTraceRecordDto.wait(G7, GATE_NAMES.get(G7),
                    "PORTFOLIO_CONFIG_MISSING", "portfolio config 未設定", p);
        }
        if (in.openPositions() >= in.maxPositions()) {
            return GateTraceRecordDto.block(G7, GATE_NAMES.get(G7),
                    "PORTFOLIO_FULL",
                    "倉位限制：" + in.symbol() + " (" + in.openPositions() + "/" + in.maxPositions() + ")", p);
        }
        if (in.availableCash() != null && in.availableCash().signum() <= 0) {
            return GateTraceRecordDto.block(G7, GATE_NAMES.get(G7),
                    "CASH_UNAVAILABLE",
                    "無可動用現金", p);
        }
        return GateTraceRecordDto.pass(G7, GATE_NAMES.get(G7),
                "倉位空間 " + in.openPositions() + "/" + in.maxPositions()
                        + " themeSizeFactor=" + sizeFactor, p);
    }

    private GateTraceRecordDto gate8FinalRank(Input in) {
        Map<String, Object> p = new LinkedHashMap<>();
        BigDecimal min = config.getDecimal("theme.gate.final_rank_a_min", new BigDecimal("7.6"));
        p.put("finalRankMin", min);

        BigDecimal multiplier = computeThemeMultiplier(in.themeContext());
        BigDecimal themeFinal = computeThemeFinalScore(in.baseScore(), multiplier);
        p.put("themeMultiplier", multiplier);
        p.put("themeFinalScore", themeFinal);
        p.put("baseScore", in.baseScore());

        if (themeFinal == null) {
            return GateTraceRecordDto.wait(G8, GATE_NAMES.get(G8),
                    "SCORE_MISSING", "最終分數無法計算（baseScore 或 themeContext 缺）", p);
        }
        if (themeFinal.compareTo(min) < 0) {
            return GateTraceRecordDto.block(G8, GATE_NAMES.get(G8),
                    "FINAL_RANK_BELOW_MIN",
                    "最終評級不足：" + in.symbol() + " (" + themeFinal + " < " + min + ")", p);
        }
        return GateTraceRecordDto.pass(G8, GATE_NAMES.get(G8),
                "最終分數合格 " + themeFinal, p);
    }

    // ══════════════════════════════════════════════════════════════════════
    // Derived values（spec §5 / §4.6）
    // ══════════════════════════════════════════════════════════════════════

    /** multiplier = 0.6 + 0.04 × themeStrength ±(EARLY+0.05 / LATE-0.05)。 */
    private BigDecimal computeThemeMultiplier(ThemeContextDto ctx) {
        if (ctx == null || ctx.themeStrength() == null) return null;
        BigDecimal m = new BigDecimal("0.6").add(
                new BigDecimal("0.04").multiply(ctx.themeStrength()));
        TrendStage stage = ctx.trendStage();
        if (stage == TrendStage.EARLY) m = m.add(new BigDecimal("0.05"));
        else if (stage == TrendStage.LATE) m = m.subtract(new BigDecimal("0.05"));
        return m.setScale(4, RoundingMode.HALF_UP);
    }

    /** theme_final_score = clamp(baseScore × multiplier, 0, 10)。 */
    private BigDecimal computeThemeFinalScore(BigDecimal baseScore, BigDecimal multiplier) {
        if (baseScore == null || multiplier == null) return null;
        BigDecimal raw = baseScore.multiply(multiplier);
        if (raw.compareTo(BigDecimal.ZERO) < 0) return BigDecimal.ZERO;
        if (raw.compareTo(BigDecimal.TEN) > 0) return BigDecimal.TEN;
        return raw.setScale(4, RoundingMode.HALF_UP);
    }

    /** spec §4.6：crowding LOW=1.0 / MID=0.85 / HIGH=0.65 / UNKNOWN 保守 0.65。 */
    private BigDecimal computeSizeFactor(ThemeContextDto ctx) {
        if (ctx == null || ctx.crowdingRisk() == null) return new BigDecimal("0.65");
        return switch (ctx.crowdingRisk()) {
            case LOW -> new BigDecimal("1.00");
            case MID -> new BigDecimal("0.85");
            case HIGH, UNKNOWN -> new BigDecimal("0.65");
        };
    }

    private Result computeOverall(List<GateTraceRecordDto> records) {
        boolean hasBlock = false;
        boolean hasWait = false;
        for (GateTraceRecordDto r : records) {
            if (r.result() == Result.BLOCK) hasBlock = true;
            else if (r.result() == Result.WAIT) hasWait = true;
        }
        if (hasBlock) return Result.BLOCK;
        if (hasWait) return Result.WAIT;
        return Result.PASS;
    }

    // ══════════════════════════════════════════════════════════════════════
    // Input record
    // ══════════════════════════════════════════════════════════════════════

    public record Input(
            String symbol,
            String marketRegime,
            boolean tradeAllowed,
            BigDecimal riskMultiplier,
            ThemeContextDto themeContext,
            BigDecimal avgTurnover,
            BigDecimal volumeRatio,
            BigDecimal javaScore,
            BigDecimal claudeScore,
            BigDecimal codexScore,
            BigDecimal rr,
            BigDecimal baseScore,
            int openPositions,
            int maxPositions,
            BigDecimal availableCash
    ) {}
}
