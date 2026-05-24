package com.austin.trading.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.math.BigDecimal;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

class CandidateThemeFirstReplayFixtureTest {

    private static final String FIXTURE = "replay/theme-first/passive-components-2026-05-22.json";
    private static final Set<String> REQUIRED_SYMBOLS = Set.of("2327", "2492", "3026", "3090", "6173", "2375");
    private static final Set<String> FORMAL_TRADABLE_ROLES = Set.of(
            "TRADABLE_PULLBACK", "BREAKOUT_CANDIDATE", "LOW_BASE_FOLLOWER", "SECOND_LEADER"
    );

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void passiveComponentsFixture_parsesAndKeepsLeaderLeadershipOnly() throws Exception {
        JsonNode root = readFixture();
        JsonNode candidates = root.path("candidates");

        assertThat(root.path("tradingDate").asText()).isEqualTo("2026-05-22");
        assertThat(root.path("themeTag").asText()).isEqualTo("被動元件");
        assertThat(root.path("safetyNote").asText()).contains("不得").contains("risk gate");
        assertThat(candidates.isArray()).isTrue();
        assertThat(candidates.size()).isLessThanOrEqualTo(10);

        Set<String> symbols = StreamSupport.stream(candidates.spliterator(), false)
                .map(c -> c.path("symbol").asText())
                .collect(Collectors.toSet());
        assertThat(symbols).containsAll(REQUIRED_SYMBOLS);

        JsonNode leader = bySymbol(candidates, "2327");
        assertThat(leader.path("stockName").asText()).isEqualTo("國巨");
        assertThat(leader.path("candidateRole").asText()).isEqualTo("THEME_LEADER");
        assertThat(leader.path("leadershipOnly").asBoolean()).isTrue();
        assertThat(leader.path("tradable").asBoolean()).isFalse();
        assertThat(leader.path("leaderTradable").asBoolean()).isFalse();
        assertThat(leader.path("isThemeLeader").asBoolean()).isTrue();
        assertThat(leader.path("themeLeaderSymbol").asText()).isEqualTo("2327");
        assertCompleteScores(leader);
        assertThat(leader.path("rejectionReason").asText())
                .contains("leadership-only")
                .contains("not FinalDecision tradable candidate");

        for (String symbol : REQUIRED_SYMBOLS) {
            JsonNode c = bySymbol(candidates, symbol);
            assertCompleteScores(c);
            assertThat(c.path("safetyNote").asText()).isNotBlank();
            assertThat(c.path("rejectionReason").asText()).isNotBlank();
        }
    }

    @Test
    void passiveComponentsPeersAreReplayContextNotFormalTradableCandidates() throws Exception {
        JsonNode candidates = readFixture().path("candidates");

        for (String peerSymbol : Set.of("2492", "3026", "3090", "6173", "2375")) {
            JsonNode peer = bySymbol(candidates, peerSymbol);
            assertThat(peer.path("candidateRole").asText()).isEqualTo("PEER_SHADOW_CONTEXT");
            assertThat(peer.path("tradable").asBoolean()).isFalse();
            assertThat(peer.path("isThemeLeader").asBoolean()).isFalse();
            assertThat(peer.path("themeLeaderSymbol").asText()).isEqualTo("2327");
            assertThat(peer.path("leaderTradable").asBoolean()).isFalse();
            assertThat(FORMAL_TRADABLE_ROLES).doesNotContain(peer.path("candidateRole").asText());
            assertThat(peer.path("rejectionReason").asText()).contains("shadow/replay context");
        }
    }

    private JsonNode readFixture() throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(FIXTURE)) {
            assertThat(in).as("fixture " + FIXTURE).isNotNull();
            return objectMapper.readTree(in);
        }
    }

    private JsonNode bySymbol(JsonNode candidates, String symbol) {
        return StreamSupport.stream(candidates.spliterator(), false)
                .filter(c -> symbol.equals(c.path("symbol").asText()))
                .findFirst()
                .orElseThrow();
    }

    private void assertCompleteScores(JsonNode c) {
        assertThat(c.path("themeImportanceScore").decimalValue()).isGreaterThan(BigDecimal.ZERO);
        assertThat(c.path("tradableScore").decimalValue()).isGreaterThan(BigDecimal.ZERO);
        assertThat(c.path("shadowRankScore").decimalValue()).isGreaterThan(BigDecimal.ZERO);
        assertThat(c.path("candidateRole").asText()).isNotBlank();
        assertThat(c.path("themeLeaderSymbol").asText()).isEqualTo("2327");
        assertThat(c.has("isThemeLeader")).isTrue();
        assertThat(c.has("leaderTradable")).isTrue();
    }
}
