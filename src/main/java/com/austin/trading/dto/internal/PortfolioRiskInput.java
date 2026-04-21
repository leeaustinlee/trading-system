package com.austin.trading.dto.internal;

import com.austin.trading.entity.PositionEntity;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Input to {@link com.austin.trading.engine.PortfolioRiskEngine} for a single candidate.
 *
 * <p>The portfolio-gate variant (symbol = null) is created by
 * {@link com.austin.trading.service.PortfolioRiskService} using only
 * {@code openPositions}, {@code maxOpenPositions}, and {@code allowWhenFullStrong}.</p>
 */
public record PortfolioRiskInput(
        /** Null for portfolio-gate evaluation; non-null for per-candidate evaluation. */
        RankedCandidate     candidate,

        List<PositionEntity> openPositions,
        int                  maxOpenPositions,
        boolean              allowWhenFullStrong,

        /** themeTag → current exposure % of total portfolio cost. Built by ThemeExposureService. */
        Map<String, BigDecimal> themeExposureMap,

        BigDecimal maxThemeExposurePct
) {}
