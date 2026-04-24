package com.austin.trading.engine;

import com.austin.trading.domain.enums.MarketSession;
import com.austin.trading.dto.internal.PriceGateDecision;
import com.austin.trading.dto.request.FinalDecisionCandidateRequest;
import com.austin.trading.service.ScoreConfigService;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * v2.9 Gate 6/7 Refactor：belowOpen / belowPrevClose 條件式 hard block。
 *
 * <p>背景：Codex Execution Reality Checker MVP 後，SELECT_BUY_NOW 已不受 soft penalty。
 * 但 belowOpen / belowPrevClose 一律 hard block 仍會誤殺「強勢股開盤洗盤站回」。
 * 本 evaluator 將 boolean hard block 改為 PASS / WAIT / BLOCK 三態。</p>
 *
 * <h3>判斷規則（簡）</h3>
 * <ul>
 *   <li>session != LIVE_TRADING → PASS（盤前 / 開盤驗證不做 hard block）</li>
 *   <li>belowOpen=false && belowPrevClose=false → PASS</li>
 *   <li>belowPrevClose + (BEAR / PANIC) → BLOCK</li>
 *   <li>belowPrevClose + 非 BULL_TREND → BLOCK</li>
 *   <li>belowPrevClose + BULL_TREND + 深跌 (>= bull_shallow_drop_pct_threshold) → BLOCK</li>
 *   <li>belowOpen + (BEAR / PANIC) → BLOCK</li>
 *   <li>belowOpen + 跌破 VWAP + 量縮 + 遠離開盤 → BLOCK（四條件同時成立）</li>
 *   <li>其他（疑似洗盤 / VWAP 缺資 / BULL 淺跌）→ WAIT</li>
 * </ul>
 *
 * <p>沒有 VWAP 時保守 fallback：belowOpen → WAIT，不直接 REST；只有在具備其他明確弱勢
 * 訊號（bear regime / 深跌昨收）時才 BLOCK。</p>
 */
@Component
public class PriceGateEvaluator {

    private static final BigDecimal DEFAULT_LOW_VOLUME_RATIO   = new BigDecimal("0.8");
    private static final BigDecimal DEFAULT_FAR_FROM_OPEN_PCT  = new BigDecimal("0.01");
    private static final BigDecimal DEFAULT_BULL_SHALLOW_DROP  = new BigDecimal("0.01");

    private final ScoreConfigService config;

    public PriceGateEvaluator(ScoreConfigService config) {
        this.config = config;
    }

