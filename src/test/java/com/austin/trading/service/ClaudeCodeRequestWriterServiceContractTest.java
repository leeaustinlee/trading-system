package com.austin.trading.service;

import com.austin.trading.config.AiClaudeConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ClaudeCodeRequestWriterServiceContractTest {

    @TempDir
    Path tempDir;

    @Test
    void writeRequest_includesStructuredMarketContextAndTradingDateAliases() throws Exception {
        Path requestPath = tempDir.resolve("claude-research-request.json");
        Path outputPath = tempDir.resolve("claude-research-latest.md");

        AiClaudeConfig config = new AiClaudeConfig();
        config.setRequestOutputPath(requestPath.toString());
        config.setResearchOutputPath(outputPath.toString());

        ObjectMapper mapper = new ObjectMapper();
        ClaudeCodeRequestWriterService service = new ClaudeCodeRequestWriterService(config, mapper);

        String context = """
                {
                  "marketGrade":"B",
                  "marketGradeSource":"JAVA_POSTMARKET_1505",
                  "universeSource":"FRESH_SCAN",
                  "scoringUniverse": {
                    "symbols": ["1216","2458","8926"],
                    "superStrongSymbols": ["1216"],
                    "finalCandidateSymbols": ["2458","8926"]
                  }
                }
                """;

        boolean written = service.writeRequest(
                172L,
                "POSTMARKET",
                LocalDate.of(2026, 5, 11),
                List.of("1216", "2458", "8926"),
                context
        );

        assertThat(written).isTrue();
        JsonNode root = mapper.readTree(Files.readString(requestPath));

        assertThat(root.path("taskId").asLong()).isEqualTo(172L);
        assertThat(root.path("taskType").asText()).isEqualTo("POSTMARKET");
        assertThat(root.path("tradingDate").asText()).isEqualTo("2026-05-11");
        assertThat(root.path("trading_date").asText()).isEqualTo("2026-05-11");
        assertThat(root.path("allowed_symbols")).hasSize(3);
        assertThat(root.path("market_context").asText()).contains("marketGrade");
        assertThat(root.path("market_context_payload").path("marketGrade").asText()).isEqualTo("B");
        assertThat(root.path("market_context_payload").path("scoringUniverse").path("symbols")).hasSize(3);
        assertThat(root.path("market_context_payload").path("universeSource").asText()).isEqualTo("FRESH_SCAN");
        assertThat(root.path("submit_filename_hint").asText()).contains("task-172");
    }

    @Test
    void writeRequest_splitsTradableCandidatesFromReadOnlyLeadershipSymbols() throws Exception {
        Path requestPath = tempDir.resolve("claude-research-request.json");
        Path outputPath = tempDir.resolve("claude-research-latest.md");

        AiClaudeConfig config = new AiClaudeConfig();
        config.setRequestOutputPath(requestPath.toString());
        config.setResearchOutputPath(outputPath.toString());

        ObjectMapper mapper = new ObjectMapper();
        ClaudeCodeRequestWriterService service = new ClaudeCodeRequestWriterService(config, mapper);

        boolean written = service.writeRequest(
                173L,
                "PREMARKET",
                LocalDate.of(2026, 5, 24),
                List.of("2458", "8926"),
                List.of(new ClaudeCodeRequestWriterService.LeaderContext(
                        "2327",
                        "國巨",
                        "MLCC",
                        1,
                        false,
                        "POSTMARKET super_strong_5 retained for next-phase leadership validation",
                        List.of("MARKET_LEADERSHIP", "THEME_VALIDATION", "PEER_DISCOVERY")
                )),
                "{\"source\":\"premarket_data_prep\"}"
        );

        assertThat(written).isTrue();
        JsonNode root = mapper.readTree(Files.readString(requestPath));
        assertThat(root.path("tradable_candidate_symbols")).extracting(JsonNode::asText)
                .containsExactly("2458", "8926");
        assertThat(root.path("leadership_symbols")).extracting(JsonNode::asText)
                .containsExactly("2327");
        assertThat(root.path("allowed_symbols")).extracting(JsonNode::asText)
                .containsExactly("2458", "8926", "2327");
        assertThat(root.path("candidates")).extracting(JsonNode::asText)
                .containsExactly("2458", "8926");
        JsonNode leader = root.path("leadership_context").get(0);
        assertThat(leader.path("symbol").asText()).isEqualTo("2327");
        assertThat(leader.path("leader_tradable").asBoolean()).isFalse();
        assertThat(leader.path("retention_reason").asText()).contains("super_strong_5");
        assertThat(leader.path("use_for")).extracting(JsonNode::asText)
                .containsExactly("MARKET_LEADERSHIP", "THEME_VALIDATION", "PEER_DISCOVERY");
        assertThat(root.path("contract_note").asText()).contains("leadership_symbols").contains("不得視為 ENTER candidate");
    }
}
