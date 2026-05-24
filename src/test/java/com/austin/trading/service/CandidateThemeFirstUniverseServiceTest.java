package com.austin.trading.service;

import com.austin.trading.client.TwseMisClient;
import com.austin.trading.dto.request.CandidateBatchItemRequest;
import com.austin.trading.dto.response.CandidateResponse;
import com.austin.trading.engine.MomentumCandidateEngine;
import com.austin.trading.entity.CandidateStockEntity;
import com.austin.trading.entity.ThemeSnapshotEntity;
import com.austin.trading.repository.CandidateStockRepository;
import com.austin.trading.repository.StockEvaluationRepository;
import com.austin.trading.repository.ThemeSnapshotRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CandidateThemeFirstUniverseServiceTest {

    private static final String PASSIVE_COMPONENTS_FIXTURE = "replay/theme-first/passive-components-2026-05-22.json";

    private CandidateStockRepository candidateStockRepository;
    private StockEvaluationRepository stockEvaluationRepository;
    private ThemeSnapshotRepository themeSnapshotRepository;
    private CandidateScanService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        candidateStockRepository = mock(CandidateStockRepository.class);
        stockEvaluationRepository = mock(StockEvaluationRepository.class);
        themeSnapshotRepository = mock(ThemeSnapshotRepository.class);
        TwseMisClient twseMisClient = mock(TwseMisClient.class);
        ScoreConfigService scoreConfigService = mock(ScoreConfigService.class);
        MomentumCandidateEngine momentumCandidateEngine = new MomentumCandidateEngine(scoreConfigService);
        when(stockEvaluationRepository.findByTradingDate(any())).thenReturn(List.of());
        service = new CandidateScanService(
                candidateStockRepository,
                stockEvaluationRepository,
                themeSnapshotRepository,
                twseMisClient,
                momentumCandidateEngine,
                scoreConfigService,
                objectMapper
        );
    }

    @Test
    void themeFirstCurrentCandidatesDefaultToUniverseTenAndExposeScoresRoles() {
        LocalDate date = LocalDate.of(2026, 5, 23);
        CandidateStockEntity marker = candidate(date, "9999", "marker", "1.0", "OTHER");
        List<CandidateStockEntity> rows = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            CandidateStockEntity c = candidate(date, String.format("24%02d", i), "股" + i,
                    String.valueOf(100 - i), i % 2 == 0 ? "被動元件" : "AI伺服器");
            c.setCandidateRole(i == 1 ? "THEME_LEADER" : (i % 2 == 0 ? "LOW_BASE_FOLLOWER" : "BREAKOUT_CANDIDATE"));
            c.setThemeImportanceScore(new BigDecimal(i % 2 == 0 ? "9.0" : "8.0"));
            c.setTradableScore(new BigDecimal(i <= 10 ? "7.5" : "2.0"));
            c.setShadowRankScore(new BigDecimal(100 - i));
            c.setThemeTraceId("trace-" + i);
            rows.add(c);
        }
        when(candidateStockRepository.findTopByOrderByTradingDateDesc()).thenReturn(Optional.of(marker));
        when(candidateStockRepository.findByTradingDateOrderByScoreDesc(eq(date), any(Pageable.class))).thenReturn(rows);
        when(themeSnapshotRepository.findByTradingDateOrderByRankingOrderAsc(date)).thenReturn(List.of(
                theme(date, "被動元件", 1, "9.6"),
                theme(date, "AI伺服器", 2, "8.8")
        ));

        List<CandidateResponse> result = service.getThemeFirstCurrentCandidates(99);

        assertThat(result).hasSize(10);
        assertThat(result).first().satisfies(c -> {
            assertThat(c.candidateRole()).isEqualTo("THEME_LEADER");
            assertThat(c.themeImportanceScore()).isNotNull();
            assertThat(c.tradableScore()).isNotNull();
            assertThat(c.shadowRankScore()).isNotNull();
            assertThat(c.themeTraceId()).startsWith("trace-");
        });
        assertThat(result).extracting(CandidateResponse::themeTag).contains("被動元件");
    }

    @Test
    void saveBatchPersistsThemeFirstScoreFieldsWithoutChangingRiskGateSemantics() {
        LocalDate date = LocalDate.of(2026, 5, 23);
        CandidateBatchItemRequest item = new CandidateBatchItemRequest(
                date, "2327", "國巨", new BigDecimal("9.1"), "retained leader pullback only",
                "被動元件", "電子零組件", "{}",
                "SWING", "900-920", new BigDecimal("860"), new BigDecimal("960"), new BigDecimal("1010"),
                new BigDecimal("2.2"), true,
                "THEME_LEADER", new BigDecimal("9.7"), new BigDecimal("6.4"), new BigDecimal("8.1"),
                "2327", true, false, "retained leader; shadow-first", "trace-2327"
        );
        when(candidateStockRepository.findByTradingDateAndSymbol(date, "2327")).thenReturn(Optional.empty());
        when(candidateStockRepository.findByTradingDateOrderByScoreDesc(eq(date), any(Pageable.class))).thenReturn(List.of());
        when(stockEvaluationRepository.findByTradingDateAndSymbol(date, "2327")).thenReturn(Optional.empty());

        service.saveBatch(List.of(item));

        verify(candidateStockRepository).save(org.mockito.ArgumentMatchers.argThat(c ->
                "2327".equals(c.getSymbol())
                        && "THEME_LEADER".equals(c.getCandidateRole())
                        && new BigDecimal("9.7").compareTo(c.getThemeImportanceScore()) == 0
                        && new BigDecimal("6.4").compareTo(c.getTradableScore()) == 0
                        && new BigDecimal("8.1").compareTo(c.getShadowRankScore()) == 0
                        && "trace-2327".equals(c.getThemeTraceId())
        ));
    }



    @Test
    void themeFirstUniverse_canReplayPassiveComponentFixtureWithoutLiveCandidateStock() throws Exception {
        LocalDate date = LocalDate.of(2026, 5, 22);
        CandidateStockEntity marker = candidate(date, "9999", "marker", "1.0", "OTHER");
        List<CandidateStockEntity> fixtureRows = passiveComponentFixtureRows();
        List<CandidateStockEntity> baselineRows = fixtureRows.stream()
                .map(this::baselineCopy)
                .toList();

        when(candidateStockRepository.findTopByOrderByTradingDateDesc()).thenReturn(Optional.of(marker));
        when(candidateStockRepository.findByTradingDateOrderByScoreDesc(eq(date), any(Pageable.class)))
                .thenReturn(fixtureRows, baselineRows);
        when(themeSnapshotRepository.findByTradingDateOrderByRankingOrderAsc(date)).thenReturn(List.of(
                theme(date, "被動元件", 1, "9.8")
        ));

        List<CandidateResponse> universe = service.getThemeFirstCurrentCandidates(10);
        Map<String, Object> replay = service.buildThemeFirstReplay(date, universe);

        assertThat(universe).hasSize(6);
        assertThat(universe).extracting(CandidateResponse::symbol)
                .containsExactly("2327", "2492", "3026", "3090", "6173", "2375");

        CandidateResponse leader = universe.get(0);
        assertThat(leader.symbol()).isEqualTo("2327");
        assertThat(leader.candidateRole()).isEqualTo("THEME_LEADER");
        assertThat(leader.isThemeLeader()).isTrue();
        assertThat(leader.leaderTradable()).isFalse();
        assertThat(leader.themeLeaderSymbol()).isEqualTo("2327");
        assertThat(leader.themeImportanceScore()).isEqualByComparingTo(new BigDecimal("9.9"));
        assertThat(leader.tradableScore()).isEqualByComparingTo(new BigDecimal("1.8"));
        assertThat(leader.shadowRankScore()).isEqualByComparingTo(new BigDecimal("9.7"));

        assertThat(universe).allSatisfy(c -> {
            assertThat(c.themeImportanceScore()).isNotNull();
            assertThat(c.tradableScore()).isNotNull();
            assertThat(c.shadowRankScore()).isNotNull();
            assertThat(c.themeLeaderSymbol()).isEqualTo("2327");
        });
        assertThat(universe.stream().skip(1).map(CandidateResponse::candidateRole).collect(Collectors.toSet()))
                .containsExactly("PEER_SHADOW_CONTEXT");

        long baselineDiversification = baselineRows.stream()
                .map(CandidateStockEntity::getCandidateRole)
                .distinct()
                .count();
        assertThat((Long) replay.get("candidateDiversification")).isGreaterThan(baselineDiversification);
        assertThat((BigDecimal) replay.get("leaderRetentionRate")).isGreaterThan(BigDecimal.ZERO);
        assertThat((BigDecimal) replay.get("peerDiscoveryHitRate")).isGreaterThan(BigDecimal.ZERO);
    }

    private CandidateStockEntity candidate(LocalDate date, String symbol, String name, String score, String theme) {
        CandidateStockEntity entity = new CandidateStockEntity();
        entity.setTradingDate(date);
        entity.setSymbol(symbol);
        entity.setStockName(name);
        entity.setScore(new BigDecimal(score));
        entity.setReason("test");
        entity.setThemeTag(theme);
        return entity;
    }



    private List<CandidateStockEntity> passiveComponentFixtureRows() throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(PASSIVE_COMPONENTS_FIXTURE)) {
            assertThat(in).as("fixture " + PASSIVE_COMPONENTS_FIXTURE).isNotNull();
            JsonNode root = objectMapper.readTree(in);
            return StreamSupport.stream(root.path("candidates").spliterator(), false)
                    .map(node -> fixtureCandidate(root.path("tradingDate").asText(), node))
                    .toList();
        }
    }

    private CandidateStockEntity fixtureCandidate(String tradingDate, JsonNode node) {
        CandidateStockEntity entity = candidate(
                LocalDate.parse(tradingDate),
                node.path("symbol").asText(),
                node.path("stockName").asText(),
                node.path("score").asText(),
                node.path("themeTag").asText()
        );
        entity.setSector(node.path("sector").asText(null));
        entity.setCandidateRole(node.path("candidateRole").asText(null));
        entity.setThemeImportanceScore(node.path("themeImportanceScore").decimalValue());
        entity.setTradableScore(node.path("tradableScore").decimalValue());
        entity.setShadowRankScore(node.path("shadowRankScore").decimalValue());
        entity.setThemeLeaderSymbol(node.path("themeLeaderSymbol").asText(null));
        entity.setIsThemeLeader(node.path("isThemeLeader").asBoolean(false));
        entity.setLeaderTradable(node.path("leaderTradable").asBoolean(false));
        entity.setLeaderRetentionReason(node.path("rejectionReason").asText(null));
        entity.setThemeTraceId("passive-components-2026-05-22");
        entity.setReason(node.path("rejectionReason").asText());
        entity.setPayloadJson(node.toString());
        return entity;
    }

    private CandidateStockEntity baselineCopy(CandidateStockEntity source) {
        CandidateStockEntity entity = candidate(
                source.getTradingDate(),
                source.getSymbol(),
                source.getStockName(),
                source.getScore().toPlainString(),
                source.getThemeTag()
        );
        entity.setCandidateRole("BASELINE_CURRENT_CANDIDATE");
        entity.setThemeImportanceScore(source.getThemeImportanceScore());
        entity.setTradableScore(source.getTradableScore());
        entity.setShadowRankScore(source.getShadowRankScore());
        return entity;
    }

    private ThemeSnapshotEntity theme(LocalDate date, String themeTag, int rank, String score) {
        ThemeSnapshotEntity entity = new ThemeSnapshotEntity();
        entity.setTradingDate(date);
        entity.setThemeTag(themeTag);
        entity.setRankingOrder(rank);
        entity.setFinalThemeScore(new BigDecimal(score));
        entity.setMarketBehaviorScore(new BigDecimal("8.5"));
        entity.setThemeHeatScore(new BigDecimal("8.8"));
        entity.setThemeContinuationScore(new BigDecimal("8.1"));
        return entity;
    }
}