    public PriceGateDecision evaluate(FinalDecisionCandidateRequest c, MarketSession session) {
        Map<String, Object> trace = baseTrace(c);

        if (session == null || !session.allowsPriceGateHardBlock()) {
            trace.put("priceGateDecision", "PASS");
            trace.put("priceGateReason", "SESSION_NOT_LIVE_TRADING");
            trace.put("priceGateHardBlock", false);
            return PriceGateDecision.pass(trace);
        }

        boolean belowOpen       = Boolean.TRUE.equals(c.belowOpen());
        boolean belowPrevClose  = Boolean.TRUE.equals(c.belowPrevClose());
        trace.put("belowOpen", belowOpen);
        trace.put("belowPrevClose", belowPrevClose);

        if (!belowOpen && !belowPrevClose) {
            trace.put("priceGateDecision", "PASS");
            trace.put("priceGateReason", "NO_PRICE_WEAKNESS");
            trace.put("priceGateHardBlock", false);
            return PriceGateDecision.pass(trace);
        }

        // ── 取 config 門檻 ─────────────────────────────────────────────
        BigDecimal lowVolumeThreshold = config.getDecimal(
                "trading.price_gate.low_volume_ratio_threshold", DEFAULT_LOW_VOLUME_RATIO);
        BigDecimal farFromOpenThreshold = config.getDecimal(
                "trading.price_gate.far_from_open_pct_threshold", DEFAULT_FAR_FROM_OPEN_PCT);
        BigDecimal bullShallowThreshold = config.getDecimal(
                "trading.price_gate.bull_shallow_drop_pct_threshold", DEFAULT_BULL_SHALLOW_DROP);

        // ── 推導輔助 signals ──────────────────────────────────────────
        String regime = c.marketRegime() == null ? "" : c.marketRegime().toUpperCase(Locale.ROOT);
        boolean bullTrend   = "BULL_TREND".equals(regime);
        boolean bearOrPanic = "PANIC_VOLATILITY".equals(regime) || "WEAK_DOWNTREND".equals(regime);

        boolean hasVwap = c.vwapPrice() != null && c.currentPrice() != null
                && c.vwapPrice().signum() > 0 && c.currentPrice().signum() > 0;
        boolean belowVwap = hasVwap && c.currentPrice().compareTo(c.vwapPrice()) < 0;

        boolean hasVolumeRatio = c.volumeRatio() != null;
        boolean lowVolume = hasVolumeRatio && c.volumeRatio().compareTo(lowVolumeThreshold) < 0;

        BigDecimal distAbs = c.distanceFromOpenPct() == null ? null : c.distanceFromOpenPct().abs();
        boolean farFromOpen = distAbs != null && distAbs.compareTo(farFromOpenThreshold) > 0;

        BigDecimal dropAbs = c.dropFromPrevClosePct() == null ? null : c.dropFromPrevClosePct().abs();
        boolean deepDrop = dropAbs != null && dropAbs.compareTo(bullShallowThreshold) >= 0;

        trace.put("marketRegime", regime.isEmpty() ? null : regime);
        trace.put("bullTrend", bullTrend);
        trace.put("bearOrPanic", bearOrPanic);
        trace.put("vwapAvailable", hasVwap);
        trace.put("belowVwap", belowVwap);
        trace.put("volumeRatioAvailable", hasVolumeRatio);
        trace.put("lowVolume", lowVolume);
        trace.put("farFromOpen", farFromOpen);
        trace.put("deepDropBelowPrev", deepDrop);
        trace.put("lowVolumeThreshold", lowVolumeThreshold);
        trace.put("farFromOpenThreshold", farFromOpenThreshold);
        trace.put("bullShallowThreshold", bullShallowThreshold);

        // ── Rule 1：belowPrevClose 確認弱勢 → BLOCK ─────────────────────
        if (belowPrevClose) {
            if (bearOrPanic) {
                return blockWith(trace, "BELOW_PREV_CLOSE_BEAR_OR_PANIC");
            }
            if (!bullTrend) {
                return blockWith(trace, "BELOW_PREV_CLOSE_NOT_BULL");
            }
            if (deepDrop) {
                return blockWith(trace, "BELOW_PREV_CLOSE_DEEP_DROP");
            }
            // BULL_TREND + 淺跌（或未知深度）→ 繼續看 belowOpen / 落入 WAIT
        }

        // ── Rule 2：belowOpen 確認弱勢 → BLOCK ─────────────────────────
        if (belowOpen) {
            if (bearOrPanic) {
                return blockWith(trace, "BELOW_OPEN_BEAR_OR_PANIC");
            }
            if (hasVwap && belowVwap && lowVolume && farFromOpen) {
                return blockWith(trace, "BELOW_OPEN_BELOW_VWAP_LOW_VOLUME");
            }
        }

        // ── Rule 3：其他情況 → WAIT（不讓候選消失）──────────────────────
        return waitWith(trace, "PRICE_GATE_WAIT_CONFIRMATION");
    }

    private Map<String, Object> baseTrace(FinalDecisionCandidateRequest c) {
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("currentPrice", c.currentPrice());
        trace.put("openPrice", c.openPrice());
        trace.put("previousClose", c.previousClose());
        trace.put("vwapPrice", c.vwapPrice());
        trace.put("volumeRatio", c.volumeRatio());
        trace.put("distanceFromOpenPct", c.distanceFromOpenPct());
        trace.put("dropFromPrevClosePct", c.dropFromPrevClosePct());
        return trace;
    }

    private PriceGateDecision blockWith(Map<String, Object> trace, String reason) {
        trace.put("priceGateDecision", "BLOCK");
        trace.put("priceGateReason", reason);
        trace.put("priceGateHardBlock", true);
        return PriceGateDecision.block(reason, trace);
    }

    private PriceGateDecision waitWith(Map<String, Object> trace, String reason) {
        trace.put("priceGateDecision", "WAIT");
        trace.put("priceGateReason", reason);
        trace.put("priceGateHardBlock", false);
        return PriceGateDecision.wait(reason, trace);
    }
}
