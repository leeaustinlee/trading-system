package com.austin.trading.service;

import com.austin.trading.domain.enums.FeatureRuntimeMode;
import com.austin.trading.dto.response.FeatureModeResponse;
import com.austin.trading.dto.response.FeatureModeSummaryResponse;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FeatureModeServiceTests {

    @Test
    void summaryCountsAllModesWithoutMutatingDecisionPaths() {
        ScoreConfigService config = defaultConfig();
        FeatureModeService service = new FeatureModeService(config);

        FeatureModeSummaryResponse summary = service.summary();

        assertThat(summary.total()).isEqualTo(10);
        assertThat(summary.counts()).containsKeys(FeatureRuntimeMode.LIVE, FeatureRuntimeMode.SHADOW,
                FeatureRuntimeMode.OBSERVATION, FeatureRuntimeMode.TRACE_ONLY, FeatureRuntimeMode.OFF);
        long counted = summary.counts().values().stream().mapToLong(Long::longValue).sum();
        assertThat(counted).isEqualTo(summary.total());
        assertThat(summary.safetyNote()).contains("read-only");
    }

    @Test
    void findNormalizesFeatureKeyAndReturnsNotFoundForUnknown() {
        FeatureModeService service = new FeatureModeService(defaultConfig());

        Optional<FeatureModeResponse> found = service.find("theme-live-decision");

        assertThat(found).isPresent();
        assertThat(found.get().featureKey()).isEqualTo("ThemeLiveDecision");
        assertThat(service.find("missing-feature")).isEmpty();
    }

    @Test
    void autoClosePaperOnlyKeepsExitLayerShadowWhenRealCloseEnabled() {
        ScoreConfigService config = mock(ScoreConfigService.class);
        when(config.getString(anyString(), anyString())).thenAnswer(invocation -> invocation.getArgument(1));
        when(config.getString("position.review.auto_close.enabled", "")).thenReturn("true");
        when(config.getString("position.review.auto_close.paper_only", "true")).thenReturn("true");
        FeatureModeService service = new FeatureModeService(config);

        FeatureModeResponse exit = service.find("ShadowExitRuleEngine").orElseThrow();

        assertThat(exit.mode()).isEqualTo(FeatureRuntimeMode.SHADOW);
        assertThat(exit.canAffectLiveDecision()).isTrue();
        assertThat(exit.safetyNote()).contains("paper_only=true");
    }

    private static ScoreConfigService defaultConfig() {
        ScoreConfigService config = mock(ScoreConfigService.class);
        when(config.getString(anyString(), anyString())).thenAnswer(invocation -> invocation.getArgument(1));
        return config;
    }
}
