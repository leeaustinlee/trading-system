package com.austin.trading.engine;

import com.austin.trading.domain.enums.ThemeExitState;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
public class ThemeExitLayer {
    public LayerResult evaluate(ExitArbiterInput in) {
        List<String> gaps = new ArrayList<>();
        String stage = norm(in == null ? null : in.themeStage());
        Boolean mainstream = in == null ? null : in.mainstreamTheme();
        if ((stage.isBlank() || "UNKNOWN".equals(stage)) && mainstream == null) {
            gaps.add("DATA_GAP: theme stage/mainstream missing");
            return new LayerResult(ThemeExitState.DATA_GAP, gaps, "theme data gap");
        }
        if (stage.contains("DECAY") || stage.contains("DEAD") || stage.contains("BROKEN") || stage.contains("DISTRIBUTION"))
            return new LayerResult(ThemeExitState.BROKEN, gaps, "theme broken");
        if (stage.contains("COOL") || stage.contains("OVERHEAT") || stage.contains("WEAK"))
            return new LayerResult(ThemeExitState.COOLING, gaps, "theme cooling");
        if (Boolean.TRUE.equals(mainstream) && (stage.contains("EXPAND") || stage.contains("EMERGING")))
            return new LayerResult(ThemeExitState.MAINSTREAM_EXPANDING, gaps, "mainstream expanding");
        if (Boolean.TRUE.equals(mainstream))
            return new LayerResult(ThemeExitState.MAINSTREAM_STABLE, gaps, "mainstream stable");
        return new LayerResult(ThemeExitState.ACTIVE, gaps, "theme active");
    }
    private String norm(String v) { return v == null ? "" : v.trim().toUpperCase(Locale.ROOT); }
    public record LayerResult(ThemeExitState state, List<String> dataGaps, String reason) {}
}
