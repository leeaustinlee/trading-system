package com.austin.trading.engine;

import java.math.BigDecimal;

public record PositionHealthInput(
        String symbol,
        BigDecimal entryPrice,
        BigDecimal currentPrice,
        BigDecimal ma5,
        BigDecimal ma10,
        BigDecimal ma20,
        BigDecimal ma5Previous,
        BigDecimal previousLow,
        BigDecimal recentHigh,
        BigDecimal atr,
        BigDecimal volumeRatio,
        BigDecimal stockReturn5d,
        BigDecimal benchmarkReturn5d,
        BigDecimal stockReturn10d,
        BigDecimal benchmarkReturn10d,
        String themeStage,
        Boolean mainstreamTheme,
        String chipStatus
) {}
