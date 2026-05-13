package com.austin.trading.service;

import com.austin.trading.engine.KolThemeSignalEngine;
import com.austin.trading.entity.KolThemeSignalDailySnapshotEntity;
import com.austin.trading.entity.KolThemeSignalEntity;
import com.austin.trading.entity.ThemeSignalEvidenceEntity;
import com.austin.trading.repository.KolSourceProfileRepository;
import com.austin.trading.repository.KolThemeSignalDailySnapshotRepository;
import com.austin.trading.repository.KolThemeSignalRepository;
import com.austin.trading.repository.ThemeSignalEvidenceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class KolSignalAggregationServiceTests {

    private ThemeSignalEvidenceRepository evidenceRepo;
    private KolThemeSignalRepository signalRepo;
    private KolSourceProfileRepository sourceProfileRepo;
    private KolThemeSignalDailySnapshotRepository snapshotRepo;
    private KolSignalTraceService traceService;
    private KolSignalAggregationService service;

    @BeforeEach
    void setUp() {
        evidenceRepo = mock(ThemeSignalEvidenceRepository.class);
        signalRepo = mock(KolThemeSignalRepository.class);
        sourceProfileRepo = mock(KolSourceProfileRepository.class);
        snapshotRepo = mock(KolThemeSignalDailySnapshotRepository.class);
        traceService = mock(KolSignalTraceService.class);
        service = new KolSignalAggregationService(new KolThemeSignalEngine(), evidenceRepo, signalRepo,
                sourceProfileRepo, snapshotRepo, traceService, new ObjectMapper());
    }

    @Test
    void rebuildDailySnapshot_createsSnapshot() {
        LocalDate date = LocalDate.of(2026, 5, 13);
        KolThemeSignalEntity signal = new KolThemeSignalEntity();
        ReflectionTestUtils.setField(signal, "id", 1L);
        signal.setSourceKey("kol-a");
        signal.setTradingDate(date);

        ThemeSignalEvidenceEntity evidence = new ThemeSignalEvidenceEntity();
        evidence.setSignalId(1L);
        evidence.setTradingDate(date);
        evidence.setThemeTag("AI伺服器");
        evidence.setDirection("POSITIVE");
        evidence.setEvidenceType("DIRECT_CLAIM");
        evidence.setConfidence(BigDecimal.ONE);

        when(evidenceRepo.findByTradingDate(date)).thenReturn(List.of(evidence));
        when(snapshotRepo.deleteByTradingDate(date)).thenReturn(1L);
        when(signalRepo.findByTradingDateOrderByCreatedAtDesc(date)).thenReturn(List.of(signal));
        when(sourceProfileRepo.findAll()).thenReturn(List.of());
        when(snapshotRepo.save(any())).thenAnswer(inv -> {
            KolThemeSignalDailySnapshotEntity s = inv.getArgument(0);
            ReflectionTestUtils.setField(s, "id", 99L);
            return s;
        });

        var snapshots = service.rebuildDailySnapshot(date);

        assertThat(snapshots).hasSize(1);
        assertThat(snapshots.get(0).themeTag()).isEqualTo("AI伺服器");
        assertThat(snapshots.get(0).netShadowBoost()).isEqualByComparingTo("0.2000");
        verify(snapshotRepo).deleteByTradingDate(date);
        verify(snapshotRepo).save(argThat(s ->
                s.getTradingDate().equals(date)
                        && s.getThemeTag().equals("AI伺服器")
                        && s.getSourceCount() == 1
                        && s.getEvidenceCount() == 1));
        verify(traceService).write(isNull(), eq("AGGREGATION"), eq("DAILY_REBUILT"),
                argThat(detail -> detail.get("date").equals("2026-05-13")
                        && detail.get("aggregationVersion").equals("mvp-rebuild-v1")
                        && detail.get("snapshotCount").equals(1)
                        && detail.get("deletedSnapshotCount").equals(1L)
                        && Boolean.TRUE.equals(detail.get("weakSignalOnly"))));
    }

    @Test
    void rebuildDailySnapshot_clearsSnapshotsWhenEvidenceGone() {
        LocalDate date = LocalDate.of(2026, 5, 13);
        when(evidenceRepo.findByTradingDate(date)).thenReturn(List.of());
        when(snapshotRepo.deleteByTradingDate(date)).thenReturn(2L);
        when(signalRepo.findByTradingDateOrderByCreatedAtDesc(date)).thenReturn(List.of());
        when(sourceProfileRepo.findAll()).thenReturn(List.of());

        var snapshots = service.rebuildDailySnapshot(date);

        assertThat(snapshots).isEmpty();
        verify(snapshotRepo).deleteByTradingDate(date);
        verify(snapshotRepo, never()).save(any());
        verify(traceService).write(isNull(), eq("AGGREGATION"), eq("DAILY_REBUILT"),
                argThat(detail -> detail.get("snapshotCount").equals(0)
                        && detail.get("deletedSnapshotCount").equals(2L)));
    }
}
