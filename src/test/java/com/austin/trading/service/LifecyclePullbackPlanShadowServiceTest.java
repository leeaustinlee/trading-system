package com.austin.trading.service;

import com.austin.trading.domain.enums.ThemeAdmissionShadowAction;
import com.austin.trading.entity.CandidateForwardTrackingEntity;
import com.austin.trading.entity.ThemeAdmissionShadowDecisionEntity;
import com.austin.trading.entity.ThemeLifecycleStateEntity;
import com.austin.trading.repository.CandidateForwardTrackingRepository;
import com.austin.trading.repository.ThemeAdmissionShadowDecisionRepository;
import com.austin.trading.repository.ThemeLifecycleStateRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LifecyclePullbackPlanShadowServiceTest {
    @Test
    void reportBuildsReadOnlyShadowPullbackPlansAndNeverWrites() {
        ThemeAdmissionShadowDecisionRepository admissionRepository = Mockito.mock(ThemeAdmissionShadowDecisionRepository.class);
        ThemeLifecycleStateRepository lifecycleRepository = Mockito.mock(ThemeLifecycleStateRepository.class);
        CandidateForwardTrackingRepository forwardRepository = Mockito.mock(CandidateForwardTrackingRepository.class);
        LocalDate date = LocalDate.now();

        when(admissionRepository.findByTradingDateBetweenOrderByTradingDateDescThemeTagAscSymbolAsc(any(), any()))
                .thenReturn(List.of(
                        admission(date, "1001", "AI", true, true, "NEAR_LIMIT", "LEADER"),
                        admission(date, "1002", "AI", false, false, null, "FOLLOWER"),
                        admission(date, "2001", "AUTO", true, false, "LIMIT_RISK", "LEADER")
                ));
        when(lifecycleRepository.findAll()).thenReturn(List.of(
                lifecycle(date, "AI", "OVERHEATED", "0.2500", "0.8000"),
                lifecycle(date, "AUTO", "MAINSTREAM", "0.0500", "0.3000")
        ));
        when(forwardRepository.findByTradingDateBetween(any(), any())).thenReturn(List.of(
                forward(date, "1001", "6.0", "9.0", "-4.0"),
                forward(date, "2001", "2.0", "4.0", "-2.0")
        ));
        LifecyclePullbackPlanShadowService service = new LifecyclePullbackPlanShadowService(
                admissionRepository, lifecycleRepository, forwardRepository);

        var response = service.report(60);

        assertThat(response.readOnly()).isTrue();
        assertThat(response.shadowOnly()).isTrue();
        assertThat(response.doesNotAffectBuySell()).isTrue();
        assertThat(response.doesNotWriteCandidateWatchlist()).isTrue();
        assertThat(response.doesNotAffectRanking()).isTrue();
        assertThat(response.totalRows()).isEqualTo(3);
        assertThat(response.rows()).hasSize(2);
        assertThat(response.pullbackPlanRows()).isEqualTo(2);
        assertThat(response.avoidChasingRows()).isEqualTo(1);
        assertThat(response.watchPullbackRows()).isEqualTo(1);
        assertThat(response.rows()).extracting("symbol").containsExactly("1001", "2001");
        assertThat(response.rows().get(0).planStatus()).isEqualTo("AVOID_CHASING");
        assertThat(response.rows().get(0).planReason()).contains("doesNotAffectBuySell=true");
        assertThat(response.averageReturn5d()).isEqualByComparingTo(new BigDecimal("4.0000"));
        assertThat(response.byStatus()).extracting("planStatus").contains("AVOID_CHASING", "WATCH_PULLBACK");
        verify(admissionRepository, never()).save(any());
        verify(forwardRepository, never()).save(any());
    }

    @Test
    void emptyDataReturnsExplicitGap() {
        ThemeAdmissionShadowDecisionRepository admissionRepository = Mockito.mock(ThemeAdmissionShadowDecisionRepository.class);
        ThemeLifecycleStateRepository lifecycleRepository = Mockito.mock(ThemeLifecycleStateRepository.class);
        CandidateForwardTrackingRepository forwardRepository = Mockito.mock(CandidateForwardTrackingRepository.class);
        when(admissionRepository.findByTradingDateBetweenOrderByTradingDateDescThemeTagAscSymbolAsc(any(), any()))
                .thenReturn(List.of());
        when(lifecycleRepository.findAll()).thenReturn(List.of());
        when(forwardRepository.findByTradingDateBetween(any(), any())).thenReturn(List.of());
        LifecyclePullbackPlanShadowService service = new LifecyclePullbackPlanShadowService(
                admissionRepository, lifecycleRepository, forwardRepository);

        var response = service.report(60);

        assertThat(response.rows()).isEmpty();
        assertThat(response.dataGaps()).containsExactly("NO_ROWS_IN_REQUESTED_WINDOW:theme_admission_shadow_decision");
        verify(admissionRepository, never()).save(any());
    }

    @Test
    void planStatusKeepsOverheatedAsAvoidChasingNotBuySignal() {
        LocalDate date = LocalDate.now();
        assertThat(LifecyclePullbackPlanShadowService.planStatus(
                admission(date, "1001", "AI", true, true, "NEAR_LIMIT", "LEADER"),
                lifecycle(date, "AI", "OVERHEATED", "0.2500", "0.8000")))
                .isEqualTo("AVOID_CHASING");
        assertThat(LifecyclePullbackPlanShadowService.planStatus(
                admission(date, "1002", "AI", true, false, "LIMIT_RISK", "LEADER"),
                lifecycle(date, "AI", "MAINSTREAM", "0.0500", "0.2000")))
                .isEqualTo("WATCH_PULLBACK");
    }

    private static ThemeAdmissionShadowDecisionEntity admission(LocalDate date, String symbol, String theme,
                                                               boolean pullback, boolean nearLimit,
                                                               String limitRisk, String role) {
        ThemeAdmissionShadowDecisionEntity e = new ThemeAdmissionShadowDecisionEntity();
        e.setTradingDate(date);
        e.setSymbol(symbol);
        e.setStockName("Stock" + symbol);
        e.setThemeTag(theme);
        e.setSignalRole(role);
        e.setShadowAction(ThemeAdmissionShadowAction.WOULD_CREATE_PULLBACK_PLAN);
        e.setWouldCreatePullbackPlan(pullback);
        e.setWouldWriteCandidate(false);
        e.setWouldWriteWatchlist(false);
        e.setNearLimit(nearLimit);
        e.setLimitRisk(limitRisk);
        e.setTraceStatus("ACTIVE");
        return e;
    }

    private static ThemeLifecycleStateEntity lifecycle(LocalDate date, String theme, String stage,
                                                      String limitDensity, String crowding) {
        ThemeLifecycleStateEntity e = new ThemeLifecycleStateEntity();
        e.setTradingDate(date);
        e.setThemeTag(theme);
        e.setStage(stage);
        e.setLifecycleScore(new BigDecimal("0.7000"));
        e.setContinuationDays(3);
        e.setBreadth(8);
        e.setLeaderCount(2);
        e.setLimitUpDensity(new BigDecimal(limitDensity));
        e.setCrowdingScore(new BigDecimal(crowding));
        return e;
    }

    private static CandidateForwardTrackingEntity forward(LocalDate date, String symbol, String r5, String r10, String dd) {
        CandidateForwardTrackingEntity e = new CandidateForwardTrackingEntity();
        e.setTradingDate(date);
        e.setStockId(symbol);
        e.setT5CloseReturnPct(new BigDecimal(r5));
        e.setT10CloseReturnPct(new BigDecimal(r10));
        e.setMaxDrawdownPct(new BigDecimal(dd));
        return e;
    }
}
