package com.austin.trading.dto.internal;

public record ThemeAdmissionWriteSummary(
        int processedSignals,
        int admittedCandidates,
        int admittedWatchlists,
        int skippedLimitRisk,
        int skippedAlreadyExists,
        int rejectedBadData,
        int rejectedLiquidity,
        int rejectedWeakTheme,
        int shadowOnly,
        boolean productionBuyImpact
) {
    public static ThemeAdmissionWriteSummary empty() {
        return new ThemeAdmissionWriteSummary(0, 0, 0, 0, 0, 0, 0, 0, 0, false);
    }
}
