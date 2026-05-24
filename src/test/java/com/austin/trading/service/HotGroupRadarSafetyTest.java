package com.austin.trading.service;

import com.austin.trading.repository.CandidateThemeRadarTraceRepository;
import com.austin.trading.repository.HotGroupRadarSnapshotRepository;
import com.austin.trading.repository.HotGroupStockSignalRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class HotGroupRadarSafetyTest {
    private HotGroupRadarSnapshotRepository snapshotRepo;
    private HotGroupStockSignalRepository signalRepo;
    private CandidateThemeRadarTraceRepository traceRepo;
    private HotGroupRadarService service;

    @BeforeEach
    void setUp() {
        snapshotRepo = mock(HotGroupRadarSnapshotRepository.class);
        signalRepo = mock(HotGroupStockSignalRepository.class);
        traceRepo = mock(CandidateThemeRadarTraceRepository.class);
        when(snapshotRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(signalRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(traceRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        service = new HotGroupRadarService(snapshotRepo, signalRepo, traceRepo);
    }

    @Test
    void safetyBoundaryForbidsProductionDecisionSideEffects() {
        var boundary = service.safetyBoundary();

        assertThat(boundary.shadowOnly()).isTrue();
        assertThat(boundary.observabilityOnly()).isTrue();
        assertThat(boundary.doesNotAffectFinalDecision()).isTrue();
        assertThat(boundary.doesNotAffectBuySellEnter()).isTrue();
        assertThat(boundary.doesNotWriteCandidateStock()).isTrue();
        assertThat(boundary.doesNotWriteProductionScore()).isTrue();
        assertThat(boundary.doesNotOverrideRiskGate()).isTrue();
        assertThat(boundary.noDirectBuy()).isTrue();
    }

    @Test
    void buildDoesNotWriteCandidateStockOrFinalDecisionTracePromotion() {
        var result = service.build(LocalDate.of(2026, 5, 22), "POSTMARKET", HotGroupRadarServiceTest.passiveFixtureJson());

        assertThat(result.doesNotWriteCandidateStock()).isTrue();
        assertThat(result.doesNotWriteProductionScore()).isTrue();
        assertThat(result.safetyBoundary().doesNotAffectBuySellEnter()).isTrue();
        verify(traceRepo, atLeastOnce()).save(argThat(t ->
                Boolean.FALSE.equals(t.getAppliedToCandidatePool()) && Boolean.FALSE.equals(t.getAppliedToFinalDecision())));
    }
}
