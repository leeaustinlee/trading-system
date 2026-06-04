package com.austin.trading.engine;

import com.austin.trading.domain.enums.*;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class StructureAwareExitArbiter {
    private final ThemeExitLayer themeLayer; private final StructureExitLayer structureLayer; private final PriceExitLayer priceLayer;
    public StructureAwareExitArbiter(ThemeExitLayer themeLayer, StructureExitLayer structureLayer, PriceExitLayer priceLayer) { this.themeLayer=themeLayer; this.structureLayer=structureLayer; this.priceLayer=priceLayer; }
    public StructureAwareExitDecision evaluate(ExitArbiterInput in) {
        ThemeExitLayer.LayerResult theme = themeLayer.evaluate(in);
        StructureExitLayer.LayerResult structure = structureLayer.evaluate(in);
        PriceExitLayer.LayerResult price = priceLayer.evaluate(in);
        List<String> gaps = new ArrayList<>(); gaps.addAll(theme.dataGaps()); gaps.addAll(structure.dataGaps()); gaps.addAll(price.dataGaps());
        List<String> signals = new ArrayList<>(); signals.add("theme="+theme.state()); signals.add("structure="+structure.state()); signals.add("price="+price.state());
        Map<String,Object> votes = new LinkedHashMap<>(); votes.put("themeReason", theme.reason()); votes.put("structureReason", structure.reason()); votes.put("priceReason", price.reason());
        StructuralExitTier tier; String reason;
        if (price.hardRisk()) { tier = StructuralExitTier.HARD_EXIT_ALERT; reason = "hard risk preserved: " + price.reason(); }
        else if (structure.state() == StructureExitState.PANIC_BREAK) { tier = StructuralExitTier.HARD_EXIT_ALERT; reason = "panic structure break"; }
        else if (hasCriticalGap(theme, structure, price)) { tier = StructuralExitTier.DATA_GAP; reason = "critical data gap; shadow arbiter refuses fake HOLD"; }
        else if (isBroken(structure.state()) && isCoolingOrBroken(theme.state()) && hasPriceTrigger(price.state())) { tier = StructuralExitTier.EXIT_REVIEW; reason = "price trigger + structure broken + theme cooling/broken"; }
        else if (theme.state() == ThemeExitState.BROKEN && hasPriceTrigger(price.state())) { tier = StructuralExitTier.EXIT_REVIEW; reason = "theme broken with price trigger; exit review required"; }
        else if (theme.state() == ThemeExitState.BROKEN) { tier = StructuralExitTier.REDUCE_REVIEW; reason = "theme broken; HOLD_THESIS forbidden even without price trigger"; }
        else if (isBroken(structure.state()) && hasPriceTrigger(price.state())) { tier = StructuralExitTier.REDUCE_REVIEW; reason = "structure broken with price trigger; reduce/manual review"; }
        else if (isBroken(structure.state())) { tier = StructuralExitTier.REDUCE_REVIEW; reason = "structure broken; HOLD_THESIS forbidden even without price trigger"; }
        else if (isThemeActive(theme.state()) && isHealthy(structure.state()) && !hasPriceTrigger(price.state())) { tier = StructuralExitTier.HOLD_THESIS; reason = "theme still active and pullback structure healthy"; }
        else if (hasPriceTrigger(price.state()) && isThemeActive(theme.state()) && isHealthyOrSoft(structure.state())) { tier = StructuralExitTier.OBSERVE_1D; reason = "price-only stop with theme/structure intact; observe one trading day"; }
        else if (hasPriceTrigger(price.state())) { tier = StructuralExitTier.REDUCE_REVIEW; reason = "price trigger without full structural/theme breakdown; manual reduce review"; }
        else { tier = StructuralExitTier.OBSERVE_1D; reason = "non-ideal theme/structure without price trigger; observe instead of fake HOLD"; }
        return new StructureAwareExitDecision(tier, reason, theme.state(), structure.state(), price.state(), price.hardRisk(), true, false, List.copyOf(signals), gaps.stream().distinct().toList(), votes);
    }
    private boolean hasCriticalGap(ThemeExitLayer.LayerResult t, StructureExitLayer.LayerResult s, PriceExitLayer.LayerResult p) { return t.state()==ThemeExitState.DATA_GAP || s.state()==StructureExitState.DATA_GAP || p.state()==PriceExitState.DATA_GAP; }
    private boolean hasPriceTrigger(PriceExitState s) { return s != PriceExitState.NO_TRIGGER && s != PriceExitState.DATA_GAP; }
    private boolean isThemeActive(ThemeExitState s) { return s==ThemeExitState.MAINSTREAM_EXPANDING || s==ThemeExitState.MAINSTREAM_STABLE || s==ThemeExitState.ACTIVE; }
    private boolean isCoolingOrBroken(ThemeExitState s) { return s==ThemeExitState.COOLING || s==ThemeExitState.BROKEN; }
    private boolean isHealthy(StructureExitState s) { return s==StructureExitState.HEALTHY_PULLBACK || s==StructureExitState.INTACT; }
    private boolean isHealthyOrSoft(StructureExitState s) { return isHealthy(s) || s==StructureExitState.MA5_BREAK_ONLY || s==StructureExitState.MA10_TEST; }
    private boolean isBroken(StructureExitState s) { return s==StructureExitState.BROKEN || s==StructureExitState.MA20_BREAK || s==StructureExitState.LOWER_LOW_BREAKDOWN || s==StructureExitState.VOLUME_PANIC || s==StructureExitState.RELATIVE_STRENGTH_BROKEN; }
}
