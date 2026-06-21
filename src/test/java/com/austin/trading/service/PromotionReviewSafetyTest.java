package com.austin.trading.service;

import com.austin.trading.dto.request.PromotionReviewDecisionRequest;
import com.austin.trading.entity.PromotionReviewItemEntity;
import com.austin.trading.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PromotionReviewSafetyTest {
    private static final LocalDate DATE = LocalDate.of(2026, 5, 22);
    private PromotionReviewItemRepository itemRepo;
    private PromotionReviewAuditRepository auditRepo;
    private CandidateStockRepository candidateStockRepo;
    private FinalDecisionRepository finalDecisionRepo;
    private CandidateForwardTrackingRepository forwardTrackingRepo;
    private PromotionReviewService service;

    @BeforeEach
    void setUp() {
        itemRepo = mock(PromotionReviewItemRepository.class);
        auditRepo = mock(PromotionReviewAuditRepository.class);
        candidateStockRepo = mock(CandidateStockRepository.class);
        finalDecisionRepo = mock(FinalDecisionRepository.class);
        forwardTrackingRepo = mock(CandidateForwardTrackingRepository.class);
        when(itemRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(auditRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(itemRepo.findByTradingDateOrderByThemeTagAscSymbolAscSourceAsc(DATE)).thenReturn(List.of());
        service = new PromotionReviewService(itemRepo, auditRepo, mock(ResearchUniverseItemRepository.class), mock(HotGroupStockSignalRepository.class),
                mock(ThemeReplayNodeRepository.class), mock(ThemeLifecycleStateRepository.class), mock(ThemeReplayMetricsRepository.class),
                candidateStockRepo, finalDecisionRepo, forwardTrackingRepo, new ObjectMapper());
    }

    @Test
    void safetyBoundaryForbidsTradingSideEffects() {
        var boundary = service.safetyBoundary();

        assertThat(boundary.reviewOnly()).isTrue();
        assertThat(boundary.doesNotAffectFinalDecision()).isTrue();
        assertThat(boundary.doesNotAffectBuySellEnter()).isTrue();
        assertThat(boundary.doesNotWriteCandidateStock()).isTrue();
        assertThat(boundary.doesNotWriteProductionScore()).isTrue();
        assertThat(boundary.candidatePoolShadowIsNotTradable()).isTrue();
        assertThat(boundary.noAutoPromotion()).isTrue();
        assertThat(boundary.promotionRequiresSeparateRiskGate()).isTrue();
    }

    @Test
    void forbiddenDecisionStatusesAreRejected() {
        PromotionReviewItemEntity item = item();
        when(itemRepo.findById(1L)).thenReturn(Optional.of(item));

        for (String forbidden : List.of("TRADABLE", "BUY", "ENTER", "PROMOTED_TO_TRADABLE")) {
            assertThatThrownBy(() -> service.decide(1L, new PromotionReviewDecisionRequest(forbidden, "test", "no")))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("400 BAD_REQUEST");
        }
        verify(itemRepo, never()).save(any());
        verify(auditRepo, never()).save(any());
    }

    @Test
    void queueResponseKeepsCandidatePoolShadowOutOfTradableAndFinalDecision() {
        PromotionReviewItemEntity item = item();
        item.setCurrentStatus("CANDIDATE_POOL_SHADOW");
        when(itemRepo.findByTradingDateOrderByThemeTagAscSymbolAscSourceAsc(DATE)).thenReturn(List.of(item));

        var queue = service.queue(DATE);

        assertThat(queue.items()).hasSize(1);
        var responseItem = queue.items().get(0);
        assertThat(responseItem.currentStatus()).isEqualTo("CANDIDATE_POOL_SHADOW");
        assertThat(responseItem.tradable()).isFalse();
        assertThat(responseItem.notFinalDecisionEligible()).isTrue();
        assertThat(responseItem.safetyBoundary().candidatePoolShadowIsNotTradable()).isTrue();
        verify(candidateStockRepo, never()).save(any());
        verify(finalDecisionRepo, never()).save(any());
    }

    @Test
    void policySimulationIsReadOnlyAndNeverOutputsTradingPolicyWords() {
        PromotionReviewItemEntity item = item();
        item.setCurrentStatus("CANDIDATE_POOL_SHADOW");
        item.setRiskBlocker(true);
        when(itemRepo.findByTradingDateBetweenAndCurrentStatusOrderByTradingDateAscThemeTagAscSymbolAscSourceAsc(
                DATE, DATE, "CANDIDATE_POOL_SHADOW")).thenReturn(List.of(item));
        when(forwardTrackingRepo.findByTradingDateBetween(DATE, DATE)).thenReturn(List.of());

        var response = service.policySimulation(DATE, DATE, "CANDIDATE_POOL_SHADOW");

        assertThat(response.simulationOnly()).isTrue();
        assertThat(response.doesNotAffectBuySellEnter()).isTrue();
        assertThat(response.doesNotWriteCandidateStock()).isTrue();
        assertThat(response.doesNotWriteProductionScore()).isTrue();
        assertThat(response.noAutoPromotion()).isTrue();
        assertThat(response.items()).extracting("suggestedPolicy")
                .doesNotContain("BUY", "ENTER", "TRADABLE")
                .containsExactly("BLOCKED_BY_RISK");
        verify(itemRepo, never()).save(any());
        verify(auditRepo, never()).save(any());
        verify(candidateStockRepo, never()).save(any());
        verify(finalDecisionRepo, never()).save(any());
    }

    private PromotionReviewItemEntity item() {
        PromotionReviewItemEntity e = new PromotionReviewItemEntity();
        e.setId(1L);
        e.setTradingDate(DATE);
        e.setSymbol("2492");
        e.setStockName("華新科");
        e.setThemeTag("被動元件/MLCC");
        e.setSource("PEER_SHADOW");
        e.setResearchRole("PEER_SHADOW");
        e.setCurrentStatus("PENDING_REVIEW");
        e.setRadarScore(new BigDecimal("24"));
        e.setPayloadJson("{}");
        return e;
    }
}
