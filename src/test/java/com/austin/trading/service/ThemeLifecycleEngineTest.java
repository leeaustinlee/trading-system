package com.austin.trading.service;

import com.austin.trading.entity.ThemeReplaySnapshotEntity;
import com.austin.trading.repository.ThemeLifecycleStateRepository;
import com.austin.trading.repository.ThemeReplayNodeRepository;
import com.austin.trading.repository.ThemeReplaySnapshotRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ThemeLifecycleEngineTest {

    private ThemeLifecycleStateRepository lifecycleRepository;
    private ThemeReplaySnapshotRepository snapshotRepository;
    private ThemeReplayNodeRepository nodeRepository;
    private ThemeLifecycleEngine engine;

    @BeforeEach
    void setUp() {
        lifecycleRepository = mock(ThemeLifecycleStateRepository.class);
        snapshotRepository = mock(ThemeReplaySnapshotRepository.class);
        nodeRepository = mock(ThemeReplayNodeRepository.class);
        when(lifecycleRepository.findFirstByThemeTagAndTradingDateLessThanOrderByTradingDateDesc(any(), any()))
                .thenReturn(Optional.empty());
        engine = new ThemeLifecycleEngine(lifecycleRepository, snapshotRepository, nodeRepository, new ObjectMapper());
    }

    @Test
    void ruleBasedStagesCoverEmergingMainstreamOverheatedDistributionAndDead() {
        assertThat(engine.decide(new ThemeLifecycleEngine.Metrics(
                1, 2, bd("0.40"), 1, bd("0.10"), bd("0.20"), bd("0.00"), bd("0.45"), bd("0.20"), 0
        )).stage()).isEqualTo(ThemeLifecycleEngine.EMERGING);

        assertThat(engine.decide(new ThemeLifecycleEngine.Metrics(
                1, 6, bd("0.65"), 3, bd("0.55"), bd("0.35"), bd("0.05"), bd("0.60"), bd("0.50"), 0
        )).stage()).isEqualTo(ThemeLifecycleEngine.MAINSTREAM);

        assertThat(engine.decide(new ThemeLifecycleEngine.Metrics(
                2, 8, bd("0.90"), 4, bd("0.50"), bd("0.86"), bd("0.40"), bd("0.90"), bd("0.40"), 0
        )).stage()).isEqualTo(ThemeLifecycleEngine.OVERHEATED);

        assertThat(engine.decide(new ThemeLifecycleEngine.Metrics(
                1, 6, bd("0.30"), 1, bd("0.20"), bd("0.70"), bd("0.10"), bd("0.55"), bd("0.10"), 3
        )).stage()).isEqualTo(ThemeLifecycleEngine.DISTRIBUTION);

        assertThat(engine.decide(new ThemeLifecycleEngine.Metrics(
                0, 1, bd("0.05"), 0, bd("0.00"), bd("0.10"), bd("0.00"), bd("0.10"), bd("0.00"), 0
        )).stage()).isEqualTo(ThemeLifecycleEngine.DEAD);
    }

    @Test
    void passiveComponentsYageoFixtureShapeBecomesMainstreamAndAdvisoryOnly() {
        ThemeReplaySnapshotEntity snapshot = snapshot(LocalDate.of(2026, 5, 22), "被動元件", 1, 6, 5, 0);
        snapshot.setPayloadJson("{\"continuationDays\":3,\"rotationScore\":0.65,\"volumeExpansion\":0.70,\"crowdingScore\":0.45,\"limitUpDensity\":0.05,\"narrativeDensity\":0.68,\"institutionalFlowScore\":0.55}");

        var state = engine.evaluate(snapshot.getTradingDate(), snapshot, java.util.List.of());

        assertThat(state.getStage()).isEqualTo(ThemeLifecycleEngine.MAINSTREAM);
        assertThat(state.getThemeTag()).isEqualTo("被動元件");
        assertThat(state.getReason()).contains("leader is clear");
        assertThat(state.getRecommendedPlaybookJson()).contains("LOW_BASE_FOLLOWER", "PULLBACK");
        assertThat(state.getAvoidPlaybookJson()).contains("CHASE_LEADER");
        assertThat(state.getPayloadJson()).contains("\"replayOnly\":true", "\"advisoryOnly\":true", "lifecycleDoesNotOverrideRiskGate");
    }

    @Test
    void buildWritesOnlyLifecycleAndReplaySnapshotLifecycleFields() {
        LocalDate date = LocalDate.of(2026, 5, 22);
        ThemeReplaySnapshotEntity snapshot = snapshot(date, "被動元件", 1, 6, 5, 0);
        snapshot.setPayloadJson("{\"continuationDays\":3,\"rotationScore\":0.65,\"volumeExpansion\":0.70}");
        when(snapshotRepository.findByTradingDateOrderByThemeTagAsc(date)).thenReturn(java.util.List.of(snapshot));
        when(nodeRepository.findByTradingDateAndThemeTagOrderByIdAsc(date, "被動元件")).thenReturn(java.util.List.of());

        var result = engine.build(date);

        assertThat(result.replayOnly()).isTrue();
        assertThat(result.advisoryOnly()).isTrue();
        assertThat(result.safetyBoundary().doesNotAffectFinalDecision()).isTrue();
        assertThat(result.safetyBoundary().doesNotAffectBuySellEnter()).isTrue();
        assertThat(result.safetyBoundary().lifecycleDoesNotOverrideRiskGate()).isTrue();
        assertThat(result.stages()).containsEntry("被動元件", ThemeLifecycleEngine.MAINSTREAM);
        verify(lifecycleRepository).deleteByTradingDate(date);
        verify(lifecycleRepository).saveAll(any());
        verify(snapshotRepository).saveAll(java.util.List.of(snapshot));
        assertThat(snapshot.getLifecycleStage()).isEqualTo(ThemeLifecycleEngine.MAINSTREAM);
        assertThat(snapshot.getRecommendedPlaybookJson()).contains("LOW_BASE_FOLLOWER", "PULLBACK");
    }

    static ThemeReplaySnapshotEntity snapshot(LocalDate date, String theme, int leaders, int breadth, int peers, int divergence) {
        ThemeReplaySnapshotEntity e = new ThemeReplaySnapshotEntity();
        e.setTradingDate(date);
        e.setThemeTag(theme);
        e.setLeaderSymbol(leaders > 0 ? "2327" : null);
        e.setLeaderCount(leaders);
        e.setBreadth(breadth);
        e.setPeerCount(peers);
        e.setDivergenceCount(divergence);
        e.setResearchUniverseCount(breadth);
        e.setTradableUniverseCount(0);
        return e;
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
