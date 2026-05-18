package com.austin.trading.dto.response;

import com.austin.trading.domain.enums.FeatureRuntimeMode;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** Read-only effective feature/engine rollout state. */
public record FeatureModeResponse(
        String featureKey,
        String displayName,
        String category,
        FeatureRuntimeMode mode,
        boolean enabled,
        String primaryConfigKey,
        String primaryConfigValue,
        String defaultConfigValue,
        List<String> supportingConfigKeys,
        Map<String, String> supportingConfigValues,
        boolean canAffectLiveDecision,
        String decisionImpact,
        String safetyNote,
        LocalDateTime generatedAt
) {
}
