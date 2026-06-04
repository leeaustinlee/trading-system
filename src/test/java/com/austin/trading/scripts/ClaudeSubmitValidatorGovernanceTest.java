package com.austin.trading.scripts;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ClaudeSubmitValidatorGovernanceTest {

    @TempDir Path tempDir;

    @Test
    void validatorFailsWhenGovernanceSectionsAreMissingForLeadershipAndPeerShadowPayload() throws Exception {
        Path request = tempDir.resolve("request.json");
        Path submit = tempDir.resolve("submit.json");
        Files.writeString(request, """
                {
                  "taskId": 301,
                  "taskType": "POSTMARKET",
                  "trading_date": "2026-05-23",
                  "allowed_symbols": ["2458"],
                  "leadership_symbols": ["2327"],
                  "peer_shadow_candidates": [{"symbol":"2492","role":"SECOND_LEADER","tradable":false}],
                  "theme_governance_trace": {"requires_leadership_analysis": true, "requires_peer_shadow_analysis": true},
                  "prompt_governance_contract": {"mandatory_sections": ["leadership_analysis", "divergence_analysis", "taxonomy_gap_analysis", "peer_shadow_analysis"]}
                }
                """);
        Files.writeString(submit, """
                {
                  "taskId": 301,
                  "taskType": "POSTMARKET",
                  "tradingDate": "2026-05-23",
                  "scores": {"2458": 6.8},
                  "thesis": {"2458": "formal candidate only"}
                }
                """);

        ProcessResult result = runValidator(request, submit);

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.output()).contains("GOVERNANCE_INCOMPLETE", "leadership_analysis", "peer_shadow_analysis");
    }

    @Test
    void validatorFailsWhenFinalEnterCandidateExpandsOutsideAllowedUniverse() throws Exception {
        Path request = tempDir.resolve("request.json");
        Path submit = tempDir.resolve("submit.json");
        Files.writeString(request, """
                {
                  "taskId": 302,
                  "taskType": "POSTMARKET",
                  "trading_date": "2026-05-23",
                  "allowed_symbols": ["2458"],
                  "must_not_expand_allowed_symbols": true
                }
                """);
        Files.writeString(submit, """
                {
                  "taskId": 302,
                  "taskType": "POSTMARKET",
                  "tradingDate": "2026-05-23",
                  "scores": {"2458": 6.8},
                  "thesis": {"2458": "formal candidate"},
                  "final_enter_candidates": ["2492"]
                }
                """);

        ProcessResult result = runValidator(request, submit);

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.output()).contains("CLAUDE_LOCAL_SYMBOL_MISMATCH", "final_enter_candidates", "2492");
    }

    @Test
    void validatorFailsWhenLeadershipOnlySymbolsEnterScoresEvenIfResearchScopeAllowsThem() throws Exception {
        Path request = tempDir.resolve("request.json");
        Path submit = tempDir.resolve("submit.json");
        Files.writeString(request, """
                {
                  "taskId": 225,
                  "taskType": "T86_TOMORROW",
                  "trading_date": "2026-05-25",
                  "candidates": ["6770", "2485", "3231", "3702", "3062"],
                  "tradable_candidate_symbols": ["6770", "2485", "3231", "3702", "3062"],
                  "scoring_allowed_symbols": ["6770", "2485", "3231", "3702", "3062"],
                  "leadership_symbols": ["2327", "4958", "3711", "2344", "3481"],
                  "research_scope_symbols": ["6770", "2485", "3231", "3702", "3062", "2327", "4958", "3711", "2344", "3481"],
                  "allowed_symbols": ["6770", "2485", "3231", "3702", "3062", "2327", "4958", "3711", "2344", "3481"],
                  "prompt_governance_contract": {"governance_required": true, "mandatory_sections": ["leadership_analysis", "divergence_analysis", "taxonomy_gap_analysis", "peer_shadow_analysis"]}
                }
                """);
        Files.writeString(submit, """
                {
                  "taskId": 225,
                  "taskType": "T86_TOMORROW",
                  "tradingDate": "2026-05-25",
                  "scores": {"6770": 6.0, "2327": 6.0},
                  "thesis": {"6770": "formal candidate", "2327": "leadership only"},
                  "leadership_analysis": {"summary": "ok"},
                  "divergence_analysis": {"summary": "ok"},
                  "taxonomy_gap_analysis": {"summary": "ok"},
                  "peer_shadow_analysis": {"summary": "ok"}
                }
                """);

        ProcessResult result = runValidator(request, submit);

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.output()).contains("CLAUDE_LOCAL_SYMBOL_MISMATCH", "invalid scores = ['2327']", "invalid thesis = ['2327']");
    }

    private ProcessResult runValidator(Path request, Path submit) throws Exception {
        Process process = new ProcessBuilder("python3", "scripts/validate-claude-submit.py",
                "--request", request.toString(), "--submit", submit.toString())
                .directory(Path.of("/mnt/d/ai/stock/trading-system").toFile())
                .redirectErrorStream(true)
                .start();
        boolean exited = process.waitFor(30, TimeUnit.SECONDS);
        assertThat(exited).isTrue();
        return new ProcessResult(process.exitValue(), new String(process.getInputStream().readAllBytes()));
    }

    private record ProcessResult(int exitCode, String output) {}
}
