package com.austin.trading.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

class VolumeProfileServiceTests {

    private final VolumeProfileService service = new VolumeProfileService();

    @Test
    void compute_atMidSession_linearPaceRatio() {
        // 盤中 11:15（開盤後 135 分鐘，交易日一半）；avg = 10,000 張 → expected ≈ 5,000
        // current 5,500 → ratio ≈ 1.10
        VolumeProfileService.VolumeRatioResult result =
                service.compute(5_500L, 10_000L, LocalTime.of(11, 15));
        assertThat(result.available()).isTrue();
        assertThat(result.source()).isEqualTo("linear-pace");
        assertThat(result.elapsedMinutes()).isEqualTo(135);
        assertThat(result.expectedVolume()).isEqualTo(5_000L);
        assertThat(result.ratio()).isEqualByComparingTo(new BigDecimal("1.1000"));
    }

    @Test
    void compute_lowVolumeLateSession_ratioBelowOne() {
        // 12:30（210 分鐘）；avg = 10,000 張 → expected ≈ 7,777
        // current 4,500 → ratio ≈ 0.58
        VolumeProfileService.VolumeRatioResult result =
                service.compute(4_500L, 10_000L, LocalTime.of(12, 30));
        assertThat(result.available()).isTrue();
        assertThat(result.ratio().compareTo(new BigDecimal("0.8"))).isLessThan(0);
    }

    @Test
    void compute_beforeSessionOpen_unavailable() {
        VolumeProfileService.VolumeRatioResult result =
                service.compute(1_000L, 10_000L, LocalTime.of(8, 30));
        assertThat(result.available()).isFalse();
        assertThat(result.reason()).isEqualTo("BEFORE_SESSION_OPEN");
    }

    @Test
    void compute_afterSessionClose_clampsToFullSession() {
        // 14:00 已收盤，elapsed clamp 到 270；avg=10000 → expected 10000；current 9500 → ratio 0.95
        VolumeProfileService.VolumeRatioResult result =
                service.compute(9_500L, 10_000L, LocalTime.of(14, 0));
        assertThat(result.available()).isTrue();
        assertThat(result.elapsedMinutes()).isEqualTo(270);
        assertThat(result.expectedVolume()).isEqualTo(10_000L);
        assertThat(result.ratio()).isEqualByComparingTo(new BigDecimal("0.9500"));
    }

    @Test
    void compute_missingAvgDaily_unavailable() {
        VolumeProfileService.VolumeRatioResult result =
                service.compute(1_000L, null, LocalTime.of(10, 0));
        assertThat(result.available()).isFalse();
        assertThat(result.reason()).isEqualTo("MISSING_AVG_DAILY_VOLUME");
    }

    @Test
    void compute_missingCurrent_unavailable() {
        VolumeProfileService.VolumeRatioResult result =
                service.compute(null, 10_000L, LocalTime.of(10, 0));
        assertThat(result.available()).isFalse();
        assertThat(result.reason()).isEqualTo("MISSING_CURRENT_VOLUME");
    }
}
