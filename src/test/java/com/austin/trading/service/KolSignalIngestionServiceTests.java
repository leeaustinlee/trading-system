package com.austin.trading.service;

import com.austin.trading.dto.request.KolSignalCreateRequest;
import com.austin.trading.entity.KolThemeSignalEntity;
import com.austin.trading.repository.KolThemeSignalRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class KolSignalIngestionServiceTests {

    private KolThemeSignalRepository signalRepo;
    private KolSignalTraceService traceService;
    private KolSignalIngestionService service;

    @BeforeEach
    void setUp() {
        signalRepo = mock(KolThemeSignalRepository.class);
        traceService = mock(KolSignalTraceService.class);
        service = new KolSignalIngestionService(signalRepo, traceService, new ObjectMapper());
    }

    @Test
    void duplicateRawContent_returnsExistingIdAndDoesNotCreateNewSignal() {
        KolThemeSignalEntity existing = signal("same content");
        ReflectionTestUtils.setField(existing, "id", 42L);
        when(signalRepo.findByContentHash(any())).thenReturn(Optional.of(existing));

        var response = service.create(new KolSignalCreateRequest(
                LocalDate.of(2026, 5, 13), "pod-a", "PODCAST", "title", "same content", null));

        assertThat(response.id()).isEqualTo(42L);
        assertThat(response.duplicate()).isTrue();
        verify(signalRepo, never()).save(any());
        verify(traceService).write(eq(42L), eq("INGEST"), eq("DEDUP_HIT"), any());
    }

    @Test
    void longRawContent_isTruncatedAndPayloadMarksIt() {
        when(signalRepo.findByContentHash(any())).thenReturn(Optional.empty());
        when(signalRepo.save(any())).thenAnswer(inv -> {
            KolThemeSignalEntity e = inv.getArgument(0);
            ReflectionTestUtils.setField(e, "id", 7L);
            return e;
        });
        String raw = "x".repeat(KolSignalIngestionService.RAW_CONTENT_LIMIT + 10);

        var response = service.create(new KolSignalCreateRequest(
                LocalDate.of(2026, 5, 13), "kol-a", "KOL", "title", raw, null));

        assertThat(response.id()).isEqualTo(7L);
        assertThat(response.rawContentTruncated()).isTrue();
        verify(signalRepo).save(argThat(e ->
                e.getRawContent().length() == KolSignalIngestionService.RAW_CONTENT_LIMIT
                        && e.getPayloadJson().contains("\"rawContentTruncated\":true")));
    }

    private KolThemeSignalEntity signal(String raw) {
        KolThemeSignalEntity entity = new KolThemeSignalEntity();
        entity.setTradingDate(LocalDate.of(2026, 5, 13));
        entity.setSourceKey("pod-a");
        entity.setSourceType("PODCAST");
        entity.setRawContent(raw);
        entity.setContentHash("hash");
        entity.setSignalStatus("RAW");
        return entity;
    }
}
