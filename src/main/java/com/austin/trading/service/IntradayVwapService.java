package com.austin.trading.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * v2.9.1 Gate 6/7 強化：盤中 VWAP 估算。
 *
 * <h3>為什麼不直接建 intraday bar entity</h3>
 * <p>系統目前沒有 5 秒 / 1 分 / 5 分 bar 的持久化；TWSE MIS API 呼叫端（{@code TwseMisClient}）
 * 也只回當下累計量（{@code v}）、當前價（{@code z}），不提供當 bar 成交金額。
 * 但 Codex Execution Reality Checker 的輸出（{@code CodexReviewedSymbolRequest}）已經帶了
 * {@code volume}（累計張數）與 {@code turnover}（累計成交金額，元）。
 * 由於 turnover 與 volume 都是整日累計，兩者相除得到的就是「當下 running VWAP」，
 * 精度完全等同於以 tick 加權計算，僅「時間點」解析度受制於 Codex 快照。</p>
 *
 * <h3>單位</h3>
 * <ul>
 *   <li>volume：張 (lot)，1 張 = 1000 股</li>
 *   <li>turnover：元 (TWD)，代表累計成交金額 Σ(price × shares)</li>
 *   <li>vwap = turnover / (volume × 1000)，單位：元/股</li>
 * </ul>
 *
 * <p>MVP 限制：未取外部 API、未 async、不改主流程；純從 in-memory 輸入算。</p>
 */
@Service
public class IntradayVwapService {

    private static final Logger log = LoggerFactory.getLogger(IntradayVwapService.class);
    private static final BigDecimal SHARES_PER_LOT = new BigDecimal("1000");

    /**
     * 從累計 turnover / volume 估算 VWAP。
     *
     * @param cumulativeVolumeLots 當下累計成交張數（TWSE 單位：張）；null / ≤0 → unavailable
     * @param cumulativeTurnover   當下累計成交金額（TWD）；null / ≤0 → unavailable
     */
    public VwapResult computeFromCumulative(Long cumulativeVolumeLots, Double cumulativeTurnover) {
        if (cumulativeVolumeLots == null || cumulativeVolumeLots <= 0L) {
            return VwapResult.unavailable("MISSING_VOLUME");
        }
        if (cumulativeTurnover == null || cumulativeTurnover <= 0.0) {
            return VwapResult.unavailable("MISSING_TURNOVER");
        }
        BigDecimal shares = BigDecimal.valueOf(cumulativeVolumeLots).multiply(SHARES_PER_LOT);
        BigDecimal vwap = BigDecimal.valueOf(cumulativeTurnover)
                .divide(shares, 4, RoundingMode.HALF_UP);
        return new VwapResult(vwap, "cumulative-mis-turnover", true, null);
    }

    /** 用 bar 資料算 VWAP 的預留入口；目前無 bar 來源一律回 unavailable。 */
    public VwapResult computeFromBars(String symbol) {
        log.debug("[IntradayVwapService] bar source not implemented yet, symbol={}", symbol);
        return VwapResult.unavailable("NO_INTRADAY_BAR_SOURCE");
    }

    public record VwapResult(BigDecimal price, String source, boolean available, String reason) {
        public static VwapResult unavailable(String reason) {
            return new VwapResult(null, "UNAVAILABLE", false, reason);
        }
    }
}
