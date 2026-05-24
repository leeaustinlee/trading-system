package com.austin.trading.service;

import com.austin.trading.entity.ThemeReplayNodeEntity;
import com.austin.trading.entity.ThemeReplaySnapshotEntity;
import com.austin.trading.repository.ThemeLifecycleStateRepository;
import com.austin.trading.repository.ThemeReplayNodeRepository;
import com.austin.trading.repository.ThemeReplaySnapshotRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ThemeLifecycleReplayTest {

    private static final String FIXTURE = "replay/theme-first/passive-components-2026-05-22.json";
    private final ObjectMapper objectMapper = new ObjectMapper();
    private ThemeLifecycleEngine engine;

    @BeforeEach
    void setUp() {
        ThemeLifecycleStateRepository lifecycleRepository = mock(ThemeLifecycleStateRepository.class);
        when(lifecycleRepository.findFirstByThemeTagAndTradingDateLessThanOrderByTradingDateDesc(any(), any()))
                .thenReturn(Optional.empty());
        engine = new ThemeLifecycleEngine(
                lifecycleRepository,
                mock(ThemeReplaySnapshotRepository.class),
                mock(ThemeReplayNodeRepository.class),
                objectMapper);
    }

    @Test
    void yageoPassiveComponentsLifecycleIsMainstreamButNotTradableOrEnter() throws Exception {
        LocalDate date = LocalDate.of(2026, 5, 22);
        List<ThemeReplayNodeEntity> nodes = fixtureNodes(date);
        ThemeReplaySnapshotEntity snapshot = new ThemeReplaySnapshotEntity();
        snapshot.setTradingDate(date);
        snapshot.setThemeTag("被動元件");
        snapshot.setLeaderSymbol("2327");
        snapshot.setLeaderCount(1);
        snapshot.setPeerCount(5);
        snapshot.setBreadth(nodes.size());
        snapshot.setResearchUniverseCount(nodes.size());
        snapshot.setTradableUniverseCount(0);
        snapshot.setPayloadJson("{\"continuationDays\":3,\"rotationScore\":0.65,\"volumeExpansion\":0.70,\"crowdingScore\":0.45,\"limitUpDensity\":0.05,\"narrativeDensity\":0.68,\"institutionalFlowScore\":0.55}");

        var lifecycle = engine.evaluate(date, snapshot, nodes);

        assertThat(lifecycle.getStage()).isEqualTo(ThemeLifecycleEngine.MAINSTREAM);
        assertThat(lifecycle.getRecommendedPlaybookJson()).contains("LOW_BASE_FOLLOWER", "PULLBACK");
        assertThat(lifecycle.getAvoidPlaybookJson()).contains("CHASE_LEADER");
        assertThat(nodes).filteredOn(n -> "2327".equals(n.getSymbol())).first().satisfies(n -> {
            assertThat(n.getLeadershipOnly()).isTrue();
            assertThat(n.getTradableUniverse()).isFalse();
            assertThat(n.getLeaderTradable()).isFalse();
        });
        for (String peer : Set.of("2492", "3026", "3090", "6173", "2375")) {
            assertThat(nodes).filteredOn(n -> peer.equals(n.getSymbol())).first().satisfies(n -> {
                assertThat(n.getResearchRole()).isEqualTo("PEER_SHADOW");
                assertThat(n.getTradableUniverse()).isFalse();
                assertThat(n.getThemeLeaderSymbol()).isEqualTo("2327");
            });
        }
        assertThat(lifecycle.getPayloadJson()).contains("doesNotAffectFinalDecision", "doesNotAffectBuySellEnter", "lifecycleDoesNotOverrideRiskGate");
    }

    @Test
    void safetyBoundaryExplicitlyKeepsLifecycleReplayAdvisoryOnly() {
        assertThat(engine.safetyBoundary().replayOnly()).isTrue();
        assertThat(engine.safetyBoundary().advisoryOnly()).isTrue();
        assertThat(engine.safetyBoundary().doesNotAffectFinalDecision()).isTrue();
        assertThat(engine.safetyBoundary().doesNotAffectBuySellEnter()).isTrue();
        assertThat(engine.safetyBoundary().lifecycleDoesNotOverrideRiskGate()).isTrue();
        assertThat(engine.safetyBoundary().doesNotPromoteResearchUniverse()).isTrue();
    }

    private List<ThemeReplayNodeEntity> fixtureNodes(LocalDate date) throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(FIXTURE)) {
            JsonNode root = objectMapper.readTree(in);
            List<ThemeReplayNodeEntity> nodes = new ArrayList<>();
            for (JsonNode n : StreamSupport.stream(root.path("candidates").spliterator(), false).toList()) {
                ThemeReplayNodeEntity node = new ThemeReplayNodeEntity();
                node.setTradingDate(date);
                node.setThemeTag(n.path("themeTag").asText());
                node.setSymbol(n.path("symbol").asText());
                node.setStockName(n.path("stockName").asText());
                boolean leader = n.path("isThemeLeader").asBoolean(false);
                node.setIsThemeLeader(leader);
                node.setLeadershipOnly(leader && !n.path("leaderTradable").asBoolean(false));
                node.setLeaderTradable(n.path("leaderTradable").asBoolean(false));
                node.setThemeLeaderSymbol(n.path("themeLeaderSymbol").asText(leader ? node.getSymbol() : null));
                node.setResearchRole(leader ? "THEME_LEADER" : "PEER_SHADOW");
                node.setResearchUniverse(true);
                node.setTradableUniverse(false);
                if (n.hasNonNull("themeImportanceScore")) node.setThemeImportanceScore(new BigDecimal(n.path("themeImportanceScore").asText()));
                if (n.hasNonNull("shadowRankScore")) node.setShadowRankScore(new BigDecimal(n.path("shadowRankScore").asText()));
                nodes.add(node);
            }
            return nodes;
        }
    }
}
