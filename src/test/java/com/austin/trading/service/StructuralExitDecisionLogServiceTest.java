package com.austin.trading.service;

import com.austin.trading.domain.enums.StructuralExitTier;
import com.austin.trading.entity.StructuralExitDecisionLogEntity;
import com.austin.trading.repository.StructuralExitDecisionLogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class StructuralExitDecisionLogServiceTest {

    @Test
    void saveFailureDoesNotAffectOriginalPositionDecisionResult() {
        StructuralExitDecisionLogRepository repo = mock(StructuralExitDecisionLogRepository.class);
        when(repo.save(any())).thenThrow(new RuntimeException("db down"));
        StructuralExitDecisionLogService service = new StructuralExitDecisionLogService(repo, new ObjectMapper());

        assertDoesNotThrow(() -> service.saveShadowLog(StructuralExitDecisionLogEntity.shadowBuilder()
                .tradeRefType("POSITION")
                .tradeRefId(1L)
                .symbol("2330")
                .evaluatedAt(LocalDateTime.now())
                .sourceDecisionStatus("EXIT")
                .arbiterTier(StructuralExitTier.OBSERVE_1D.name())
                .arbiterReason("shadow only")
                .manualConfirmRequired(true)
                .autoSellEnabled(false)
                .dataGaps(List.of())
                .build()));
    }
}
