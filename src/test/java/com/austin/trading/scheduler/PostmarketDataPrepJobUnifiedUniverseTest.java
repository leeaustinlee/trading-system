package com.austin.trading.scheduler;

import com.austin.trading.client.MarketBreadthClient;
import com.austin.trading.client.TwseMisClient;
import com.austin.trading.client.dto.MarketBreadth;
import com.austin.trading.dto.request.AiTaskCandidateRef;
import com.austin.trading.dto.request.CandidateBatchItemRequest;
import com.austin.trading.dto.response.CandidateBatchSaveResponse;
import com.austin.trading.dto.response.CandidateResponse;
import com.austin.trading.entity.AiTaskEntity;
import com.austin.trading.entity.MarketSnapshotEntity;
import com.austin.trading.repository.CandidateStockRepository;
import com.austin.trading.repository.MarketSnapshotRepository;
import com.austin.trading.service.AiTaskService;
import com.austin.trading.service.CandidateScanService;
import com.austin.trading.service.ClaudeCodeRequestWriterService;
import com.austin.trading.service.DailyOrchestrationService;
import com.austin.trading.service.OrchestrationStep;
import com.austin.trading.service.SchedulerLogService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PostmarketDataPrepJobUnifiedUniverseTest {

    private static final Path SCAN_PATH = Path.of("D:/ai/stock/market-breadth-scan.json");

    private final MarketBreadthClient marketBreadthClient = mock(MarketBreadthClient.class);
    private final TwseMisClient twseMisClient = mock(TwseMisClient.class);
    private final CandidateScanService candidateScanService = mock(CandidateScanService.class);
    private final CandidateStockRepository candidateStockRepository = mock(CandidateStockRepository.class);
    private final MarketSnapshotRepository marketSnapshotRepository = mock(MarketSnapshotRepository.class);
    private final SchedulerLogService schedulerLogService = mock(SchedulerLogService.class);
    private final ClaudeCodeRequestWriterService requestWriterService = mock(ClaudeCodeRequestWriterService.class);
    private final DailyOrchestrationService orchestrationService = mock(DailyOrchestrationService.class);
    private final AiTaskService aiTaskService = mock(AiTaskService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        Files.createDirectories(SCAN_PATH.getParent());
        Files.deleteIfExists(SCAN_PATH);
        System.setProperty("trading.postmarket.marketBreadthScanPath", SCAN_PATH.toString());

        when(orchestrationService.markRunning(any(LocalDate.class), eq(OrchestrationStep.POSTMARKET_DATA_PREP)))
                .thenReturn(true);
        when(candidateStockRepository.findByTradingDateOrderByScoreDesc(any(LocalDate.class), any(PageRequest.class)))
                .thenReturn(List.of());
        when(twseMisClient.getQuotesWithOtcFallback(anyList())).thenReturn(List.of());
        when(candidateScanService.saveBatchWithGate(anyList())).thenReturn(
                new CandidateBatchSaveResponse(3, 3, 0, List.of(), List.of()));
        when(requestWriterService.writeRequest(any(), anyString(), any(LocalDate.class), anyList(), anyString()))
                .thenReturn(true);

        AiTaskEntity task = new AiTaskEntity();
        task.setId(172L);
        when(aiTaskService.createTask(any(LocalDate.class), eq("POSTMARKET"), eq(null), anyList(), anyString(), anyString()))
                .thenReturn(task);
    }

    @AfterEach
    void tearDown() throws Exception {
        Files.deleteIfExists(SCAN_PATH);
        System.clearProperty("trading.postmarket.marketBreadthScanPath");
    }

    @Test
    void run_usesFreshScanUnifiedUniverseAndWritesMarketContext() throws Exception {
        Files.writeString(SCAN_PATH, "\uFEFF" + """
                {
                  "super_strong_5": [
                    {"Code":"1216","Name":"統一","Theme":"食品","Score":9.1},
                    {"Code":"2458","Name":"義隆","Theme":"IC設計","Score":8.8}
                  ],
                  "final_candidates_5": [
                    {"Code":"2458","Name":"義隆","Theme":"IC設計","Score":8.8},
                    {"Code":"8926","Name":"台汽電","Theme":"電力","Score":8.2}
                  ]
                }
                """);
        when(marketBreadthClient.getBreadth(any(LocalDate.class)))
                .thenReturn(Optional.of(new MarketBreadth(1200, 800, 100, 21000.0, 180.0, 1.1, "20260511")));
        when(candidateScanService.getCurrentCandidates(20)).thenReturn(List.of(
                candidate("2458", "義隆", "IC設計", "8.7"),
                candidate("5388", "中磊", "網通", "7.6")
        ));

        PostmarketDataPrepJob job = new PostmarketDataPrepJob(
                marketBreadthClient,
                twseMisClient,
                candidateScanService,
                candidateStockRepository,
                marketSnapshotRepository,
                schedulerLogService,
                requestWriterService,
                orchestrationService,
                aiTaskService,
                objectMapper
        );

        job.run();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AiTaskCandidateRef>> refsCap = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<String> summaryCap = ArgumentCaptor.forClass(String.class);
        verify(aiTaskService).createTask(any(LocalDate.class), eq("POSTMARKET"), eq(null), refsCap.capture(), summaryCap.capture(), anyString());
        assertThat(refsCap.getValue()).extracting(AiTaskCandidateRef::symbol)
                .containsExactly("1216", "2458", "8926");
        assertThat(summaryCap.getValue()).contains("source=FRESH_SCAN").contains("marketGrade=A");

        ArgumentCaptor<List<String>> symbolsCap = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<String> ctxCap = ArgumentCaptor.forClass(String.class);
        verify(requestWriterService).writeRequest(eq(172L), eq("POSTMARKET"), any(LocalDate.class), symbolsCap.capture(), ctxCap.capture());
        assertThat(symbolsCap.getValue()).containsExactly("1216", "2458", "8926");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CandidateBatchItemRequest>> batchCap = ArgumentCaptor.forClass(List.class);
        verify(candidateScanService).saveBatchWithGate(batchCap.capture());
        assertThat(batchCap.getValue()).hasSize(3);
        assertThat(batchCap.getValue()).extracting(CandidateBatchItemRequest::symbol)
                .containsExactly("1216", "2458", "8926");
        assertThat(batchCap.getValue()).allSatisfy(item ->
                assertThat(item.tradingDate()).isAfter(LocalDate.now()));

        JsonNode ctx = objectMapper.readTree(ctxCap.getValue());
        assertThat(ctx.path("universeSource").asText()).isEqualTo("FRESH_SCAN");
        assertThat(ctx.path("marketGrade").asText()).isEqualTo("A");
        assertThat(ctx.path("marketGradeSource").asText()).isEqualTo("JAVA_POSTMARKET_1505");
        assertThat(ctx.path("scoringUniverse").path("symbols")).hasSize(3);
        assertThat(ctx.path("scoringUniverse").path("symbols").get(0).asText()).isEqualTo("1216");

        ArgumentCaptor<MarketSnapshotEntity> snapshotCap = ArgumentCaptor.forClass(MarketSnapshotEntity.class);
        verify(marketSnapshotRepository).save(snapshotCap.capture());
        assertThat(snapshotCap.getValue().getMarketGrade()).isEqualTo("A");
        assertThat(snapshotCap.getValue().getPayloadJson()).contains("JAVA_POSTMARKET_1505");
    }

    @Test
    void run_fallsBackToCurrentCandidatesWhenFreshScanMissing() throws Exception {
        when(marketBreadthClient.getBreadth(any(LocalDate.class)))
                .thenReturn(Optional.of(new MarketBreadth(900, 1100, 80, 20700.0, -120.0, -0.9, "20260511")));
        when(candidateScanService.getCurrentCandidates(20)).thenReturn(List.of(
                candidate("1216", "統一", "食品", "7.9"),
                candidate("5388", "中磊", "網通", "7.6")
        ));

        PostmarketDataPrepJob job = new PostmarketDataPrepJob(
                marketBreadthClient,
                twseMisClient,
                candidateScanService,
                candidateStockRepository,
                marketSnapshotRepository,
                schedulerLogService,
                requestWriterService,
                orchestrationService,
                aiTaskService,
                objectMapper
        );

        job.run();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AiTaskCandidateRef>> refsCap = ArgumentCaptor.forClass(List.class);
        verify(aiTaskService).createTask(any(LocalDate.class), eq("POSTMARKET"), eq(null), refsCap.capture(), anyString(), anyString());
        assertThat(refsCap.getValue()).extracting(AiTaskCandidateRef::symbol)
                .containsExactly("1216", "5388");

        ArgumentCaptor<String> ctxCap = ArgumentCaptor.forClass(String.class);
        verify(requestWriterService).writeRequest(eq(172L), eq("POSTMARKET"), any(LocalDate.class), anyList(), ctxCap.capture());
        JsonNode ctx = objectMapper.readTree(ctxCap.getValue());
        assertThat(ctx.path("universeSource").asText()).isEqualTo("FALLBACK_CURRENT_CANDIDATES");
        assertThat(ctx.path("marketGrade").asText()).isEqualTo("C");
    }

    @Test
    void run_createTaskFails_throwsAndDoesNotWriteRequest() {
        when(marketBreadthClient.getBreadth(any(LocalDate.class))).thenReturn(Optional.empty());
        when(candidateScanService.getCurrentCandidates(20)).thenReturn(List.of(
                candidate("1216", "統一", "食品", "7.9")
        ));
        when(aiTaskService.createTask(any(LocalDate.class), eq("POSTMARKET"), eq(null), anyList(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("task create failed"));

        PostmarketDataPrepJob job = new PostmarketDataPrepJob(
                marketBreadthClient,
                twseMisClient,
                candidateScanService,
                candidateStockRepository,
                marketSnapshotRepository,
                schedulerLogService,
                requestWriterService,
                orchestrationService,
                aiTaskService,
                objectMapper
        );

        assertThatThrownBy(job::run)
                .isInstanceOf(RuntimeException.class)
                .hasRootCauseMessage("task create failed");
        verify(requestWriterService, never()).writeRequest(any(), anyString(), any(LocalDate.class), anyList(), anyString());
    }

    private CandidateResponse candidate(String symbol, String name, String theme, String score) {
        BigDecimal s = new BigDecimal(score);
        return new CandidateResponse(
                LocalDate.of(2026, 5, 11),
                symbol,
                name,
                s,
                theme + "；測試候選",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                theme,
                null,
                s,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }
}
