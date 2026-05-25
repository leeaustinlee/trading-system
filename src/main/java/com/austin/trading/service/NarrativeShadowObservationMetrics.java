package com.austin.trading.service;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class NarrativeShadowObservationMetrics {
    private final AtomicLong emergingTotal = new AtomicLong();
    private final AtomicLong crowdedTotal = new AtomicLong();
    private final AtomicLong shadowBoostTotal = new AtomicLong();
    private final AtomicLong shadowPenaltyTotal = new AtomicLong();
    private final AtomicLong fomoWarningTotal = new AtomicLong();
    private final AtomicLong candidateTrackingTotal = new AtomicLong();

    public NarrativeShadowObservationMetrics(MeterRegistry registry) {
        gauge(registry, "narrative_theme_emerging_total", emergingTotal);
        gauge(registry, "narrative_theme_crowded_total", crowdedTotal);
        gauge(registry, "narrative_shadow_boost_total", shadowBoostTotal);
        gauge(registry, "narrative_shadow_penalty_total", shadowPenaltyTotal);
        gauge(registry, "narrative_fomo_warning_total", fomoWarningTotal);
        gauge(registry, "narrative_candidate_tracking_total", candidateTrackingTotal);
    }

    public void publish(Map<String, Long> metrics) {
        emergingTotal.set(metrics.getOrDefault("narrative_theme_emerging_total", 0L));
        crowdedTotal.set(metrics.getOrDefault("narrative_theme_crowded_total", 0L));
        shadowBoostTotal.set(metrics.getOrDefault("narrative_shadow_boost_total", 0L));
        shadowPenaltyTotal.set(metrics.getOrDefault("narrative_shadow_penalty_total", 0L));
        fomoWarningTotal.set(metrics.getOrDefault("narrative_fomo_warning_total", 0L));
        candidateTrackingTotal.set(metrics.getOrDefault("narrative_candidate_tracking_total", 0L));
    }

    private void gauge(MeterRegistry registry, String name, AtomicLong value) {
        Gauge.builder(name, value, AtomicLong::get)
                .description("Narrative Shadow Observation System metric; shadow/observability only")
                .register(registry);
    }
}
