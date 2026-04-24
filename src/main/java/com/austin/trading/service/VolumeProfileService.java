package com.austin.trading.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalTime;

/**
 * v2.9.1 Gate 6/7 強化：volumeRatio 計算（當下累計 / 預期累計）。
 *
 * <h3>預期量模型</h3>
 * <p>系統目前沒有儲存任何 intraday bar，也沒有日級歷史 volume table，
 * 所以「前 5 日同時間點平均」(spec 首選方案) 不可行。
 * MVP 採 <strong>linear-pace</strong> 折衷：假設整日交易量均勻累積，
 * 到 T 分鐘時的預期累計量 = {@code avgDailyVolume × elapsedMinutes / totalTradingMinutes}。</p>
 *
 * <p>{@code avgDailyVolume}（前 5 或 20 日日均量）由 Codex payload 攜帶進來；
 * 若 Codex 未提供（舊 payload） → {@link VolumeRatioResult#unavailable(String) unavailable}，
 * PriceGateEvaluator fallback 為 WAIT，不會誤 BLOCK。</p>
 *
 * <h3>交易時段</h3>
 * <ul>
 *   <li>台股盤：09:00 – 13:30，共 270 分鐘</li>
 *   <li>09:00 前 or 13:30 後呼叫：elapsedMinutes clamp 到 [1, 270]</li>
 * </ul>
 */
@Service
public class VolumeProfileService {

    private static final Logger log = LoggerFactory.getLogger(VolumeProfileService.class);

    public static final LocalTime SESSION_OPEN  = LocalTime.of(9, 0);
    public static final LocalTime SESSION_CLOSE = LocalTime.of(13, 30);
    public static final int TOTAL_TRADING_MINUTES = 270;

    /**
     * @param currentCumulativeLots 當下累計成交張數（TWSE 單位：張）；null / ≤0 → unavailable
     * @param avgDailyVolumeLots    Codex 提供的日均量（5/20 日），單位張；null / ≤0 → unavailable
     * @param now                   當下時間（Asia/Taipei）；null → 用目前秒
     */
    public VolumeRatioResult compute(Long currentCumulativeLots, Long avgDailyVolumeLots, LocalTime now) {
        if (currentCumulativeLots == null || currentCumulativeLots <= 0L) {
            return VolumeRatioResult.unavailable("MISSING_CURRENT_VOLUME");
        }
        if (avgDailyVolumeLots == null || avgDailyVolumeLots <= 0L) {
            return VolumeRatioResult.unavailable("MISSING_AVG_DAILY_VOLUME");
        }

        int elapsedMin = elapsedTradingMinutes(now);
        if (elapsedMin <= 0) {
            return VolumeRatioResult.unavailable("BEFORE_SESSION_OPEN");
        }
        long expected = Math.round(avgDailyVolumeLots * (elapsedMin / (double) TOTAL_TRADING_MINUTES));
        if (expected <= 0L) {
            return VolumeRatioResult.unavailable("EXPECTED_ZERO");
        }

        BigDecimal ratio = BigDecimal.valueOf(currentCumulativeLots)
                .divide(BigDecimal.valueOf(expected), 4, RoundingMode.HALF_UP);
        return new VolumeRatioResult(ratio, currentCumulativeLots, expected,
                "linear-pace", elapsedMin, true, null);
    }

    static int elapsedTradingMinutes(LocalTime now) {
        if (now == null) return TOTAL_TRADING_MINUTES;
        if (now.isBefore(SESSION_OPEN)) return 0;
        if (!now.isBefore(SESSION_CLOSE)) return TOTAL_TRADING_MINUTES;
        return (int) java.time.Duration.between(SESSION_OPEN, now).toMinutes();
    }

    public record VolumeRatioResult(
            BigDecimal ratio,
            Long currentVolume,
            Long expectedVolume,
            String source,
            int elapsedMinutes,
            boolean available,
            String reason
    ) {
        public static VolumeRatioResult unavailable(String reason) {
            return new VolumeRatioResult(null, null, null, "UNAVAILABLE", 0, false, reason);
        }
    }
}
