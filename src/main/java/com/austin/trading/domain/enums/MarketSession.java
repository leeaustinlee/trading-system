package com.austin.trading.domain.enums;

import java.time.LocalTime;

/**
 * v2.7 Session-Aware 時間分層。
 *
 * <p>台股盤中時段切分為三階段，決定系統各模組在對應階段允許哪些行為。
 * 動機：08:30-09:00 的試撮資料不具真實成交意義，
 * 用它做 scoring / decision 會產生假突破 / 假跌破，導致系統誤判。</p>
 *
 * <h3>階段定義</h3>
 * <ul>
 *   <li>{@link #PREMARKET}（08:30-09:00）：試撮時段。
 *       允許：bias / 題材 / 候選名單；
 *       禁止：FinalDecision 出 ENTER/REST、用試撮價做 scoring 旗標。</li>
 *   <li>{@link #OPEN_VALIDATION}（09:00-09:30）：開盤驗證時段。
 *       允許：用真實成交資料更新 scoring / regime；
 *       禁止：FinalDecision 出 ENTER/REST（但允許停損觸發等既有規則）。</li>
 *   <li>{@link #LIVE_TRADING}（09:30 起）：正式交易時段。
 *       允許：完整 FinalDecisionEngine 管線、進出場決策、持倉動態調整。</li>
 * </ul>
 *
 * <h3>注意</h3>
 * <ul>
 *   <li>切點由 Austin 於 2026-04-22 拍板：OPEN_VALIDATION 終點定於 09:30（非 09:20 / 09:25），
 *       對齊 {@code FinalDecision0930Job} cron 與 Austin 手動操盤的「09:30 strategy」習慣。</li>
 *   <li>PREMARKET 開始時點目前未硬限在 08:30（即 08:00-08:30 也視為 PREMARKET），
 *       若未來需排除過早批次可改為條件式。</li>
 *   <li>`null` 時間傳入 → 回傳 {@link #LIVE_TRADING}（保守 fallback，避免 NPE 導致系統停擺）。</li>
 * </ul>
 */
public enum MarketSession {

    PREMARKET,
    OPEN_VALIDATION,
    LIVE_TRADING,
    MIDDAY_REVIEW,
    POSTMARKET,
    TOMORROW_PLAN;

    /** 09:00：PREMARKET → OPEN_VALIDATION 切點。 */
    public static final LocalTime OPEN_VALIDATION_START = LocalTime.of(9, 0);

    /** 09:30：OPEN_VALIDATION → LIVE_TRADING 切點（Austin 2026-04-22 拍板）。 */
    public static final LocalTime LIVE_TRADING_START    = LocalTime.of(9, 30);

    /**
     * 依給定時間判斷所處 session。
     *
     * @param now 要判斷的時刻；null 回傳 LIVE_TRADING 作 fallback
     */
    public static MarketSession fromTime(LocalTime now) {
        if (now == null) return LIVE_TRADING;
        if (now.isBefore(OPEN_VALIDATION_START)) return PREMARKET;
        if (now.isBefore(LIVE_TRADING_START))    return OPEN_VALIDATION;
        return LIVE_TRADING;
    }

    /** 是否允許 {@code FinalDecisionEngine} 輸出 ENTER / REST（反之須回 WAIT）。 */
    public boolean allowsFinalDecision() {
        return this == LIVE_TRADING;
    }

    /** 是否允許用真實成交資料更新 candidate scoring（PREMARKET 禁止）。 */
    public boolean allowsScoringUpdate() {
        return this != PREMARKET;
    }

    /** 是否允許對 belowOpen / belowPrevClose 等欄位做 hard block 決策。 */
    public boolean allowsPriceGateHardBlock() {
        return this == LIVE_TRADING;
    }

    public static MarketSession fromTaskType(String taskType, LocalTime now) {
        if (taskType == null || taskType.isBlank()) {
            return fromTime(now);
        }
        return switch (taskType.toUpperCase()) {
            case "PREMARKET" -> PREMARKET;
            case "OPENING" -> fromTime(now);
            case "MIDDAY" -> MIDDAY_REVIEW;
            case "POSTMARKET" -> POSTMARKET;
            case "T86_TOMORROW" -> TOMORROW_PLAN;
            default -> fromTime(now);
        };
    }
}
