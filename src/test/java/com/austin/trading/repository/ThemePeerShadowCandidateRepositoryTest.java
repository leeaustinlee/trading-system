package com.austin.trading.repository;

import com.austin.trading.entity.ThemePeerShadowCandidateEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("integration")
@Transactional
class ThemePeerShadowCandidateRepositoryTest {

    @Autowired
    ThemePeerShadowCandidateRepository repository;

    @Test
    void savesAndQueriesShadowPeersByDatePhaseAndLeader() {
        LocalDate date = LocalDate.of(2026, 5, 23);
        ThemePeerShadowCandidateEntity peer = new ThemePeerShadowCandidateEntity();
        peer.setTradingDate(date);
        peer.setSourcePhase("POSTMARKET");
        peer.setLeaderSymbol("2327");
        peer.setSymbol("2492");
        peer.setStockName("華新科");
        peer.setThemeTag("MLCC");
        peer.setCandidateRole("SECOND_LEADER");
        peer.setThemeImportanceScore(new BigDecimal("8.50"));
        peer.setTradableScore(new BigDecimal("0.00"));
        peer.setShadowRankScore(new BigDecimal("9.20"));
        peer.setTradable(false);
        peer.setRejectionReason("SHADOW_ONLY_NOT_TRADABLE_CANDIDATE");
        peer.setEvidenceJson("{\"source\":\"test\"}");
        repository.save(peer);
        repository.flush();

        assertThat(repository.findByTradingDateAndSourcePhaseOrderByShadowRankScoreDesc(date, "POSTMARKET"))
                .extracting(ThemePeerShadowCandidateEntity::getSymbol)
                .contains("2492");
        assertThat(repository.findByLeaderSymbolOrderByTradingDateDescShadowRankScoreDesc("2327"))
                .extracting(ThemePeerShadowCandidateEntity::getStockName)
                .contains("華新科");
    }
}
