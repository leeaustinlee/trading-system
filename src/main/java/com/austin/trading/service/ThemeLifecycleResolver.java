package com.austin.trading.service;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Locale;

@Component
public class ThemeLifecycleResolver {
    public enum ThemeLifecycle {
        EARLY, EMERGING, ACTIVE, MAINSTREAM, CROWDED, FADING, DEAD, UNKNOWN
    }

    public String normalize(String rawStage, BigDecimal heat, BigDecimal crowding, BigDecimal rotation) {
        String s = rawStage == null ? "" : rawStage.trim().toUpperCase(Locale.ROOT);
        if (s.contains("DEAD")) return ThemeLifecycle.DEAD.name();
        if (s.contains("FAD") || s.contains("DECAY")) return ThemeLifecycle.FADING.name();
        if (s.contains("CROWD")) return ThemeLifecycle.CROWDED.name();
        if (s.contains("MAIN")) return ThemeLifecycle.MAINSTREAM.name();
        if (s.contains("ACTIVE")) return ThemeLifecycle.ACTIVE.name();
        if (s.contains("EMERG")) return ThemeLifecycle.EMERGING.name();
        if (s.contains("EARLY")) return ThemeLifecycle.EARLY.name();
        if (crowding != null && crowding.compareTo(new BigDecimal("0.75")) >= 0) return ThemeLifecycle.CROWDED.name();
        if (heat != null && heat.compareTo(new BigDecimal("8.0")) >= 0) return ThemeLifecycle.MAINSTREAM.name();
        if (heat != null && heat.compareTo(new BigDecimal("6.0")) >= 0) return ThemeLifecycle.ACTIVE.name();
        if (rotation != null && rotation.signum() < 0) return ThemeLifecycle.FADING.name();
        return ThemeLifecycle.UNKNOWN.name();
    }

    public boolean active(String lifecycle) {
        String s = lifecycle == null ? "" : lifecycle.toUpperCase(Locale.ROOT);
        return s.equals("EARLY") || s.equals("EMERGING") || s.equals("ACTIVE") || s.equals("MAINSTREAM") || s.equals("CROWDED");
    }
}
