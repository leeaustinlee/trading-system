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

    // ── 阻擋原因 Top 2 (穩定化) ──────────────────────────────────────────

    @Test
    void buildFinalDecision_waitShouldShowTopTwoBlockReasons() {
        FinalDecisionResponse response = new FinalDecisionResponse(
                "WAIT",
                List.of(),
                // 故意亂序 + 多個原因，挑選器要把 hard gate / Codex 排到前面
                List.of(
                        "PRICE_GATE_WAIT_CONFIRMATION",
                        "RR 1.8 < 2.5",
                        "CODEX_NOT_READY",
                        "VETO_THEME_WEAK"
                ),
                "暫不進場，等候 09:30 後再確認。"
        );

        String message = LineMessageBuilder.buildFinalDecision(response, LocalDate.of(2026, 4, 26));

        assertThat(message).contains("🚫 主要阻擋原因");
        assertThat(message).contains("1. CODEX_NOT_READY");
        // 第二優先：VETO（priority=3）優於 PRICE_GATE（priority=4）
        assertThat(message).contains("2. VETO_THEME_WEAK");
        // 確認只列 2 條（rejected reasons 區塊另列 ≤3 條，不算這個 block）
        assertThat(message).doesNotContain("3. PRICE_GATE_WAIT_CONFIRMATION");
    }

    @Test
    void buildFinalDecision_restShouldShowTopTwoBlockReasons() {
        FinalDecisionResponse response = new FinalDecisionResponse(
                "REST",
                List.of(),
                List.of(
                        "AI_NOT_READY",
                        "REGIME_BLOCKED: PANIC_VOLATILITY"
                ),
                "AI 未完成 + regime panic，今日休息。"
        );

        String message = LineMessageBuilder.buildFinalDecision(response, LocalDate.of(2026, 4, 26));

        assertThat(message).contains("🚫 主要阻擋原因");
        // hard gate 永遠優先於 AI_NOT_READY
        assertThat(message).contains("1. REGIME_BLOCKED: PANIC_VOLATILITY");
        assertThat(message).contains("2. AI_NOT_READY");
    }

    @Test
    void pickTopBlockReasons_emptyOrNull_returnsEmpty() {
        assertThat(LineMessageBuilder.pickTopBlockReasons(null, 2)).isEmpty();
        assertThat(LineMessageBuilder.pickTopBlockReasons(List.of(), 2)).isEmpty();
        assertThat(LineMessageBuilder.pickTopBlockReasons(List.of("X", "Y"), 0)).isEmpty();
    }

    @Test
    void pickTopBlockReasons_dedupesIdenticalReasonsKeepingHighestPriority() {
        // 同一字串多次出現只算一次；保留最高優先序（最小 priority 數字）
        java.util.List<String> picks = LineMessageBuilder.pickTopBlockReasons(
                List.of("CODEX_MISSING", "PRICE_GATE_WAIT", "CODEX_MISSING"), 2);
        assertThat(picks).hasSize(2);
        assertThat(picks.get(0)).isEqualTo("CODEX_MISSING");
        assertThat(picks.get(1)).isEqualTo("PRICE_GATE_WAIT");
    }
}
