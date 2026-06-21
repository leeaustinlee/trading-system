package com.austin.trading.service;

import com.austin.trading.entity.CandidateForwardTrackingEntity;
import com.austin.trading.entity.CandidateStockEntity;
import com.austin.trading.entity.PositionEntity;
import com.austin.trading.repository.CandidateForwardTrackingRepository;
import com.austin.trading.repository.CandidateStockRepository;
import com.austin.trading.repository.PositionRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PortfolioRotationShadowServiceTest {
    @Test
    void reportBuildsShadowRotationComparisonWithExplicitSafetyFlagsAndNeverMutatesPositions() {
        CandidateStockRepository candidateRepository = Mockito.mock(CandidateStockRepository.class);
        PositionRepository positionRepository = Mockito.mock(PositionRepository.class);
        CandidateForwardTrackingRepository forwardRepository = Mockito.mock(CandidateForwardTrackingRepository.class);
        LocalDate date = LocalDate.of(2026, 6, 20);

        when(candidateRepository.findTopByOrderByTradingDateDesc()).thenReturn(Optional.of(candidate(date, "9999", "NewCo", "AI", "88", "0.80")));
        when(positionRepository.findByStatus("OPEN")).thenReturn(List.of(
                position("1111", "WeakHold"),
                position("2222", "StrongHold")
        ));
        when(candidateRepository.findByTradingDateBetweenOrderByTradingDateDescScoreDesc(any(), any())).thenReturn(List.of(
                candidate(date, "9999", "NewCo", "AI", "88", "0.80"),
                candidate(date, "1111", "WeakHold", "OLD", "70", "0.20"),
                candidate(date, "2222", "StrongHold", "OLD", "82", "0.30")
        ));
        when(forwardRepository.findByTradingDateBetween(any(), any())).thenReturn(List.of(
                forward(date, "9999", "12"),
                forward(date, "1111", "3")
        ));

        PortfolioRotationShadowService service = new PortfolioRotationShadowService(
                candidateRepository, positionRepository, forwardRepository);

        var response = service.report(60);

        assertThat(response.shadowOnly()).isTrue();
        assertThat(response.advisoryOnly()).isTrue();
        assertThat(response.doesNotAffectBuySell()).isTrue();
        assertThat(response.doesNotMutatePositions()).isTrue();
        assertThat(response.doesNotAffectRiskGate()).isTrue();
        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).candidateSymbol()).isEqualTo("9999");
        assertThat(response.items().get(0).weakestHoldingSymbol()).isEqualTo("1111");
        assertThat(response.items().get(0).newCandidateScore()).isEqualByComparingTo(new BigDecimal("88"));
        assertThat(response.items().get(0).weakestHoldingScore()).isEqualByComparingTo(new BigDecimal("70"));
        assertThat(response.items().get(0).lifecycleDifferential()).isEqualByComparingTo(new BigDecimal("0.60"));
        assertThat(response.items().get(0).opportunityDelta()).isEqualByComparingTo(new BigDecimal("9"));
        assertThat(response.items().get(0).opportunityDeltaDataGapped()).isFalse();
        assertThat(response.items().get(0).shadowAction()).isEqualTo("SHADOW_ROTATE");
        assertThat(response.items().get(0).reason()).contains("SHADOW_ONLY", "doesNotMutatePositions=true");
        verify(positionRepository, never()).save(any(PositionEntity.class));
    }

    @Test
    void safetyWordingUsesShadowActionsOnlyAndDoesNotCallSaveOnRepositories() {
        CandidateStockRepository candidateRepository = Mockito.mock(CandidateStockRepository.class);
        PositionRepository positionRepository = Mockito.mock(PositionRepository.class);
        CandidateForwardTrackingRepository forwardRepository = Mockito.mock(CandidateForwardTrackingRepository.class);
        LocalDate date = LocalDate.of(2026, 6, 20);
        when(candidateRepository.findTopByOrderByTradingDateDesc()).thenReturn(Optional.of(candidate(date, "9999", "NewCo", "AI", "75", "0.10")));
        when(positionRepository.findByStatus("OPEN")).thenReturn(List.of(position("1111", "WeakHold")));
        when(candidateRepository.findByTradingDateBetweenOrderByTradingDateDescScoreDesc(any(), any())).thenReturn(List.of(
                candidate(date, "9999", "NewCo", "AI", "75", "0.10"),
                candidate(date, "1111", "WeakHold", "OLD", "70", "0.10")
        ));
        when(forwardRepository.findByTradingDateBetween(any(), any())).thenReturn(List.of());

        PortfolioRotationShadowService service = new PortfolioRotationShadowService(
                candidateRepository, positionRepository, forwardRepository);

        var response = service.report(60);
        String text = response.toString();

        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).shadowAction()).isEqualTo("SHADOW_REDUCE");
        assertThat(text).contains("SHADOW_REDUCE");
        assertThat(text).doesNotContain("\"BUY\"").doesNotContain("\"SELL\"")
                .doesNotContain("action=BUY").doesNotContain("action=SELL");
        assertThat(response.items().get(0).opportunityDeltaDataGapped()).isTrue();
        verify(positionRepository, never()).save(any(PositionEntity.class));
        verify(candidateRepository, never()).save(any(CandidateStockEntity.class));
        verify(forwardRepository, never()).save(any(CandidateForwardTrackingEntity.class));
    }

    private static CandidateStockEntity candidate(LocalDate date, String symbol, String name, String theme, String score, String lifecycle) {
        CandidateStockEntity e = new CandidateStockEntity();
        e.setTradingDate(date);
        e.setSymbol(symbol);
        e.setStockName(name);
        e.setThemeTag(theme);
        e.setScore(new BigDecimal(score));
        e.setThemeImportanceScore(new BigDecimal(lifecycle));
        return e;
    }

    private static PositionEntity position(String symbol, String name) {
        PositionEntity e = new PositionEntity();
        e.setSymbol(symbol);
        e.setStockName(name);
        e.setStatus("OPEN");
        return e;
    }

    private static CandidateForwardTrackingEntity forward(LocalDate date, String symbol, String t10) {
        CandidateForwardTrackingEntity e = new CandidateForwardTrackingEntity();
        e.setTradingDate(date);
        e.setStockId(symbol);
        e.setT10CloseReturnPct(new BigDecimal(t10));
        return e;
    }
}
