package com.austin.trading.service;

import com.austin.trading.entity.CandidateStockEntity;
import com.austin.trading.entity.StockThemeMappingEntity;
import com.austin.trading.entity.ThemeLeaderRetentionEntity;
import com.austin.trading.entity.ThemePeerShadowCandidateEntity;
import com.austin.trading.repository.CandidateStockRepository;
import com.austin.trading.repository.StockThemeMappingRepository;
import com.austin.trading.repository.ThemePeerShadowCandidateRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ThemePeerDiscoveryServiceTest {

    @Test
    void discoversYageoPeerShadowUniverseFromThemeMappingsWithoutMakingPeersTradable() {
        ThemePeerShadowCandidateRepository shadowRepo = mock(ThemePeerShadowCandidateRepository.class);
        StockThemeMappingRepository mappingRepo = mock(StockThemeMappingRepository.class);
        CandidateStockRepository candidateRepo = mock(CandidateStockRepository.class);
        ThemePeerDiscoveryService service = new ThemePeerDiscoveryService(shadowRepo, mappingRepo, candidateRepo, new ObjectMapper());
        LocalDate date = LocalDate.of(2026, 5, 23);

        ThemeLeaderRetentionEntity leader = leader(date, "2327", "國巨", "MLCC");
        when(mappingRepo.findByThemeTagAndIsActiveTrue("MLCC")).thenReturn(List.of(
                mapping("2327", "國巨", "MLCC", "被動元件", null),
                mapping("2492", "華新科", "MLCC", "被動元件", "SECOND_LEADER"),
                mapping("3026", "禾伸堂", "MLCC", "被動元件", "LOW_BASE_FOLLOWER"),
                mapping("3090", "日電貿", "MLCC", "通路", "CHANNEL_DISTRIBUTOR"),
                mapping("6173", "信昌電", "MLCC", "被動元件", "LOW_BASE_FOLLOWER"),
                mapping("2375", "凱美", "MLCC", "被動元件", "SENTIMENT_STOCK")
        ));
        when(candidateRepo.findByTradingDateAndSymbol(date, "2492")).thenReturn(Optional.of(candidate(date, "2492", "華新科", "MLCC", "9.3", "{\"volumeExpansion\":true,\"continuation\":true}")));
        when(candidateRepo.findByTradingDateAndSymbol(date, "3026")).thenReturn(Optional.of(candidate(date, "3026", "禾伸堂", "MLCC", "7.6", "{}")));
        when(candidateRepo.findByTradingDateAndSymbol(date, "3090")).thenReturn(Optional.of(candidate(date, "3090", "日電貿", "MLCC", "7.9", "{}")));
        when(candidateRepo.findByTradingDateAndSymbol(date, "6173")).thenReturn(Optional.of(candidate(date, "6173", "信昌電", "MLCC", "7.2", "{}")));
        when(candidateRepo.findByTradingDateAndSymbol(date, "2375")).thenReturn(Optional.of(candidate(date, "2375", "凱美", "MLCC", "8.2", "{\"limitUp\":true}")));
        when(shadowRepo.findByTradingDateAndSourcePhaseAndLeaderSymbolAndSymbol(any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(shadowRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        List<ThemePeerShadowCandidateEntity> peers = service.discoverAndSave(date, "POSTMARKET", List.of(leader));

        assertThat(peers).extracting(ThemePeerShadowCandidateEntity::getSymbol)
                .contains("2327", "2492", "3026", "3090", "6173", "2375");
        assertThat(peers).filteredOn(p -> !"2327".equals(p.getSymbol()))
                .allSatisfy(p -> {
                    assertThat(p.getTradable()).isFalse();
                    assertThat(p.getRejectionReason()).contains("SHADOW_ONLY");
                });
        assertThat(peers).filteredOn(p -> "3090".equals(p.getSymbol()))
                .singleElement().extracting(ThemePeerShadowCandidateEntity::getCandidateRole)
                .isEqualTo("CHANNEL_DISTRIBUTOR");
        assertThat(peers).filteredOn(p -> "2492".equals(p.getSymbol()))
                .singleElement().extracting(ThemePeerShadowCandidateEntity::getCandidateRole)
                .isEqualTo("SECOND_LEADER");
    }

    private ThemeLeaderRetentionEntity leader(LocalDate date, String symbol, String name, String theme) {
        ThemeLeaderRetentionEntity entity = new ThemeLeaderRetentionEntity();
        entity.setTradingDate(date);
        entity.setSourcePhase("POSTMARKET");
        entity.setTargetPhase("PREMARKET");
        entity.setSymbol(symbol);
        entity.setStockName(name);
        entity.setThemeTag(theme);
        entity.setLeaderRank(1);
        entity.setScore(new BigDecimal("9.8"));
        entity.setLeaderTradable(false);
        entity.setRetentionReason("test");
        entity.setUseFor("PEER_DISCOVERY");
        entity.setActive(true);
        return entity;
    }

    private StockThemeMappingEntity mapping(String symbol, String name, String theme, String subTheme, String source) {
        StockThemeMappingEntity entity = new StockThemeMappingEntity();
        entity.setSymbol(symbol);
        entity.setStockName(name);
        entity.setThemeTag(theme);
        entity.setSubTheme(subTheme);
        entity.setSource(source);
        entity.setIsActive(true);
        return entity;
    }

    private CandidateStockEntity candidate(LocalDate date, String symbol, String name, String theme, String score, String payload) {
        CandidateStockEntity entity = new CandidateStockEntity();
        entity.setTradingDate(date);
        entity.setSymbol(symbol);
        entity.setStockName(name);
        entity.setThemeTag(theme);
        entity.setScore(new BigDecimal(score));
        entity.setPayloadJson(payload);
        return entity;
    }
}
