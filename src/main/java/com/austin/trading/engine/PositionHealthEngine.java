package com.austin.trading.engine;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Component
public class PositionHealthEngine {

    public PositionHealthResult evaluate(PositionHealthInput in) {
        List<String> reasons = new ArrayList<>();
        List<String> gaps = new ArrayList<>();
        int score = 50;

        if (in == null || in.currentPrice() == null) {
            return new PositionHealthResult(0, "UNKNOWN", "UNKNOWN", "UNKNOWN", "UNKNOWN",
                    PositionHealthResult.ExitTier.EXIT_CONFIRM_REQUIRED,
                    List.of("DATA_GAP: current price missing"), List.of("DATA_GAP: current price missing"));
        }

        String structure = "NEUTRAL";
        if (in.ma5() == null || in.ma10() == null || in.ma20() == null) {
            gaps.add("DATA_GAP: MA5/MA10/MA20 insufficient");
            structure = "UNKNOWN";
        } else {
            boolean bull = in.currentPrice().compareTo(in.ma5()) >= 0
                    && in.ma5().compareTo(in.ma10()) >= 0
                    && in.ma10().compareTo(in.ma20()) >= 0;
            boolean belowMa10 = in.currentPrice().compareTo(in.ma10()) < 0;
            boolean belowPrevLow = in.previousLow() != null && in.currentPrice().compareTo(in.previousLow()) < 0;
            boolean ma5Down = in.ma5Previous() != null && in.ma5().compareTo(in.ma5Previous()) < 0;
            if (bull) {
                structure = "BULL_ALIGNED";
                score += 20;
                reasons.add("price_above_ma5_ma10_ma20");
            } else if (belowPrevLow) {
                structure = "PREVIOUS_LOW_BREAK";
                score -= 30;
                reasons.add("below_previous_low");
            } else if (belowMa10) {
                structure = "MA10_BREAK";
                score -= 20;
                reasons.add("below_ma10");
            } else if (in.currentPrice().compareTo(in.ma5()) < 0) {
                structure = ma5Down ? "MA5_BREAK_SLOPE_DOWN" : "MA5_BREAK";
                score -= ma5Down ? 15 : 10;
                reasons.add("below_ma5");
            }
            if (in.recentHigh() != null && in.currentPrice().compareTo(in.recentHigh()) >= 0) {
                score += 5;
                reasons.add("recent_high");
            }
        }

        String volume = "UNKNOWN";
        if (in.volumeRatio() == null) {
            gaps.add("DATA_GAP: volume ratio missing");
        } else if (in.volumeRatio().compareTo(new BigDecimal("1.8")) >= 0 && structure.contains("BREAK")) {
            volume = "VOLUME_BREAKDOWN";
            score -= 20;
            reasons.add("volume_expansion_on_break");
        } else if (in.volumeRatio().compareTo(new BigDecimal("1.3")) >= 0 && !structure.contains("BREAK")) {
            volume = "RISING_VOLUME";
            score += 10;
            reasons.add("rising_volume");
        } else if (in.volumeRatio().compareTo(new BigDecimal("0.75")) <= 0 && structure.contains("BREAK")) {
            volume = "LOW_VOLUME_PULLBACK";
            score += 5;
            reasons.add("low_volume_pullback");
        } else {
            volume = "NORMAL";
        }

        String rs = "UNKNOWN";
        if (in.stockReturn5d() == null || in.benchmarkReturn5d() == null) {
            gaps.add("DATA_GAP: relative strength 5D missing");
        } else {
            BigDecimal spread = in.stockReturn5d().subtract(in.benchmarkReturn5d());
            if (spread.compareTo(new BigDecimal("3.0")) >= 0) {
                rs = "OUTPERFORM";
                score += 10;
                reasons.add("relative_strength_outperform");
            } else if (spread.compareTo(new BigDecimal("-3.0")) <= 0) {
                rs = "UNDERPERFORM";
                score -= 10;
                reasons.add("relative_strength_underperform");
            } else {
                rs = "INLINE";
            }
        }

        String chip = in.chipStatus() == null || in.chipStatus().isBlank() ? "UNKNOWN" : in.chipStatus();
        if ("UNKNOWN".equals(chip)) gaps.add("DATA_GAP: chip data missing");
        if ("COOLING".equalsIgnoreCase(in.themeStage())) {
            score -= 10;
            reasons.add("theme_cooling");
        } else if (Boolean.TRUE.equals(in.mainstreamTheme())) {
            score += 5;
            reasons.add("mainstream_theme");
        } else if (in.mainstreamTheme() == null) {
            gaps.add("DATA_GAP: mainstream theme mapping missing");
        }

        score = Math.max(0, Math.min(100, score));
        PositionHealthResult.ExitTier tier = score >= 70 ? PositionHealthResult.ExitTier.HOLD
                : score >= 55 ? PositionHealthResult.ExitTier.SOFT_WARNING
                : score >= 40 ? PositionHealthResult.ExitTier.REDUCE
                : score >= 25 ? PositionHealthResult.ExitTier.EXIT_CONFIRM_REQUIRED
                : PositionHealthResult.ExitTier.HARD_EXIT;
        return new PositionHealthResult(score, structure, volume, rs, chip, tier,
                reasons.isEmpty() ? List.of("no_positive_or_negative_signal") : reasons, gaps);
    }
}
