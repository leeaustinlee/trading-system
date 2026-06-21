package com.austin.trading.ui;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class LifecyclePromotionUiTest {

    @Test
    void lifecyclePageExposesPolicySimulationAndForwardTrackingBridgeControls() throws Exception {
        String html = Files.readString(Path.of("src/main/resources/static/index.html"));

        assertThat(html).contains(
                "P4 Policy Simulation / Forward Tracking",
                "id=\"lc-policy-refresh\"",
                "id=\"lc-policy-bridge\"",
                "id=\"lc-policy-backfill\"",
                "/api/promotion-review/policy-simulation?startDate=",
                "/api/promotion-review/forward-tracking-bridge?startDate=",
                "/api/forward-tracking/backfill-returns?days=");
        assertThat(html).contains(
                "simulationOnly",
                "doesNotAffectFinalDecision",
                "doesNotWriteCandidateStock",
                "doesNotWriteProductionScore",
                "noAutoPromotion",
                "boundedSoftBoostShadowOnly");
        assertThat(html).doesNotContain("BUY_NOW", "PROMOTE_TO_TRADABLE");
    }
}
