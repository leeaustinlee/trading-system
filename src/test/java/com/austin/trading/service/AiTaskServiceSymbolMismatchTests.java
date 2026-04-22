package com.austin.trading.service;

import com.austin.trading.dto.request.AiTaskCandidateRef;
import com.austin.trading.dto.request.ClaudeSubmitRequest;
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

    /** 測試間避免 UPSERT 衝突，每次用不同 taskType */
    private String uniqueType(String base) {
        return base + "_" + System.nanoTime();
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
}
