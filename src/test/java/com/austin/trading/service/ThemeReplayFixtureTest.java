package com.austin.trading.service;

import com.austin.trading.entity.CandidateStockEntity;
import com.austin.trading.repository.CandidateStockRepository;
import com.austin.trading.repository.ThemeLeaderRetentionRepository;
import com.austin.trading.repository.ThemeLeadershipSnapshotRepository;
import com.austin.trading.repository.ThemePeerShadowCandidateRepository;
import com.austin.trading.repository.ThemeReplayEdgeRepository;
import com.austin.trading.repository.ThemeReplayNodeRepository;
import com.austin.trading.repository.ThemeReplaySnapshotRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ThemeReplayFixtureTest {

    private static final String FIXTURE = "replay/theme-first/passive-components-2026-05-22.json";
    private final ObjectMapper objectMapper = new ObjectMapper();
    private ThemeReplaySnapshotRepository snapshotRepository;
    private ThemeReplayNodeRepository nodeRepository;
    private ThemeReplayEdgeRepository edgeRepository;
    private ThemeReplayTimelineService service;
    private CandidateStockRepository candidateStockRepository;

    @BeforeEach
    void setUp() {
        snapshotRepository = mock(ThemeReplaySnapshotRepository.class);
        nodeRepository = mock(ThemeReplayNodeRepository.class);
        edgeRepository = mock(ThemeReplayEdgeRepository.class);
        candidateStockRepository = mock(CandidateStockRepository.class);
        service = new ThemeReplayTimelineService(
                snapshotRepository,
                nodeRepository,
                edgeRepository,
                mock(ThemeLeadershipSnapshotRepository.class),
                mock(ThemeLeaderRetentionRepository.class),
                mock(ThemePeerShadowCandidateRepository.class),
                candidateStockRepository,
                objectMapper
        );
    }

    @Test
    void passiveComponentsReplayBuildsLeaderPeerTimelineWithSafetyBoundary() throws Exception {
        LocalDate date = LocalDate.of(2026, 5, 22);
        when(candidateStockRepository.findByTradingDateOrderByScoreDesc(eq(date), any(Pageable.class)))
                .thenReturn(fixtureRows());

        var timeline = service.build(date);

        assertThat(timeline.tradingDate()).isEqualTo(date);
        assertThat(timeline.themeTag()).isEqualTo("被動元件");
        assertThat(timeline.shadowOnly()).isTrue();
        assertThat(timeline.replayOnly()).isTrue();
        assertThat(timeline.safetyBoundary().researchUniverseNotTradable()).isTrue();
        assertThat(timeline.nodes()).hasSizeGreaterThanOrEqualTo(6);
        assertThat(timeline.nodes()).filteredOn(n -> "2327".equals(n.symbol())).first().satisfies(n -> {
            assertThat(n.stockName()).isEqualTo("國巨");
            assertThat(n.researchRole()).isEqualTo("THEME_LEADER");
            assertThat(n.leadershipOnly()).isTrue();
            assertThat(n.tradableUniverse()).isFalse();
            assertThat(n.leaderTradable()).isFalse();
        });
        for (String peer : Set.of("2492", "3026", "3090", "6173", "2375")) {
            assertThat(timeline.nodes()).filteredOn(n -> peer.equals(n.symbol())).first().satisfies(n -> {
                assertThat(n.researchRole()).isEqualTo("PEER_SHADOW");
                assertThat(n.tradableUniverse()).isFalse();
                assertThat(n.themeLeaderSymbol()).isEqualTo("2327");
            });
            assertThat(timeline.edges()).anySatisfy(e -> {
                assertThat(e.fromSymbol()).isEqualTo("2327");
                assertThat(e.toSymbol()).isEqualTo(peer);
                assertThat(e.edgeType()).isEqualTo("LEADER_TO_PEER");
            });
        }
        assertThat(timeline.snapshot()).satisfies(s -> {
            assertThat(s.themeTag()).isEqualTo("被動元件");
            assertThat(s.researchUniverseCount()).isGreaterThanOrEqualTo(6);
            assertThat(s.tradableUniverseCount()).isZero();
        });
        assertThat(timeline.events()).extracting("eventType")
                .contains("THEME_EMERGED", "LEADER_IDENTIFIED", "PEER_DISCOVERED", "RISK_REJECTED", "FINAL_DECISION_REST");
    }

    private List<CandidateStockEntity> fixtureRows() throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(FIXTURE)) {
            assertThat(in).isNotNull();
            JsonNode root = objectMapper.readTree(in);
            return StreamSupport.stream(root.path("candidates").spliterator(), false)
                    .map(node -> toCandidate(root.path("tradingDate").asText(), node))
                    .toList();
        }
    }

    private CandidateStockEntity toCandidate(String tradingDate, JsonNode node) {
        CandidateStockEntity entity = new CandidateStockEntity();
        entity.setTradingDate(LocalDate.parse(tradingDate));
        entity.setSymbol(node.path("symbol").asText());
        entity.setStockName(node.path("stockName").asText());
        entity.setThemeTag(node.path("themeTag").asText());
        entity.setScore(node.path("score").decimalValue());
        entity.setCandidateRole(node.path("candidateRole").asText());
        entity.setThemeImportanceScore(node.path("themeImportanceScore").decimalValue());
        entity.setTradableScore(node.path("tradableScore").decimalValue());
        entity.setShadowRankScore(node.path("shadowRankScore").decimalValue());
        entity.setThemeLeaderSymbol(node.path("themeLeaderSymbol").asText());
        entity.setIsThemeLeader(node.path("isThemeLeader").asBoolean());
        entity.setLeaderTradable(node.path("leaderTradable").asBoolean());
        entity.setLeaderRetentionReason(node.path("rejectionReason").asText());
        entity.setPayloadJson(node.toString());
        return entity;
    }
}
