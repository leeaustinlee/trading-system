package com.austin.trading.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record FinalDecisionEvaluateRequest(
        @NotBlank String marketGrade,
        @NotBlank String decisionLock,
        @NotBlank String timeDecayStage,
        @NotNull Boolean hasPosition,
        @Valid List<FinalDecisionCandidateRequest> candidates
) {
}
