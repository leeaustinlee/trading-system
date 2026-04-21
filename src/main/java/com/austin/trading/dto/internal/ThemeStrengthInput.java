package com.austin.trading.dto.internal;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Input to {@link com.austin.trading.engine.ThemeStrengthEngine}.
 * Built by {@link com.austin.trading.service.ThemeStrengthService} from
 * {@code ThemeSnapshotEntity} fields.
 */
public record ThemeStrengthInput(
        LocalDate tradingDate,
        String themeTag,
        /** Java-computed market behavior score (0-10). */
        BigDecimal marketBehaviorScore,
        /** Breadth proxy: ratio of strong-stock count to total mapped stocks (0-10). */
        BigDecimal breadthScore,
        /** Average price change % of theme members. */
        BigDecimal averagePriceChangePct,
        /** Claude heat score (0-10, may be null). */
        BigDecimal claudeHeatScore,
        /** Claude continuation score (0-10, may be null). */
        BigDecimal claudeContinuationScore,
        /** Theme catalyst type (법說/政策/事件/報價/籌碼). */
        String catalystType,
        /** True when riskSummary is non-blank. */
        boolean hasRiskFlag
) {}
