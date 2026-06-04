package com.austin.trading.engine;

import com.austin.trading.domain.enums.StructureExitState;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
public class StructureExitLayer {
    public LayerResult evaluate(ExitArbiterInput in) {
        List<String> gaps = new ArrayList<>();
        if (in == null || in.currentPrice() == null) {
            gaps.add("DATA_GAP: current price missing for structure");
            return new LayerResult(StructureExitState.DATA_GAP, gaps, "structure data gap");
        }
        BigDecimal p = in.currentPrice();
        boolean ma10Missing = in.ma10() == null, ma20Missing = in.ma20() == null, prevMissing = in.previousLow() == null;
        if (ma10Missing) gaps.add("DATA_GAP: MA10 missing");
        if (ma20Missing) gaps.add("DATA_GAP: MA20 missing");
        if (prevMissing) gaps.add("DATA_GAP: previous low missing");
        String volume = norm(in.volumeStatus()); String rs = norm(in.relativeStrengthStatus()); String chip = norm(in.chipStatus()); String raw = norm(in.structureStatus());
        boolean volumePanic = volume.contains("PANIC") || volume.contains("BREAKDOWN") || volume.contains("SPIKE_LONG_BLACK");
        boolean rsBroken = rs.contains("UNDERPERFORM") || rs.contains("BROKEN") || rs.contains("WEAK");
        boolean chipWeak = chip.contains("BEARISH");
        boolean lowerLow = in.previousLow() != null && p.compareTo(in.previousLow()) < 0;
        boolean ma20Break = in.ma20() != null && p.compareTo(in.ma20()) < 0;
        boolean ma10Break = in.ma10() != null && p.compareTo(in.ma10()) < 0;
        boolean ma5Break = in.ma5() != null && p.compareTo(in.ma5()) < 0;
        if (raw.contains("PANIC")) {
            return new LayerResult(StructureExitState.PANIC_BREAK, gaps, "explicit panic structure break");
        }
        if (raw.contains("LOWER_LOW") || lowerLow) return new LayerResult((volumePanic || rsBroken || chipWeak) ? StructureExitState.BROKEN : StructureExitState.LOWER_LOW_BREAKDOWN, gaps, "lower low breakdown");
        if (ma20Break) return new LayerResult((volumePanic || rsBroken || chipWeak) ? StructureExitState.BROKEN : StructureExitState.MA20_BREAK, gaps, "MA20 break");
        if (volumePanic) return new LayerResult(StructureExitState.VOLUME_PANIC, gaps, "volume panic");
        if (rsBroken && ma10Break) return new LayerResult(StructureExitState.RELATIVE_STRENGTH_BROKEN, gaps, "relative strength broken with MA10 break");
        if (ma10Break) return new LayerResult(StructureExitState.MA10_TEST, gaps, "MA10 test");
        if (ma5Break) return new LayerResult(StructureExitState.MA5_BREAK_ONLY, gaps, "MA5 break only");
        if (!gaps.isEmpty()) return new LayerResult(StructureExitState.DATA_GAP, gaps, "structure data gap");
        return new LayerResult(StructureExitState.HEALTHY_PULLBACK, gaps, "healthy pullback / structure intact");
    }
    private String norm(String v) { return v == null ? "" : v.trim().toUpperCase(Locale.ROOT); }
    public record LayerResult(StructureExitState state, List<String> dataGaps, String reason) {}
}
