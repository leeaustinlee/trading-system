package com.austin.trading.service;

import com.austin.trading.dto.request.AiTaskCandidateRef;
import com.austin.trading.dto.request.ClaudeSubmitRequest;
import com.austin.trading.dto.request.CodexResultPayloadRequest;
import com.austin.trading.dto.request.CodexReviewedSymbolRequest;
import com.austin.trading.dto.request.CodexSubmitRequest;
import com.austin.trading.repository.AiResearchLogRepository;
import com.austin.trading.repository.AiTaskRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * v2.5 驗收：Claude submit scores/thesis key 必須是 task candidates 子集。
 */
@SpringBootTest
@ActiveProfiles("integration")
class AiTaskServiceSymbolMismatchTests {

    @Autowired AiTaskService aiTaskService;
    @MockBean StockEvaluationService stockEvaluationService; // 避免副作用
    @MockBean AiResearchLogRepository aiResearchLogRepository;

    private static final LocalDate TODAY = LocalDate.now();
    private static final AtomicInteger TYPE_SEQ = new AtomicInteger();

    /** 測試間避免 UPSERT 衝突，每次用不同但不超過 DB 欄位長度的 taskType */
    private String uniqueType(String base) {
        return base + "_" + TYPE_SEQ.incrementAndGet();
    }

