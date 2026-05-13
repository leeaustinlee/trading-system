package com.austin.trading.service;

import com.austin.trading.dto.request.KolStructuredResultRequest;
import com.austin.trading.entity.KolThemeSignalEntity;
import com.austin.trading.repository.KolThemeSignalRepository;
import com.austin.trading.repository.KolThemeStockMappingRepository;
import com.austin.trading.repository.ThemeSignalEvidenceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class KolSignalExtractionServiceTests {

    private KolThemeSignalRepository signalRepo;
    private KolThemeStockMappingRepository mappingRepo;
    private ThemeSignalEvidenceRepository evidenceRepo;
    private KolSignalTraceService traceService;
    private KolSignalExtractionService service;

    @BeforeEach
    void setUp() {
        signalRepo = mock(KolThemeSignalRepository.class);
        mappingRepo = mock(KolThemeStockMappingRepository.class);
        evidenceRepo = mock(ThemeSignalEvidenceRepository.class);
        traceService = mock(KolSignalTraceService.class);
        service = new KolSignalExtractionService(signalRepo, mappingRepo, evidenceRepo, traceService, new ObjectMapper());
    }

    @Test
    void structuredResult_writesMappingEvidenceAndTrace() {
        KolThemeSignalEntity signal = new KolThemeSignalEntity();
        ReflectionTestUtils.setField(signal, "id", 10L);
        signal.setTradingDate(LocalDate.of(2026, 5, 13));
        signal.setSourceKey("pod-a");
        signal.setSourceType("PODCAST");
        signal.setPayloadJson("{}");
        when(signalRepo.findById(10L)).thenReturn(Optional.of(signal));
        when(signalRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(mappingRepo.findBySignalIdAndThemeTagAndSymbol(any(), any(), any())).thenReturn(Optional.empty());

        KolStructuredResultRequest request = new KolStructuredResultRequest(List.of(
                new KolStructuredResultRequest.ThemeItem(
                        "AI伺服器",
                        "POSITIVE",
                        new BigDecimal("0.8"),
                        "reasoned demand discussion",
                        List.of(new KolStructuredResultRequest.StockItem("2382", "廣達", new BigDecimal("0.7"), Map.of("role", "ODM"))),
                        List.of(new KolStructuredResultRequest.EvidenceItem("REASONED_ANALYSIS", "POSITIVE", "capex discussion", new BigDecimal("0.9"), null)),
                        null
                )
        ), Map.of("model", "manual-ai"));

        KolThemeSignalEntity saved = service.applyStructuredResult(10L, request);

        assertThat(saved.getSignalStatus()).isEqualTo("STRUCTURED");
        verify(mappingRepo).countBySignalId(10L);
        verify(evidenceRepo).countBySignalId(10L);
        verify(mappingRepo).deleteBySignalId(10L);
        verify(evidenceRepo).deleteBySignalId(10L);
        verify(mappingRepo).save(argThat(e ->
                e.getSignalId().equals(10L)
                        && e.getThemeTag().equals("AI伺服器")
                        && e.getSymbol().equals("2382")
                        && e.getDirection().equals("POSITIVE")));
        verify(evidenceRepo).save(argThat(e ->
                e.getSignalId().equals(10L)
                        && e.getEvidenceType().equals("REASONED_ANALYSIS")
                        && e.getDirection().equals("POSITIVE")));
        verify(traceService).write(eq(10L), eq("EXTRACTION"), eq("STRUCTURED_RESULT_REPLACED"),
                argThat(detail -> Boolean.FALSE.equals(detail.get("replacedExisting"))
                        && detail.get("oldMappingCount").equals(0L)
                        && detail.get("oldEvidenceCount").equals(0L)));
    }

    @Test
    void structuredResult_replacesExistingExtractionForSameSignal() {
        KolThemeSignalEntity signal = new KolThemeSignalEntity();
        ReflectionTestUtils.setField(signal, "id", 10L);
        signal.setTradingDate(LocalDate.of(2026, 5, 13));
        signal.setSourceKey("pod-a");
        signal.setSourceType("PODCAST");
        signal.setPayloadJson("{}");
        when(signalRepo.findById(10L)).thenReturn(Optional.of(signal));
        when(signalRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(mappingRepo.countBySignalId(10L)).thenReturn(2L);
        when(evidenceRepo.countBySignalId(10L)).thenReturn(3L);

        KolStructuredResultRequest request = new KolStructuredResultRequest(List.of(
                new KolStructuredResultRequest.ThemeItem(
                        "AI伺服器",
                        "POSITIVE",
                        new BigDecimal("0.8"),
                        "reasoned demand discussion",
                        List.of(new KolStructuredResultRequest.StockItem("2382", "廣達", new BigDecimal("0.7"), null)),
                        List.of(new KolStructuredResultRequest.EvidenceItem("DIRECT_CLAIM", "POSITIVE", "demand", new BigDecimal("0.8"), null)),
                        null
                )
        ), Map.of());

        service.applyStructuredResult(10L, request);

        verify(mappingRepo).deleteBySignalId(10L);
        verify(evidenceRepo).deleteBySignalId(10L);
        verify(mappingRepo, times(1)).save(any());
        verify(evidenceRepo, times(1)).save(any());
        verify(traceService).write(eq(10L), eq("EXTRACTION"), eq("STRUCTURED_RESULT_REPLACED"),
                argThat(detail -> Boolean.TRUE.equals(detail.get("replacedExisting"))
                        && detail.get("oldMappingCount").equals(2L)
                        && detail.get("oldEvidenceCount").equals(3L)
                        && detail.get("mappingCount").equals(1)
                        && detail.get("evidenceCount").equals(1)));
    }
}
