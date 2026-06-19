package com.austin.trading.service;

import com.austin.trading.engine.ThemeDrivenAdmissionEngine;
import com.austin.trading.entity.CandidateStockEntity;
import com.austin.trading.entity.HotGroupStockSignalEntity;
import com.austin.trading.entity.WatchlistStockEntity;
import com.austin.trading.repository.CandidateStockRepository;
import com.austin.trading.repository.HotGroupStockSignalRepository;
import com.austin.trading.repository.WatchlistStockRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ThemeAdmissionWriteServiceTest {

    private final HotGroupStockSignalRepository signalRepository = mock(HotGroupStockSignalRepository.class);
    private final CandidateStockRepository candidateRepository = mock(CandidateStockRepository.class);
    private final WatchlistStockRepository watchlistRepository = mock(WatchlistStockRepository.class);
    private final ThemeAdmissionWriteService service = new ThemeAdmissionWriteService(
            signalRepository, candidateRepository, watchlistRepository, new ThemeDrivenAdmissionEngine(), new ObjectMapper());

    @Test
    void admitCandidateWritesCandidateOnly() {
        LocalDate date = LocalDate.of(2026, 6, 18);
        HotGroupStockSignalEntity leader = signal(1L, date, "1111", "THEME_LEADER", false);
        when(signalRepository.findByTradingDateOrderByRadarRankScoreDesc(date)).thenReturn(List.of(leader));
        when(candidateRepository.findByTradingDateAndSymbol(date, "1111")).thenReturn(Optional.empty());
        when(watchlistRepository.findBySymbol("1111")).thenReturn(Optional.empty());

        var summary = service.rebuildForDate(date);

        assertThat(summary.admittedCandidates()).isEqualTo(1);
        assertThat(summary.admittedWatchlists()).isZero();
        ArgumentCaptor<CandidateStockEntity> captor = ArgumentCaptor.forClass(CandidateStockEntity.class);
        verify(candidateRepository).save(captor.capture());
        verify(watchlistRepository, never()).save(any());
        CandidateStockEntity saved = captor.getValue();
        assertThat(saved.getSymbol()).isEqualTo("1111");
        assertThat(saved.getPayloadJson()).contains("THEME_GUARANTEED", "source_signal_id", "THEME_ADMISSION", "TRADE_CANDIDATE");
    }

    @Test
    void admitWatchlistWritesWatchlistOnly() {
        LocalDate date = LocalDate.of(2026, 6, 18);
        HotGroupStockSignalEntity second = signal(2L, date, "2222", "SECOND_LEADER", false);
        when(signalRepository.findByTradingDateOrderByRadarRankScoreDesc(date)).thenReturn(List.of(second));
        when(candidateRepository.findByTradingDateAndSymbol(date, "2222")).thenReturn(Optional.empty());
        when(watchlistRepository.findBySymbol("2222")).thenReturn(Optional.empty());

        var summary = service.rebuildForDate(date);

        assertThat(summary.admittedCandidates()).isZero();
        assertThat(summary.admittedWatchlists()).isEqualTo(1);
        ArgumentCaptor<WatchlistStockEntity> captor = ArgumentCaptor.forClass(WatchlistStockEntity.class);
        verify(watchlistRepository).save(captor.capture());
        verify(candidateRepository, never()).save(any());
        WatchlistStockEntity saved = captor.getValue();
        assertThat(saved.getSymbol()).isEqualTo("2222");
        assertThat(saved.getPayloadJson()).contains("THEME_SECOND_LEADER", "source_signal_id", "THEME_ADMISSION", "WATCH_ONLY");
    }

    @Test
    void strongMoverWouldAdmitWatchlistInProductionWritePath() {
        LocalDate date = LocalDate.of(2026, 6, 18);
        HotGroupStockSignalEntity strongFollower = signal(8L, date, "8888", "LOW_BASE_FOLLOWER", false);
        strongFollower.setChangePct(new BigDecimal("7.5"));
        when(signalRepository.findByTradingDateOrderByRadarRankScoreDesc(date)).thenReturn(List.of(strongFollower));
        when(candidateRepository.findByTradingDateAndSymbol(date, "8888")).thenReturn(Optional.empty());
        when(watchlistRepository.findBySymbol("8888")).thenReturn(Optional.empty());

        var summary = service.rebuildForDate(date);

        assertThat(summary.admittedCandidates()).isZero();
        assertThat(summary.admittedWatchlists()).isEqualTo(1);
        verify(watchlistRepository).save(any(WatchlistStockEntity.class));
        verify(candidateRepository, never()).save(any());
    }

    @Test
    void limitRiskDoesNotWriteAndCountsSkippedLimitRisk() {
        LocalDate date = LocalDate.of(2026, 6, 18);
        HotGroupStockSignalEntity leader = signal(3L, date, "3333", "THEME_LEADER", true);
        when(signalRepository.findByTradingDateOrderByRadarRankScoreDesc(date)).thenReturn(List.of(leader));
        when(candidateRepository.findByTradingDateAndSymbol(date, "3333")).thenReturn(Optional.empty());
        when(watchlistRepository.findBySymbol("3333")).thenReturn(Optional.empty());

        var summary = service.rebuildForDate(date);

        assertThat(summary.skippedLimitRisk()).isEqualTo(1);
        verify(candidateRepository, never()).save(any());
        verify(watchlistRepository, never()).save(any());
    }

    @Test
    void existingCandidateDoesNotOverwriteAndCountsAlreadyExists() {
        LocalDate date = LocalDate.of(2026, 6, 18);
        HotGroupStockSignalEntity leader = signal(4L, date, "4444", "THEME_LEADER", false);
        when(signalRepository.findByTradingDateOrderByRadarRankScoreDesc(date)).thenReturn(List.of(leader));
        when(candidateRepository.findByTradingDateAndSymbol(date, "4444")).thenReturn(Optional.of(new CandidateStockEntity()));
        when(watchlistRepository.findBySymbol("4444")).thenReturn(Optional.empty());

        var summary = service.rebuildForDate(date);

        assertThat(summary.skippedAlreadyExists()).isEqualTo(1);
        verify(candidateRepository, never()).save(any());
        verify(watchlistRepository, never()).save(any());
    }

    @Test
    void existingWatchlistDoesNotOverwriteAndCountsAlreadyExists() {
        LocalDate date = LocalDate.of(2026, 6, 18);
        HotGroupStockSignalEntity second = signal(5L, date, "5555", "SECOND_LEADER", false);
        when(signalRepository.findByTradingDateOrderByRadarRankScoreDesc(date)).thenReturn(List.of(second));
        when(candidateRepository.findByTradingDateAndSymbol(date, "5555")).thenReturn(Optional.empty());
        when(watchlistRepository.findBySymbol("5555")).thenReturn(Optional.of(new WatchlistStockEntity()));

        var summary = service.rebuildForDate(date);

        assertThat(summary.skippedAlreadyExists()).isEqualTo(1);
        verify(candidateRepository, never()).save(any());
        verify(watchlistRepository, never()).save(any());
    }

    @Test
    void badDataAndLiquidityRejectWithoutWrites() {
        LocalDate date = LocalDate.of(2026, 6, 18);
        HotGroupStockSignalEntity noPrice = signal(6L, date, "6666", "THEME_LEADER", false);
        noPrice.setChangePct(null);
        HotGroupStockSignalEntity noLiquidity = signal(7L, date, "7777", "THEME_LEADER", false);
        noLiquidity.setTurnoverYi(BigDecimal.ZERO);
        when(signalRepository.findByTradingDateOrderByRadarRankScoreDesc(date)).thenReturn(List.of(noPrice, noLiquidity));
        when(candidateRepository.findByTradingDateAndSymbol(any(), any())).thenReturn(Optional.empty());
        when(watchlistRepository.findBySymbol(any())).thenReturn(Optional.empty());

        var summary = service.rebuildForDate(date);

        assertThat(summary.rejectedBadData()).isEqualTo(1);
        assertThat(summary.rejectedLiquidity()).isEqualTo(1);
        verify(candidateRepository, never()).save(any());
        verify(watchlistRepository, never()).save(any());
    }

    private HotGroupStockSignalEntity signal(Long id, LocalDate date, String symbol, String role, boolean limitRisk) {
        HotGroupStockSignalEntity s = new HotGroupStockSignalEntity();
        ReflectionTestUtils.setField(s, "id", id);
        s.setTradingDate(date);
        s.setSourcePhase("HOT_GROUP_RADAR");
        s.setThemeTag("被動元件/MLCC");
        s.setSymbol(symbol);
        s.setStockName("測試股");
        s.setRole(role);
        s.setChangePct(new BigDecimal("3.1"));
        s.setTurnoverYi(new BigDecimal("1.2"));
        s.setLimitRisk(limitRisk);
        s.setTradabilityTag("TRADE_CANDIDATE");
        s.setRadarRankScore(new BigDecimal("25.0"));
        return s;
    }
}
