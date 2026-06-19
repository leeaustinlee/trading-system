package com.austin.trading.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ThemeLifecycleCalibrationServiceTest {

    @Test
    void rateUsesPercentScaleAndHandlesZeroDenominator() {
        assertThat(ThemeLifecycleCalibrationService.rate(3, 10)).isEqualByComparingTo(new BigDecimal("30.0000"));
        assertThat(ThemeLifecycleCalibrationService.rate(3, 0)).isEqualByComparingTo(new BigDecimal("0.0000"));
    }

    @Test
    void correlationAndSpreadDetectPositiveLifecycleSignal() {
        List<ThemeLifecycleCalibrationService.MetricSample> samples = List.of(
                sample(1, 1), sample(2, 2), sample(3, 3), sample(4, 4),
                sample(5, 5), sample(6, 6), sample(7, 7), sample(8, 8));

        Double corr = ThemeLifecycleCalibrationService.correlation(
                samples, ThemeLifecycleCalibrationService.MetricSample::lifecycleScore);
        ThemeLifecycleCalibrationService.Spread spread =
                ThemeLifecycleCalibrationService.lifecycleScoreSpread(samples);

        assertThat(corr).isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.0001));
        assertThat(spread.topAverage()).isEqualTo(7.5);
        assertThat(spread.bottomAverage()).isEqualTo(1.5);
        assertThat(spread.spread()).isEqualTo(6.0);
    }

    private ThemeLifecycleCalibrationService.MetricSample sample(double lifecycleScore, double realizedReturn) {
        return new ThemeLifecycleCalibrationService.MetricSample(
                lifecycleScore, lifecycleScore, lifecycleScore, lifecycleScore, realizedReturn);
    }
}
