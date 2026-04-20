package com.austin.trading.notify;

import com.austin.trading.dto.response.FinalDecisionResponse;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LineMessageBuilderTests {

    @Test
    void buildFinalDecision_shouldShowPartialAiReadyForCodexMissing() {
        FinalDecisionResponse response = new FinalDecisionResponse(
                "WATCH",
                List.of(),
                List.of("CODEX_MISSING"),
                "Claude 已完成但 Codex 尚未完成"
        );

        String message = LineMessageBuilder.buildFinalDecision(response, LocalDate.of(2026, 4, 20));

        assertThat(message).contains("AI 狀態：PARTIAL_AI_READY");
        assertThat(message).contains("降級原因：CODEX_MISSING");
    }

    @Test
    void buildFinalDecision_shouldNotShowAiStatusForNormalDecision() {
        FinalDecisionResponse response = new FinalDecisionResponse(
                "ENTER",
                List.of(),
                List.of("追價風險"),
                "可進場"
        );

        String message = LineMessageBuilder.buildFinalDecision(response, LocalDate.of(2026, 4, 20));

        assertThat(message).doesNotContain("AI 狀態：");
        assertThat(message).doesNotContain("降級原因：");
    }
}
