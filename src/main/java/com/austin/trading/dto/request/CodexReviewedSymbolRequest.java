package com.austin.trading.dto.request;

import java.util.List;

public record CodexReviewedSymbolRequest(
        String symbol,
        String bucket,
        Double currentPrice,
        Double openPrice,
        Double previousClose,
        Double dayHigh,
        Double dayLow,
        Long volume,
        Double turnover,
        Double entryZoneLow,
        Double entryZoneHigh,
        Double stopLoss,
        Double targetPrice,
        Double realTimeRR,
        Boolean thesisStillValid,
        List<String> reasons,
        List<String> issues,
        String suggestedAction,
        String positionMode,
        /** v2.9.1 Gate 6/7 強化：Codex 研究時提供的 5/20 日平均日成交張數，用來算 volumeRatio。可 null。 */
        Long averageDailyVolume
) {
    /** Legacy 19-arg constructor（v2.9 之前 payload 無 averageDailyVolume 時使用）。 */
    public CodexReviewedSymbolRequest(
            String symbol,
            String bucket,
            Double currentPrice,
            Double openPrice,
            Double previousClose,
            Double dayHigh,
            Double dayLow,
            Long volume,
            Double turnover,
            Double entryZoneLow,
            Double entryZoneHigh,
            Double stopLoss,
            Double targetPrice,
            Double realTimeRR,
            Boolean thesisStillValid,
            List<String> reasons,
            List<String> issues,
            String suggestedAction,
            String positionMode
    ) {
        this(symbol, bucket, currentPrice, openPrice, previousClose, dayHigh, dayLow,
                volume, turnover, entryZoneLow, entryZoneHigh, stopLoss, targetPrice,
                realTimeRR, thesisStillValid, reasons, issues, suggestedAction, positionMode,
                null);
    }
}
