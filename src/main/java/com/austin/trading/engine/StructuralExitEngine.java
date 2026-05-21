package com.austin.trading.engine;

import com.austin.trading.domain.enums.StructuralExitTier;
import com.austin.trading.dto.StructuralExitInput;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
public class StructuralExitEngine {

    public StructuralExitResult evaluate(StructuralExitInput in) {
        List<String> signals = new ArrayList<>();
        if (in == null || in.currentPrice() == null) {
            return result(StructuralExitTier.DATA_GAP, "DATA_GAP: current price missing", signals);
        }

        int healthScore = in.healthScore() == null ? 0 : in.healthScore();
        String structure = normalize(in.structureStatus());
        String volume = normalize(in.volumeStatus());
        String rs = normalize(in.relativeStrengthStatus());
        String chip = normalize(in.chipStatus());
        boolean priceBroken = in.trailingStopPrice() != null
                && in.currentPrice().compareTo(in.trailingStopPrice()) <= 0;
        boolean structureBroken = structure.contains("PREVIOUS_LOW_BREAK")
                || structure.contains("MA10_BREAK")
                || structure.contains("MA20_BREAK");
        boolean softStructureBreak = structure.contains("MA5_BREAK");
        boolean volumeBreakdown = volume.contains("VOLUME_BREAKDOWN");
        boolean rsWeak = rs.contains("UNDERPERFORM");
        boolean chipWeak = chip.contains("BEARISH");
        boolean structureIntact = !structureBroken && !softStructureBreak;
        boolean leadershipIntact = Boolean.TRUE.equals(in.mainstreamTheme())
                && (rs.contains("OUTPERFORM") || rs.contains("INLINE") || rs.isBlank() || rs.contains("UNKNOWN"))
                && !chipWeak;

        if (priceBroken) signals.add("price_broken");
        if (structureIntact) signals.add("structure_intact");
        if (structureBroken) signals.add("structure_broken");
        if (volumeBreakdown) signals.add("volume_breakdown");
        if (rsWeak) signals.add("relative_strength_weak");
        if (leadershipIntact) signals.add("leader_stock_context_intact");

        if (healthScore < 25 && structureBroken && volumeBreakdown && rsWeak) {
            return result(StructuralExitTier.HARD_EXIT_ALERT,
                    "structure_broken + volume_breakdown + relative_strength_weak + health_score<25", signals);
        }
        if (structureBroken && (volumeBreakdown || rsWeak || chipWeak) && healthScore < 55) {
            return result(StructuralExitTier.EXIT_REVIEW,
                    "structure_broken requires manual exit review", signals);
        }
        if (softStructureBreak && (volumeBreakdown || rsWeak) && healthScore < 70) {
            return result(StructuralExitTier.REDUCE_REVIEW,
                    "soft_structure_break requires manual reduce review", signals);
        }
        if (priceBroken && (structureIntact || leadershipIntact) && healthScore >= 70) {
            return result(StructuralExitTier.OBSERVE_1D,
                    "price_broken but structure_intact; tolerate washout and observe one trading day", signals);
        }
        if (healthScore >= 70 && (structureIntact || leadershipIntact)) {
            return result(StructuralExitTier.HOLD,
                    "structure_intact and health score supports hold", signals);
        }
        if (priceBroken) {
            return result(StructuralExitTier.REDUCE_REVIEW,
                    "price_broken without confirmed structural breakdown; manual reduce review only", signals);
        }
        if (healthScore >= 55) {
            return result(StructuralExitTier.OBSERVE_1D,
                    "mixed signals; observe one trading day", signals);
        }
        return result(StructuralExitTier.REDUCE_REVIEW,
                "weak health score without hard structural confirmation; manual reduce review", signals);
    }

    private StructuralExitResult result(StructuralExitTier tier, String reason, List<String> signals) {
        return new StructuralExitResult(tier, reason, true, false, List.copyOf(signals));
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
