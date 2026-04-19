package com.austin.trading.service;

import java.util.Arrays;
import java.util.Optional;

/**
 * 排程步驟列舉，將 step 名稱映射到 {@link com.austin.trading.entity.DailyOrchestrationStatusEntity} 的欄位。
 *
 * <p>同時支援多個別名，方便 REST API / manual trigger 以字串指定 step。</p>
 */
public enum OrchestrationStep {

    PREMARKET_DATA_PREP("stepPremarketDataPrep", "premarket-data-prep", "PremarketDataPrepJob"),
    PREMARKET_NOTIFY("stepPremarketNotify", "premarket-notify", "premarket", "PremarketNotifyJob"),
    OPEN_DATA_PREP("stepOpenDataPrep", "open-data-prep", "OpenDataPrepJob"),
    FINAL_DECISION("stepFinalDecision", "final-decision", "FinalDecision0930Job"),
    HOURLY_GATE("stepHourlyGate", "hourly-gate", "HourlyIntradayGateJob"),
    FIVE_MINUTE_MONITOR("stepFiveMinuteMonitor", "five-minute-monitor", "FiveMinuteMonitorJob"),
    MIDDAY_REVIEW("stepMiddayReview", "midday-review", "MiddayReviewJob"),
    AFTERMARKET_REVIEW("stepAftermarketReview", "aftermarket-review", "AftermarketReview1400Job"),
    POSTMARKET_DATA_PREP("stepPostmarketDataPrep", "postmarket-data-prep", "PostmarketDataPrepJob"),
    POSTMARKET_ANALYSIS("stepPostmarketAnalysis", "postmarket-analysis", "postmarket", "PostmarketAnalysis1530Job"),
    WATCHLIST_REFRESH("stepWatchlistRefresh", "watchlist-refresh", "WatchlistRefreshJob"),
    T86_DATA_PREP("stepT86DataPrep", "t86-data-prep", "T86DataPrepJob"),
    TOMORROW_PLAN("stepTomorrowPlan", "tomorrow-plan", "TomorrowPlan1800Job"),
    EXTERNAL_PROBE_HEALTH("stepExternalProbeHealth", "external-probe-health", "ExternalProbeHealthJob"),
    WEEKLY_TRADE_REVIEW("stepWeeklyTradeReview", "weekly-review", "weekly-trade-review", "WeeklyTradeReviewJob");

    /** JPA Entity 欄位名（camelCase）。 */
    private final String entityField;
    /** 常見別名：scheduler trigger key、job class 名稱等。 */
    private final String[] aliases;

    OrchestrationStep(String entityField, String... aliases) {
        this.entityField = entityField;
        this.aliases = aliases;
    }

    public String entityField() {
        return entityField;
    }

    public String[] aliases() {
        return aliases;
    }

    /**
     * 以字串 key 尋找對應的 step：可吃 enum name、entityField、trigger key、Job class 名稱。
     */
    public static Optional<OrchestrationStep> fromKey(String key) {
        if (key == null || key.isBlank()) return Optional.empty();
        String k = key.trim();
        return Arrays.stream(values())
                .filter(s ->
                        s.name().equalsIgnoreCase(k) ||
                        s.entityField.equalsIgnoreCase(k) ||
                        Arrays.stream(s.aliases).anyMatch(a -> a.equalsIgnoreCase(k)))
                .findFirst();
    }

    /**
     * 這些 step 一天會執行多次（intraday 類）。
     * {@code markRunning} 不會擋 DONE 狀態，每次執行都記錄最新 updatedAt。
     */
    public boolean isRepeatable() {
        return this == FIVE_MINUTE_MONITOR
                || this == HOURLY_GATE
                || this == EXTERNAL_PROBE_HEALTH;
    }
}