    /** 場景：OPENING 候選 10 檔，submit 含 4 檔非候選 → 必須丟 IllegalArgumentException */
    @Test
    void submit_withSymbolsOutsideCandidates_shouldThrow() {
        var task = aiTaskService.createTask(TODAY, uniqueType("OPENING"), null,
                List.of(
                        new AiTaskCandidateRef("3189", "n1", null, null),
                        new AiTaskCandidateRef("4958", "n2", null, null),
                        new AiTaskCandidateRef("8046", "n3", null, null),
                        new AiTaskCandidateRef("6191", "n4", null, null),
                        new AiTaskCandidateRef("6442", "n5", null, null),
                        new AiTaskCandidateRef("2399", "n6", null, null),
                        new AiTaskCandidateRef("6456", "n7", null, null),
                        new AiTaskCandidateRef("2436", "n8", null, null),
                        new AiTaskCandidateRef("3231", "n9", null, null),
                        new AiTaskCandidateRef("5469", "n10", null, null)
                ),
                "test", null);

        // 送 PREMARKET 殘留 symbols（2303/2476/3042/4938 不在 candidates 內；5469 重疊）
        Map<String, BigDecimal> scores = Map.of(
                "2303", new BigDecimal("8.0"),
                "5469", new BigDecimal("7.0"),
                "2476", new BigDecimal("5.5"),
                "3042", new BigDecimal("4.0"),
                "4938", new BigDecimal("3.5")
        );
        var req = new ClaudeSubmitRequest("md content", scores, Map.of(), List.of());

        assertThatThrownBy(() -> aiTaskService.submitClaudeResult(task.getId(), req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("CLAUDE_SCORES_SYMBOL_MISMATCH:")
                .hasMessageContaining("2303")
                .hasMessageContaining("2476")
                .hasMessageContaining("3042")
                .hasMessageContaining("4938");
    }

    /** 場景：scores 全在 candidates 內 → 通過 */
    @Test
    void submit_withValidSubset_shouldPass() {
        var task = aiTaskService.createTask(TODAY, uniqueType("OPENING_OK"), null,
                List.of(
                        new AiTaskCandidateRef("3189", "n1", null, null),
                        new AiTaskCandidateRef("4958", "n2", null, null),
                        new AiTaskCandidateRef("5469", "n3", null, null)
                ),
                "test", null);
        Map<String, BigDecimal> scores = Map.of(
                "3189", new BigDecimal("7.2"),
                "5469", new BigDecimal("6.5")
        );
        var req = new ClaudeSubmitRequest("md content", scores, Map.of(), List.of());
        var result = aiTaskService.submitClaudeResult(task.getId(), req);
        assertThat(result.task().getStatus()).isEqualTo("CLAUDE_DONE");
    }

    /** 場景：thesis 含非 candidate 也應擋 */
    @Test
    void submit_withInvalidThesisKey_shouldThrow() {
        var task = aiTaskService.createTask(TODAY, uniqueType("OPENING_THESIS"), null,
                List.of(new AiTaskCandidateRef("3189", "n1", null, null)),
                "test", null);
        var req = new ClaudeSubmitRequest(
                "md",
                Map.of("3189", new BigDecimal("7.0")),
                Map.of("3189", "ok", "9999", "bad"),  // 9999 不在
                List.of()
        );
        assertThatThrownBy(() -> aiTaskService.submitClaudeResult(task.getId(), req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("9999");
    }

    /** 場景：Codex scores/veto/payload 含非 candidate symbols → 必須擋在寫入前 */
    @Test
    void submitCodex_withSymbolsOutsideCandidates_shouldThrow() {
        var task = aiTaskService.createTask(TODAY, uniqueType("OPENING_CODEX"), null,
                List.of(
                        new AiTaskCandidateRef("3189", "n1", null, null),
                        new AiTaskCandidateRef("4958", "n2", null, null)
                ),
                "test", null);
        aiTaskService.submitClaudeResult(task.getId(),
                new ClaudeSubmitRequest("md", Map.of("3189", new BigDecimal("7.0")), Map.of(), List.of()));

        var payload = new CodexResultPayloadRequest(
                "OPENING", "OPENING", "09:30", "B", "RANGE", false,
                List.of(new CodexReviewedSymbolRequest("3189", "selected", null, null, null, null, null,
                        null, null, null, null, null, null, null, true,
                        List.of("ok"), List.of(), "WATCH", "CASH")),
                List.of(new CodexReviewedSymbolRequest("9999", "watchlist", null, null, null, null, null,
                        null, null, null, null, null, null, null, true,
                        List.of("bad"), List.of(), "WATCH", "CASH")),
                List.of()
        );
        var req = new CodexSubmitRequest(
                "codex md",
                Map.of("4958", new BigDecimal("6.0"), "8888", new BigDecimal("5.0")),
                List.of("7777"),
                Map.of("9999", "review issue outside candidates"),
                payload
        );

        assertThatThrownBy(() -> aiTaskService.submitCodexResult(task.getId(), req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("CODEX_RESULT_SYMBOL_MISMATCH:")
                .hasMessageContaining("8888")
                .hasMessageContaining("7777")
                .hasMessageContaining("9999");
    }

    /** 場景：Codex 只有 reviewIssues 含非 candidate symbol → 也必須擋 */
    @Test
    void submitCodex_withReviewIssueOutsideCandidates_shouldThrow() {
        var task = aiTaskService.createTask(TODAY, uniqueType("CODEX_REVIEW"), null,
                List.of(new AiTaskCandidateRef("3189", "n1", null, null)),
                "test", null);
        aiTaskService.submitClaudeResult(task.getId(),
                new ClaudeSubmitRequest("md", Map.of("3189", new BigDecimal("7.0")), Map.of(), List.of()));

        var req = new CodexSubmitRequest(
                "codex md",
                Map.of(),
                List.of(),
                Map.of("9999", "review issue outside candidates"),
                null
        );

        assertThatThrownBy(() -> aiTaskService.submitCodexResult(task.getId(), req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("CODEX_RESULT_SYMBOL_MISMATCH:")
                .hasMessageContaining("9999");
    }

    /** 場景：Codex 只有 reviewIssues 但 task universe 為空 → fail closed */
    @Test
    void submitCodex_withEmptyTaskCandidatesAndReviewIssuesOnly_shouldThrow() {
        var task = aiTaskService.createTask(TODAY, uniqueType("CODEX_REV_EMPTY"), null,
                List.of(), "test", null);
        aiTaskService.submitClaudeResult(task.getId(),
                new ClaudeSubmitRequest("md", Map.of(), Map.of(), List.of()));

        var req = new CodexSubmitRequest(
                "codex md",
                Map.of(),
                List.of(),
                Map.of("8888", "review issue without universe"),
                null
        );

        assertThatThrownBy(() -> aiTaskService.submitCodexResult(task.getId(), req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("CODEX_RESULT_SYMBOL_MISMATCH:");
    }

    /** 場景：Codex 帶 symbol 但 task universe 為空 → fail closed，避免 drift 被寫入 */
    @Test
    void submitCodex_withEmptyTaskCandidatesAndSymbols_shouldThrow() {
        var task = aiTaskService.createTask(TODAY, uniqueType("CODEX_EMPTY"), null,
                List.of(), "test", null);
        aiTaskService.submitClaudeResult(task.getId(),
                new ClaudeSubmitRequest("md", Map.of(), Map.of(), List.of()));

        var req = new CodexSubmitRequest(
                "codex md",
                Map.of("8888", new BigDecimal("5.0")),
                List.of(),
                Map.of(),
                null
        );

        assertThatThrownBy(() -> aiTaskService.submitCodexResult(task.getId(), req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("CODEX_RESULT_SYMBOL_MISMATCH:");
    }
}
