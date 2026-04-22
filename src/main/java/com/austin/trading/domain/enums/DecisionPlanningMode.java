package com.austin.trading.domain.enums;

/**
 * v2.8 決策規劃模式：區分盤中進場與盤後明日規劃。
 *
 * <p>動機：{@code FinalDecisionEngine} 原本統一套用「A+/A/B → ENTER / REST 休息」語意，
 * 但盤後（POSTMARKET / T86_TOMORROW）做的是**明日計畫**，不是「今日要不要進場」。
 * 兩種模式的輸出語意、veto 規則、decisionLock 行為都應不同。</p>
 *
 * <h3>對應規則</h3>
 * <ul>
 *   <li>{@link #INTRADAY_ENTRY}：PREMARKET / OPENING / MIDDAY — 當下進場決策</li>
 *   <li>{@link #POSTCLOSE_TOMORROW_PLAN}：POSTMARKET / T86_TOMORROW — 盤後為明日準備</li>
 * </ul>
 */
public enum DecisionPlanningMode {

    /** 盤中進場模式：走 A+/A/B bucket、受 session gate、受 decisionLock 管控。 */
    INTRADAY_ENTRY,

    /** 盤後規劃模式：輸出 primary/backup/sectorIndicators/avoidSymbols，忽略 decisionLock。 */
    POSTCLOSE_TOMORROW_PLAN;

    /**
     * 依 AI task type 判斷規劃模式。
     *
     * @param taskType PREMARKET / OPENING / MIDDAY / POSTMARKET / T86_TOMORROW
     * @return 對應 mode；未知或 null 預設 INTRADAY_ENTRY（向下相容）
     */
    public static DecisionPlanningMode fromTaskType(String taskType) {
        if (taskType == null) return INTRADAY_ENTRY;
        String t = taskType.trim().toUpperCase();
        return switch (t) {
            case "POSTMARKET", "T86_TOMORROW" -> POSTCLOSE_TOMORROW_PLAN;
            default -> INTRADAY_ENTRY;  // PREMARKET / OPENING / MIDDAY
        };
    }

    public boolean isPostClosePlanning() {
        return this == POSTCLOSE_TOMORROW_PLAN;
    }
}
