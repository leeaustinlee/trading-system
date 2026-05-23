package com.austin.trading.service;

import com.austin.trading.dto.response.CandidateResponse;
import com.austin.trading.entity.ThemeLeaderRetentionEntity;
import com.austin.trading.repository.ThemeLeaderRetentionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ThemeLeaderRetentionServiceTest {

    private final ThemeLeaderRetentionRepository repository = mock(ThemeLeaderRetentionRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ThemeLeaderRetentionService service = new ThemeLeaderRetentionService(repository, objectMapper);

    @Test
    void retainPostmarketSuperStrong_writesReadOnlyLeadersForAllTargetPhases() throws Exception {
        LocalDate sourceDate = LocalDate.of(2026, 5, 23);
        CandidateResponse yageo = candidate("2327", "國巨", "MLCC", "9.8");
        when(repository.findByTradingDateAndTargetPhaseAndSymbol(sourceDate, "T86_TOMORROW", "2327"))
                .thenReturn(java.util.Optional.empty());
        when(repository.findByTradingDateAndTargetPhaseAndSymbol(sourceDate, "PREMARKET", "2327"))
                .thenReturn(java.util.Optional.empty());
        when(repository.findByTradingDateAndTargetPhaseAndSymbol(sourceDate, "OPENING", "2327"))
                .thenReturn(java.util.Optional.empty());

        service.retainPostmarketSuperStrong(sourceDate, List.of(yageo));

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<ThemeLeaderRetentionEntity> cap = org.mockito.ArgumentCaptor.forClass(ThemeLeaderRetentionEntity.class);
        verify(repository, org.mockito.Mockito.times(3)).save(cap.capture());
        assertThat(cap.getAllValues()).extracting(ThemeLeaderRetentionEntity::getTargetPhase)
                .containsExactly("T86_TOMORROW", "PREMARKET", "OPENING");
        assertThat(cap.getAllValues()).allSatisfy(row -> {
            assertThat(row.getSymbol()).isEqualTo("2327");
            assertThat(row.getLeaderTradable()).isFalse();
            assertThat(row.getUseFor()).isEqualTo("MARKET_LEADERSHIP,THEME_VALIDATION,PEER_DISCOVERY");
            assertThat(row.getRetentionReason()).contains("super_strong_5");
        });
        JsonNode payload = objectMapper.readTree(cap.getAllValues().get(0).getPayloadJson());
        assertThat(payload.path("shadowOnly").asBoolean()).isTrue();
        assertThat(payload.path("mustNotAffectFinalDecisionEngine").asBoolean()).isTrue();
    }

    @Test
    void loadForPhase_usesLatestSourceDateOnOrBeforeTargetDate() {
        LocalDate targetDate = LocalDate.of(2026, 5, 24);
        ThemeLeaderRetentionEntity row = new ThemeLeaderRetentionEntity();
        row.setTradingDate(LocalDate.of(2026, 5, 23));
        row.setTargetPhase("PREMARKET");
        row.setSymbol("2327");
        row.setStockName("國巨");
        row.setThemeTag("MLCC");
        row.setLeaderRank(1);
        row.setLeaderTradable(false);
        row.setRetentionReason("POSTMARKET super_strong_5 retained for next-phase leadership validation");
        row.setUseFor("MARKET_LEADERSHIP,THEME_VALIDATION,PEER_DISCOVERY");
        when(repository.findByTargetPhaseAndActiveTrueAndTradingDateLessThanEqualOrderByTradingDateDescLeaderRankAsc(
                "PREMARKET", targetDate)).thenReturn(List.of(row));

        List<ClaudeCodeRequestWriterService.LeaderContext> leaders = service.loadLeaderContexts(targetDate, "PREMARKET");

        assertThat(leaders).hasSize(1);
        assertThat(leaders.get(0).symbol()).isEqualTo("2327");
        assertThat(leaders.get(0).leaderTradable()).isFalse();
        assertThat(leaders.get(0).useFor()).containsExactly("MARKET_LEADERSHIP", "THEME_VALIDATION", "PEER_DISCOVERY");
    }

    private CandidateResponse candidate(String symbol, String name, String theme, String score) {
        BigDecimal s = new BigDecimal(score);
        return new CandidateResponse(
                LocalDate.of(2026, 5, 23), symbol, name, s,
                theme + "；測試", null, null, null, null, null, null, null,
                theme, null, s, null, null, null, null, null, null, null
        );
    }
}
