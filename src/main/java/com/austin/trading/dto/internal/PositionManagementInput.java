package com.austin.trading.dto.internal;

import com.austin.trading.domain.enums.PositionSizeLevel;
import com.austin.trading.engine.PositionDecisionEngine.PositionStatus;

import java.math.BigDecimal;
import java.util.List;

/**
 * PositionManagementEngine 的 input bundle（v2.10 MVP）。
 *
 * <h3>欄位語意</h3>
 * <ul>
 *   <li>底層訊號：{@code baselineStatus}（PositionDecisionEngine.STRONG/HOLD/WEAKEN/EXIT/TRAIL_UP）、
 *       {@code marketRegime}（BULL_TREND / RANGE_CHOP / WEAK_DOWNTREND / PANIC_VOLATILITY）</li>
 *   <li>基礎量價：{@code currentPrice}、{@code entryPrice}、{@code stopLoss}、{@code trailingStop}、
 *       {@code sessionHigh}（當日高）、{@code peakUnrealizedPct}（歷史浮盈高點，可 null）</li>
 *   <li>盤中強化：{@code vwapPrice}、{@code volumeRatio}（皆可 null；缺就 fallback 保守行為）</li>
 *   <li>倉位分級：{@code positionSizeLevel}（TRIAL/NORMAL/CORE；預設 NORMAL）</li>
 *   <li>加碼次數：{@code todayAddCount}、{@code lifetimeAddCount}（MVP 用來限縮 ADD 頻率）</li>
 *   <li>換股候選：{@code switchCandidates}（可 null / 空；有值才評估 SWITCH_HINT）</li>
 *   <li>本倉基礎分：{@code currentPositionScore}（持倉當初 finalRankScore，用於換股比較；可 null）</li>
 * </ul>
 */
public record PositionManagementInput(
        String symbol,
        PositionStatus baselineStatus,
        String marketRegime,
        BigDecimal currentPrice,
        BigDecimal entryPrice,
        BigDecimal stopLoss,
        BigDecimal trailingStop,
        BigDecimal sessionHigh,
        BigDecimal peakUnrealizedPct,
        BigDecimal vwapPrice,
        BigDecimal volumeRatio,
        PositionSizeLevel positionSizeLevel,
        int todayAddCount,
        int lifetimeAddCount,
        BigDecimal currentPositionScore,
        String positionTheme,
        List<SwitchCandidate> switchCandidates
) {
    /**
     * 換股候選精簡視圖。
     */
    public record SwitchCandidate(
            String symbol,
            BigDecimal finalRankScore,
            String bucket,        // SELECT_BUY_NOW / CONVERT_BUY / WATCH_ONLY 等
            String themeTag,
            boolean mainStream
    ) {}
}
