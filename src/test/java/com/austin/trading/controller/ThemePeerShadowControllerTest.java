package com.austin.trading.controller;

import com.austin.trading.entity.ThemePeerShadowCandidateEntity;
import com.austin.trading.repository.ThemePeerShadowCandidateRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ThemePeerShadowControllerTest {

    @Test
    void replayIncludesThemeGovernanceTraceForObservabilityOnlyEnforcement() {
        ThemePeerShadowCandidateRepository repository = mock(ThemePeerShadowCandidateRepository.class);
        LocalDate date = LocalDate.of(2026, 5, 23);
        ThemePeerShadowCandidateEntity peer = new ThemePeerShadowCandidateEntity();
        peer.setTradingDate(date);
        peer.setSourcePhase("POSTMARKET");
        peer.setLeaderSymbol("2327");
        peer.setSymbol("2492");
        peer.setStockName("華新科");
        peer.setThemeTag("被動元件/MLCC");
        peer.setCandidateRole("SECOND_LEADER");
        peer.setTradable(false);
        peer.setRejectionReason("SHADOW_ONLY_NOT_TRADABLE_CANDIDATE; must_not_expand_allowed_symbols=true");
        when(repository.findByTradingDateOrderBySourcePhaseAscLeaderSymbolAscShadowRankScoreDesc(date))
                .thenReturn(List.of(peer));

        ThemePeerShadowController.ReplayResponse replay = new ThemePeerShadowController(repository).replay(date);

        assertThat(replay.shadowOnly()).isTrue();
        assertThat(replay.themeGovernanceTrace().hasPeerShadowAnalysis()).isTrue();
        assertThat(replay.themeGovernanceTrace().violatesAllowedUniverseContract()).isFalse();
        assertThat(replay.themeGovernanceTrace().mandatorySubmitSections())
                .contains("leadership_analysis", "divergence_analysis", "taxonomy_gap_analysis", "peer_shadow_analysis");
        assertThat(replay.safetyContract()).contains("observability only");
    }
}
