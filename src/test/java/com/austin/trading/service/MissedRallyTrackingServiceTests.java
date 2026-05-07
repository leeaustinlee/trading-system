package com.austin.trading.service;

import com.austin.trading.entity.MissedRallyTrackingEntity;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class MissedRallyTrackingServiceTests {
    @Test
    void t5MaxReturnOver8MarksMissedRally() {
        MissedRallyTrackingService service = new MissedRallyTrackingService(null);
        MissedRallyTrackingEntity e = new MissedRallyTrackingEntity();
        e.setCurrentPriceAtDecision(new BigDecimal("100"));
        e.setT5High(new BigDecimal("109"));

        service.evaluateFlag(e);

        assertThat(e.getMaxReturnPct()).isEqualByComparingTo("9.0000");
        assertThat(e.getMissedRallyFlag()).isTrue();
        assertThat(e.getMissedRallyReason()).contains("T+5");
    }
}
