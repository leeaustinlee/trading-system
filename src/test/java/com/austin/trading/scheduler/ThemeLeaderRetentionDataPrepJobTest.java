package com.austin.trading.scheduler;

import com.austin.trading.client.MarketBreadthClient;
import com.austin.trading.client.TaifexClient;
import com.austin.trading.client.TwseInstitutionalClient;
import com.austin.trading.client.TwseMisClient;
import com.austin.trading.client.dto.InstitutionalFlow;
import com.austin.trading.client.dto.MarketBreadth;
import com.austin.trading.client.dto.StockQuote;
import com.austin.trading.dto.response.CandidateResponse;
import com.austin.trading.entity.AiTaskEntity;
import com.austin.trading.entity.CandidateStockEntity;
import com.austin.trading.repository.CandidateStockRepository;
import com.austin.trading.repository.MarketSnapshotRepository;
import com.austin.trading.service.AiTaskService;
import com.austin.trading.service.CandidateScanService;
import com.austin.trading.service.ClaudeCodeRequestWriterService;
import com.austin.trading.service.DailyOrchestrationService;
import com.austin.trading.service.OrchestrationStep;
import com.austin.trading.service.SchedulerLogService;
import com.austin.trading.service.ThemeLeaderRetentionService;
import com.austin.trading.service.ThemePeerDiscoveryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ThemeLeaderRetentionDataPrepJobTest {

    @TempDir
    Path tempDir;

    private final ClaudeCodeRequestWriterService.LeaderContext yageoLeader = new ClaudeCodeRequestWriterService.LeaderContext(
            "2327", "國巨", "MLCC", 1, false,
            "POSTMARKET super_strong_5 retained for next-phase leadership validation",
            List.of("MARKET_LEADERSHIP", "THEME_VALIDATION", "PEER_DISCOVERY")
    );

    @Test
    void postmarketDataPrep_retainsSuperStrong5ForT86PremarketAndOpening() throws Exception {
        Path scanPath = tempDir.resolve("market-breadth-scan.json");
        Files.writeString(scanPath, """
                {
                  "super_strong_5": [
                    {"Code":"2327","Name":"國巨","Theme":"MLCC","Score":9.8}
                  ],
                  "final_candidates_5": [
                    {"Code":"2458","Name":"義隆","Theme":"IC設計","Score":8.8}
                  ]
                }
                """);
        System.setProperty("trading.postmarket.marketBreadthScanPath", scanPath.toString());
        try {
            MarketBreadthClient breadthClient = mock(MarketBreadthClient.class);
            TwseMisClient twseMisClient = mock(TwseMisClient.class);
            CandidateScanService scanService = mock(CandidateScanService.class);
            CandidateStockRepository repository = mock(CandidateStockRepository.class);
            ClaudeCodeRequestWriterService requestWriterService = mock(ClaudeCodeRequestWriterService.class);
            DailyOrchestrationService orchestrationService = mock(DailyOrchestrationService.class);
            AiTaskService aiTaskService = mock(AiTaskService.class);
            ThemeLeaderRetentionService retentionService = mock(ThemeLeaderRetentionService.class);
            LocalDate today = LocalDate.now();
            AiTaskEntity task = new AiTaskEntity(); task.setId(300L);

            when(orchestrationService.markRunning(eq(today), eq(OrchestrationStep.POSTMARKET_DATA_PREP))).thenReturn(true);
            when(breadthClient.getBreadth(today)).thenReturn(Optional.of(new MarketBreadth(1200, 800, 100, 21000.0, 180.0, 1.1, "20260523")));
            when(scanService.getCurrentCandidates(20)).thenReturn(List.of());
            when(twseMisClient.getQuotesWithOtcFallback(anyList())).thenReturn(List.of());
            when(repository.findByTradingDateOrderByScoreDesc(eq(today), any(PageRequest.class))).thenReturn(List.of());
            when(aiTaskService.createTask(eq(today), eq("POSTMARKET"), eq(null), anyList(), anyString(), anyString())).thenReturn(task);
            when(scanService.saveBatchWithGate(anyList()))
                    .thenAnswer(invocation -> {
                        List<?> batch = invocation.getArgument(0);
                        return new com.austin.trading.dto.response.CandidateBatchSaveResponse(batch.size(), batch.size(), 0, List.of(), List.of());
                    });
            when(requestWriterService.writeRequest(any(), anyString(), any(LocalDate.class), anyList(), anyString())).thenReturn(true);
            when(retentionService.retainPostmarketSuperStrong(any(LocalDate.class), anyList())).thenReturn(3);

            new PostmarketDataPrepJob(breadthClient, twseMisClient, scanService, repository,
                    mock(MarketSnapshotRepository.class), mock(SchedulerLogService.class), requestWriterService,
                    orchestrationService, aiTaskService, retentionService, new com.fasterxml.jackson.databind.ObjectMapper()).run();

            ArgumentCaptor<List<CandidateResponse>> leadersCap = ArgumentCaptor.forClass(List.class);
            verify(retentionService).retainPostmarketSuperStrong(eq(today), leadersCap.capture());
            assertThat(leadersCap.getValue()).extracting(CandidateResponse::symbol).containsExactly("2327");
        } finally {
            System.clearProperty("trading.postmarket.marketBreadthScanPath");
        }
    }

    @Test
    void t86DataPrep_passesRetainedLeaderAsLeadershipOnlyNotTradableCandidate() {
        TwseInstitutionalClient institutionalClient = mock(TwseInstitutionalClient.class);
        CandidateStockRepository repository = mock(CandidateStockRepository.class);
        SchedulerLogService schedulerLogService = mock(SchedulerLogService.class);
        DailyOrchestrationService orchestrationService = mock(DailyOrchestrationService.class);
        AiTaskService aiTaskService = mock(AiTaskService.class);
        ClaudeCodeRequestWriterService requestWriterService = mock(ClaudeCodeRequestWriterService.class);
        ThemeLeaderRetentionService retentionService = mock(ThemeLeaderRetentionService.class);
        LocalDate today = LocalDate.now();
        CandidateStockEntity tradable = entity(today, "2458", "義隆", "IC設計");
        AiTaskEntity task = new AiTaskEntity(); task.setId(301L);

        when(orchestrationService.markRunning(eq(today), eq(OrchestrationStep.T86_DATA_PREP))).thenReturn(true);
        when(institutionalClient.getT86(today)).thenReturn(List.of(new InstitutionalFlow("2458", "義隆", 1L, 1L, 0L, 2L)));
        when(repository.findTopByOrderByTradingDateDesc()).thenReturn(Optional.of(tradable));
        when(repository.findByTradingDateOrderByScoreDesc(eq(today), any(PageRequest.class))).thenReturn(List.of(tradable));
        when(aiTaskService.createTask(eq(today), eq("T86_TOMORROW"), eq(null), anyList(), anyString(), anyString())).thenReturn(task);
        when(retentionService.loadLeaderContexts(today, "T86_TOMORROW")).thenReturn(List.of(yageoLeader));
        when(requestWriterService.writeRequest(any(Long.class), anyString(), any(LocalDate.class), anyList(), anyList(), anyString())).thenReturn(true);

        new T86DataPrepJob(institutionalClient, repository, schedulerLogService, orchestrationService,
                aiTaskService, requestWriterService, retentionService).run();

        ArgumentCaptor<List<String>> tradableCap = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<ClaudeCodeRequestWriterService.LeaderContext>> leaderCap = ArgumentCaptor.forClass(List.class);
        verify(requestWriterService).writeRequest(eq(301L), eq("T86_TOMORROW"), eq(today), tradableCap.capture(), leaderCap.capture(), anyString());
        assertThat(tradableCap.getValue()).containsExactly("2458");
        assertThat(leaderCap.getValue()).extracting(ClaudeCodeRequestWriterService.LeaderContext::symbol).containsExactly("2327");
    }

    @Test
    void premarketDataPrep_passesRetainedLeaderAsLeadershipOnlyNotTradableCandidate() {
        CandidateScanService scanService = mock(CandidateScanService.class);
        ClaudeCodeRequestWriterService requestWriterService = mock(ClaudeCodeRequestWriterService.class);
        ThemeLeaderRetentionService retentionService = mock(ThemeLeaderRetentionService.class);
        DailyOrchestrationService orchestrationService = mock(DailyOrchestrationService.class);
        AiTaskService aiTaskService = mock(AiTaskService.class);
        TwseMisClient twseMisClient = mock(TwseMisClient.class);
        LocalDate today = LocalDate.now();
        AiTaskEntity task = new AiTaskEntity(); task.setId(302L);

        when(orchestrationService.markRunning(eq(today), eq(OrchestrationStep.PREMARKET_DATA_PREP))).thenReturn(true);
        when(scanService.getCandidatesByDate(eq(today), eq(10))).thenReturn(List.of(candidate(today, "2458")));
        when(twseMisClient.getQuotesWithOtcFallback(List.of("2458"))).thenReturn(List.of());
        when(aiTaskService.createTask(eq(today), eq("PREMARKET"), eq(null), anyList(), anyString(), anyString())).thenReturn(task);
        when(retentionService.loadLeaderContexts(today, "PREMARKET")).thenReturn(List.of(yageoLeader));
        when(requestWriterService.writeRequest(any(Long.class), anyString(), any(LocalDate.class), anyList(), anyList(), anyString())).thenReturn(true);

        new PremarketDataPrepJob(mock(TaifexClient.class), twseMisClient, scanService,
                mock(MarketSnapshotRepository.class), mock(SchedulerLogService.class), requestWriterService,
                orchestrationService, aiTaskService, retentionService).run();

        ArgumentCaptor<List<String>> tradableCap = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<ClaudeCodeRequestWriterService.LeaderContext>> leaderCap = ArgumentCaptor.forClass(List.class);
        verify(requestWriterService).writeRequest(eq(302L), eq("PREMARKET"), eq(today), tradableCap.capture(), leaderCap.capture(), anyString());
        assertThat(tradableCap.getValue()).containsExactly("2458");
        assertThat(leaderCap.getValue()).extracting(ClaudeCodeRequestWriterService.LeaderContext::symbol).containsExactly("2327");
    }

    @Test
    void premarketDataPrep_passesPeerShadowCandidatesAsPayloadContextOnly() {
        CandidateScanService scanService = mock(CandidateScanService.class);
        ClaudeCodeRequestWriterService requestWriterService = mock(ClaudeCodeRequestWriterService.class);
        ThemeLeaderRetentionService retentionService = mock(ThemeLeaderRetentionService.class);
        ThemePeerDiscoveryService peerDiscoveryService = mock(ThemePeerDiscoveryService.class);
        DailyOrchestrationService orchestrationService = mock(DailyOrchestrationService.class);
        AiTaskService aiTaskService = mock(AiTaskService.class);
        TwseMisClient twseMisClient = mock(TwseMisClient.class);
        LocalDate today = LocalDate.now();
        AiTaskEntity task = new AiTaskEntity(); task.setId(304L);
        ClaudeCodeRequestWriterService.PeerShadowContext peer = new ClaudeCodeRequestWriterService.PeerShadowContext(
                "2492", "SECOND_LEADER", "2327", "MLCC", false,
                new BigDecimal("9.20"), "same themeTag + hot stock overlap");

        when(orchestrationService.markRunning(eq(today), eq(OrchestrationStep.PREMARKET_DATA_PREP))).thenReturn(true);
        when(scanService.getCandidatesByDate(eq(today), eq(10))).thenReturn(List.of(candidate(today, "2458")));
        when(twseMisClient.getQuotesWithOtcFallback(List.of("2458"))).thenReturn(List.of());
        when(aiTaskService.createTask(eq(today), eq("PREMARKET"), eq(null), anyList(), anyString(), anyString())).thenReturn(task);
        when(retentionService.loadLeaderContexts(today, "PREMARKET")).thenReturn(List.of(yageoLeader));
        when(peerDiscoveryService.discoverAndSaveFromLeaderContexts(today, "PREMARKET", List.of(yageoLeader))).thenReturn(List.of());
        when(peerDiscoveryService.toPeerShadowContexts(List.of())).thenReturn(List.of(peer));
        when(requestWriterService.writeRequest(any(), anyString(), any(LocalDate.class), anyList(), anyList(), anyList(), anyString())).thenReturn(true);

        new PremarketDataPrepJob(mock(TaifexClient.class), twseMisClient, scanService,
                mock(MarketSnapshotRepository.class), mock(SchedulerLogService.class), requestWriterService,
                orchestrationService, aiTaskService, retentionService, peerDiscoveryService).run();

        ArgumentCaptor<List<String>> tradableCap = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<ClaudeCodeRequestWriterService.LeaderContext>> leaderCap = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<ClaudeCodeRequestWriterService.PeerShadowContext>> peerCap = ArgumentCaptor.forClass(List.class);
        verify(requestWriterService).writeRequest(eq(304L), eq("PREMARKET"), eq(today),
                tradableCap.capture(), leaderCap.capture(), peerCap.capture(), anyString());
        assertThat(tradableCap.getValue()).containsExactly("2458");
        assertThat(leaderCap.getValue()).extracting(ClaudeCodeRequestWriterService.LeaderContext::symbol).containsExactly("2327");
        assertThat(peerCap.getValue()).extracting(ClaudeCodeRequestWriterService.PeerShadowContext::symbol).containsExactly("2492");
        assertThat(peerCap.getValue()).allSatisfy(p -> assertThat(p.tradable()).isFalse());
    }

    @Test
    void openingDataPrep_passesRetainedLeaderAsLeadershipOnlyNotTradableCandidate() {
        CandidateScanService scanService = mock(CandidateScanService.class);
        CandidateStockRepository repository = mock(CandidateStockRepository.class);
        ClaudeCodeRequestWriterService requestWriterService = mock(ClaudeCodeRequestWriterService.class);
        ThemeLeaderRetentionService retentionService = mock(ThemeLeaderRetentionService.class);
        DailyOrchestrationService orchestrationService = mock(DailyOrchestrationService.class);
        AiTaskService aiTaskService = mock(AiTaskService.class);
        TwseMisClient twseMisClient = mock(TwseMisClient.class);
        LocalDate today = LocalDate.now();
        AiTaskEntity task = new AiTaskEntity(); task.setId(303L);

        when(orchestrationService.markRunning(eq(today), eq(OrchestrationStep.OPEN_DATA_PREP))).thenReturn(true);
        when(scanService.getCurrentCandidates(20)).thenReturn(List.of(candidate(today, "2458")));
        when(twseMisClient.getQuotesWithOtcFallback(List.of("2458"))).thenReturn(List.of(new StockQuote("2458", "義隆", "tse", 100.0, 99.0, 100.0, 101.0, 98.0, 100.0, 100.5, 1000L, "20260524", "09:01:00", true)));
        when(repository.findByTradingDateOrderByScoreDesc(eq(today), any(PageRequest.class))).thenReturn(List.of(entity(today, "2458", "義隆", "IC設計")));
        when(aiTaskService.createTask(eq(today), eq("OPENING"), eq(null), anyList(), anyString(), anyString())).thenReturn(task);
        when(retentionService.loadLeaderContexts(today, "OPENING")).thenReturn(List.of(yageoLeader));
        when(requestWriterService.writeRequest(any(Long.class), anyString(), any(LocalDate.class), anyList(), anyList(), anyString())).thenReturn(true);

        new OpenDataPrepJob(twseMisClient, scanService, repository, mock(SchedulerLogService.class),
                orchestrationService, aiTaskService, requestWriterService, retentionService).run();

        ArgumentCaptor<List<String>> tradableCap = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<ClaudeCodeRequestWriterService.LeaderContext>> leaderCap = ArgumentCaptor.forClass(List.class);
        verify(requestWriterService).writeRequest(eq(303L), eq("OPENING"), eq(today), tradableCap.capture(), leaderCap.capture(), anyString());
        assertThat(tradableCap.getValue()).containsExactly("2458");
        assertThat(leaderCap.getValue()).extracting(ClaudeCodeRequestWriterService.LeaderContext::symbol).containsExactly("2327");
    }

    private CandidateStockEntity entity(LocalDate date, String symbol, String name, String theme) {
        CandidateStockEntity entity = new CandidateStockEntity();
        entity.setTradingDate(date);
        entity.setSymbol(symbol);
        entity.setStockName(name);
        entity.setThemeTag(theme);
        entity.setScore(new BigDecimal("8.8"));
        entity.setPayloadJson("{}");
        return entity;
    }

    private CandidateResponse candidate(LocalDate date, String symbol) {
        return new CandidateResponse(date, symbol, "義隆", new BigDecimal("8.8"), "IC設計；測試",
                null, null, null, null, null, null, null,
                "IC設計", null, new BigDecimal("8.8"), null, null, null, null, null, null, null);
    }
}
