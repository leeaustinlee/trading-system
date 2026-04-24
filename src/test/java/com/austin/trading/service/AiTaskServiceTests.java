package com.austin.trading.service;

import com.austin.trading.dto.request.AiScoreUpdateRequest;
import com.austin.trading.dto.request.AiTaskCandidateRef;
import com.austin.trading.dto.request.ClaudeSubmitRequest;
import com.austin.trading.dto.request.CodexSubmitRequest;
import com.austin.trading.entity.AiResearchLogEntity;
import com.austin.trading.entity.AiTaskEntity;
import com.austin.trading.entity.StockEvaluationEntity;
import com.austin.trading.repository.AiResearchLogRepository;
import com.austin.trading.repository.AiTaskRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AiTaskService 單元測試（PR-2）。
 * Mock repositories + StockEvaluationService 模擬佇列流轉與自動回寫。
 */
class AiTaskServiceTests {

    private AiTaskRepository         aiTaskRepository;
    private AiResearchLogRepository  aiResearchLogRepository;
    private StockEvaluationService   stockEvaluationService;
    private ObjectMapper             objectMapper;
    private AiTaskService            service;

    private final Map<Long, AiTaskEntity> store = new HashMap<>();
    private final AtomicLong idSeq = new AtomicLong(0);

    @BeforeEach
    void setUp() {
        aiTaskRepository        = mock(AiTaskRepository.class);
        aiResearchLogRepository = mock(AiResearchLogRepository.class);
        stockEvaluationService  = mock(StockEvaluationService.class);
        objectMapper            = new ObjectMapper();
        store.clear();
        idSeq.set(0);

        when(aiTaskRepository.save(any(AiTaskEntity.class))).thenAnswer(inv -> {
            AiTaskEntity t = inv.getArgument(0);
            if (t.getId() == null) {
                t.setId(idSeq.incrementAndGet());
            }
            store.put(t.getId(), t);
            return t;
        });
        when(aiTaskRepository.findById(any(Long.class))).thenAnswer(inv ->
                Optional.ofNullable(store.get(inv.getArgument(0, Long.class))));

        when(aiTaskRepository.findByTradingDateAndTaskTypeAndTargetSymbolIsNull(
                any(LocalDate.class), anyString())).thenAnswer(inv -> {
            LocalDate date = inv.getArgument(0);
            String type = inv.getArgument(1);
            return store.values().stream()
                    .filter(t -> date.equals(t.getTradingDate())
                            && type.equals(t.getTaskType())
                            && t.getTargetSymbol() == null)
                    .findFirst();
        });
        when(aiTaskRepository.findByTradingDateAndTaskTypeAndTargetSymbol(
                any(LocalDate.class), anyString(), anyString())).thenAnswer(inv -> {
            LocalDate date = inv.getArgument(0);
            String type = inv.getArgument(1);
            String symbol = inv.getArgument(2);
            return store.values().stream()
                    .filter(t -> date.equals(t.getTradingDate())
                            && type.equals(t.getTaskType())
                            && symbol.equals(t.getTargetSymbol()))
                    .findFirst();
        });

        when(aiTaskRepository.findByStatusAndTaskTypeOrderByCreatedAtAsc(anyString(), anyString()))
                .thenAnswer(inv -> {
                    String status = inv.getArgument(0);
                    String type   = inv.getArgument(1);
                    return store.values().stream()
                            .filter(t -> status.equals(t.getStatus()) && type.equals(t.getTaskType()))
                            .sorted((a, b) -> Long.compare(
                                    a.getId() == null ? 0 : a.getId(),
                                    b.getId() == null ? 0 : b.getId()))
                            .toList();
                });
        when(aiTaskRepository.findByStatusOrderByCreatedAtAsc(anyString()))
                .thenAnswer(inv -> {
                    String status = inv.getArgument(0);
                    return store.values().stream()
                            .filter(t -> status.equals(t.getStatus()))
                            .toList();
                });

        when(aiTaskRepository.findByTradingDateAndTaskType(any(LocalDate.class), anyString()))
                .thenAnswer(inv -> {
                    LocalDate date = inv.getArgument(0);
                    String type = inv.getArgument(1);
                    return store.values().stream()
                            .filter(t -> date.equals(t.getTradingDate()) && type.equals(t.getTaskType()))
                            .toList();
                });
        when(aiTaskRepository.findByTradingDateOrderByCreatedAtDesc(any(LocalDate.class)))
                .thenAnswer(inv -> {
                    LocalDate date = inv.getArgument(0);
                    return store.values().stream()
                            .filter(t -> date.equals(t.getTradingDate()))
                            .toList();
                });

        // stockEvaluationService.updateAiScores 回傳一個空 entity 即可
        when(stockEvaluationService.updateAiScores(anyString(), any(AiScoreUpdateRequest.class)))
                .thenAnswer(inv -> {
                    StockEvaluationEntity e = new StockEvaluationEntity();
                    e.setSymbol(inv.getArgument(0));
                    return e;
                });

        service = new AiTaskService(aiTaskRepository, aiResearchLogRepository,
                stockEvaluationService, objectMapper);
    }

    // ── 1. createTask 新建 ─────────────────────────────────────────────────
    @Test
    void createTask_shouldCreateNewPending() {
        LocalDate date = LocalDate.of(2026, 4, 20);
        AiTaskCandidateRef ref = new AiTaskCandidateRef("2303", "聯電", "AI", new BigDecimal("7.5"));

        AiTaskEntity t = service.createTask(date, "PREMARKET", null, List.of(ref), "test", "/tmp/x.json");

        assertThat(t.getId()).isNotNull();
        assertThat(t.getStatus()).isEqualTo(AiTaskService.STATUS_PENDING);
        assertThat(t.getTaskType()).isEqualTo("PREMARKET");
        assertThat(t.getTargetCandidatesJson()).contains("2303");
    }

    // ── 2. createTask 重複 UPSERT ─────────────────────────────────────────
    @Test
    void createTask_sameKey_shouldUpsertNotCreateNew() {
        LocalDate date = LocalDate.of(2026, 4, 20);
        AiTaskEntity first  = service.createTask(date, "PREMARKET", null,
                List.of(new AiTaskCandidateRef("2303", "聯電", null, null)), "first", null);
        AiTaskEntity second = service.createTask(date, "PREMARKET", null,
                List.of(new AiTaskCandidateRef("2330", "TSMC", null, null)), "second", null);

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(second.getTargetCandidatesJson()).contains("2330");
        assertThat(store).hasSize(1);
    }

    // ── 3. claimNextPending 將 PENDING 轉為 CLAUDE_RUNNING ────────────────
    @Test
    void claimNextPending_shouldMoveToClaudeRunning() {
        LocalDate date = LocalDate.of(2026, 4, 20);
        service.createTask(date, "PREMARKET", null, List.of(), "t", null);

        Optional<AiTaskEntity> claimed = service.claimNextPending("PREMARKET");

        assertThat(claimed).isPresent();
        assertThat(claimed.get().getStatus()).isEqualTo(AiTaskService.STATUS_CLAUDE_RUNNING);
        assertThat(claimed.get().getClaudeStartedAt()).isNotNull();
    }

    @Test
    void claimNextPending_noPending_shouldReturnEmpty() {
        Optional<AiTaskEntity> claimed = service.claimNextPending("PREMARKET");
        assertThat(claimed).isEmpty();
    }

    // ── 4. submitClaudeResult 寫分數到 stock_evaluation ────────────────────
    @Test
    void submitClaudeResult_shouldMoveToClaudeDoneAndWriteScores() {
        LocalDate date = LocalDate.of(2026, 4, 20);
        AiTaskEntity t = service.createTask(date, "PREMARKET", null, List.of(), "t", null);
        service.claim(t.getId());

        Map<String, BigDecimal> scores = new HashMap<>();
        scores.put("2303", new BigDecimal("8.5"));
        scores.put("3231", new BigDecimal("7.8"));
        Map<String, String> thesis = new HashMap<>();
        thesis.put("2303", "好");

        AiTaskService.SubmitResult res = service.submitClaudeResult(t.getId(),
                new ClaudeSubmitRequest("full md", scores, thesis, List.of("risk1")));
        AiTaskEntity submitted = res.task();

        assertThat(res.idempotent()).isFalse();
        assertThat(submitted.getStatus()).isEqualTo(AiTaskService.STATUS_CLAUDE_DONE);
        assertThat(submitted.getClaudeDoneAt()).isNotNull();
        assertThat(submitted.getClaudeScoresJson()).contains("2303");

        // 驗證 stockEvaluationService.updateAiScores 被呼叫 2 次（2 檔）
        ArgumentCaptor<String> symbolCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<AiScoreUpdateRequest> reqCap = ArgumentCaptor.forClass(AiScoreUpdateRequest.class);
        verify(stockEvaluationService, times(2)).updateAiScores(symbolCap.capture(), reqCap.capture());
        assertThat(symbolCap.getAllValues()).containsExactlyInAnyOrder("2303", "3231");
        assertThat(reqCap.getAllValues())
                .allMatch(r -> r.claudeScore() != null && r.codexScore() == null);

        // ai_research_log 相容寫入
        verify(aiResearchLogRepository, atLeastOnce()).save(any(AiResearchLogEntity.class));
    }

    // ── 5. submitCodexResult 寫分數 + veto ──────────────────────────────
    @Test
    void submitCodexResult_shouldMoveToCodexDoneAndWriteVeto() {
        LocalDate date = LocalDate.of(2026, 4, 20);
        AiTaskEntity t = service.createTask(date, "PREMARKET", null, List.of(), "t", null);
        service.claim(t.getId());
        service.submitClaudeResult(t.getId(),
                new ClaudeSubmitRequest("md", Map.of("2303", new BigDecimal("8.5")), null, null));

        Map<String, BigDecimal> cscores = Map.of("2303", new BigDecimal("8.0"));
        Map<String, String> issues = Map.of("6213", "風險未揭露");

        AiTaskEntity submitted = service.submitCodexResult(t.getId(),
                new CodexSubmitRequest("codex md", cscores, List.of("6213"), issues, null)).task();

        assertThat(submitted.getStatus()).isEqualTo(AiTaskService.STATUS_CODEX_DONE);
        assertThat(submitted.getCodexDoneAt()).isNotNull();
        assertThat(submitted.getCodexVetoSymbolsJson()).contains("6213");

        // Codex update 應被呼叫
        ArgumentCaptor<AiScoreUpdateRequest> reqCap = ArgumentCaptor.forClass(AiScoreUpdateRequest.class);
        verify(stockEvaluationService, atLeastOnce()).updateAiScores(eq("2303"), reqCap.capture());
        assertThat(reqCap.getAllValues())
                .anyMatch(r -> r.codexScore() != null && r.codexScore().compareTo(new BigDecimal("8.0")) == 0);
    }

    // ── 6. finalizeTask → FINALIZED（v2.1：要先 CODEX_DONE）────────────────
    @Test
    void finalizeTask_shouldMarkFinalized() {
        LocalDate date = LocalDate.of(2026, 4, 20);
        AiTaskEntity t = service.createTask(date, "PREMARKET", null, List.of(), "t", null);
        service.claim(t.getId());
        service.submitClaudeResult(t.getId(),
                new ClaudeSubmitRequest("md", Collections.emptyMap(), null, null));
        service.submitCodexResult(t.getId(),
                new CodexSubmitRequest("codex", Collections.emptyMap(), List.of(), null, null));

        AiTaskEntity done = service.finalizeTask(t.getId());
        assertThat(done.getStatus()).isEqualTo(AiTaskService.STATUS_FINALIZED);
        assertThat(done.getFinalizedAt()).isNotNull();
    }

    // ── 7. isClaudeReady 在各狀態 ──────────────────────────────────────
    @Test
    void isClaudeReady_shouldReflectStatusProgression() {
        LocalDate date = LocalDate.of(2026, 4, 20);
        AiTaskEntity t = service.createTask(date, "PREMARKET", null, List.of(), "t", null);

        assertThat(service.isClaudeReady(date, "PREMARKET")).isFalse();  // PENDING

        service.claim(t.getId());
        assertThat(service.isClaudeReady(date, "PREMARKET")).isFalse();  // CLAUDE_RUNNING

        service.submitClaudeResult(t.getId(),
                new ClaudeSubmitRequest("md", Collections.emptyMap(), null, null));
        assertThat(service.isClaudeReady(date, "PREMARKET")).isTrue();   // CLAUDE_DONE

        service.submitCodexResult(t.getId(),
                new CodexSubmitRequest("codex", Collections.emptyMap(), List.of(), null, null));
        service.finalizeTask(t.getId());
        assertThat(service.isClaudeReady(date, "PREMARKET")).isTrue();   // FINALIZED
    }

    @Test
    void isCodexReady_onlyTrueAfterCodexDone() {
        LocalDate date = LocalDate.of(2026, 4, 20);
        AiTaskEntity t = service.createTask(date, "PREMARKET", null, List.of(), "t", null);
        service.claim(t.getId());
        service.submitClaudeResult(t.getId(),
                new ClaudeSubmitRequest("md", Collections.emptyMap(), null, null));

        assertThat(service.isCodexReady(date, "PREMARKET")).isFalse();

        service.submitCodexResult(t.getId(),
                new CodexSubmitRequest("md", Collections.emptyMap(), List.of(), null, null));

        assertThat(service.isCodexReady(date, "PREMARKET")).isTrue();
    }

    // ── 8. submit 不存在 task → throw（v2.1：AiTaskInvalidStateException / TASK_NOT_FOUND）─────
    @Test
    void submitClaudeResult_taskNotFound_shouldThrow() {
        assertThatThrownBy(() -> service.submitClaudeResult(999L,
                new ClaudeSubmitRequest("md", null, null, null)))
                .isInstanceOf(AiTaskInvalidStateException.class)
                .matches(e -> ((AiTaskInvalidStateException) e).getErrorCode()
                        == AiTaskErrorCode.TASK_NOT_FOUND);
    }

    // ── 9. FINALIZED 的 task 再提交 → throw（v2.1：AiTaskInvalidStateException）─────
    @Test
    void submitClaudeResult_finalizedTask_shouldThrow() {
        LocalDate date = LocalDate.of(2026, 4, 20);
        AiTaskEntity t = service.createTask(date, "PREMARKET", null, List.of(), "t", null);
        service.claim(t.getId());
        service.submitClaudeResult(t.getId(),
                new ClaudeSubmitRequest("md", Collections.emptyMap(), null, null));
        // v2.1：要先 CODEX_DONE 才能 finalize
        service.submitCodexResult(t.getId(),
                new CodexSubmitRequest("codex", Collections.emptyMap(), List.of(), null, null));
        service.finalizeTask(t.getId());

        assertThatThrownBy(() -> service.submitClaudeResult(t.getId(),
                new ClaudeSubmitRequest("changed", Collections.emptyMap(), null, null)))
                .isInstanceOf(AiTaskInvalidStateException.class);
    }

    // ── 9b. CODEX_DONE 收到 Claude result → AI_TASK_ALREADY_ADVANCED ───
    @Test
    void submitClaudeResult_onCodexDone_shouldFail409() {
        LocalDate date = LocalDate.of(2026, 4, 20);
        AiTaskEntity t = service.createTask(date, "PREMARKET", null, List.of(), "t", null);
        service.claim(t.getId());
        service.submitClaudeResult(t.getId(),
                new ClaudeSubmitRequest("md", Collections.emptyMap(), null, null));
        service.submitCodexResult(t.getId(),
                new CodexSubmitRequest("codex", Collections.emptyMap(), List.of(), null, null));

        assertThatThrownBy(() -> service.submitClaudeResult(t.getId(),
                new ClaudeSubmitRequest("new md", Collections.emptyMap(), null, null)))
                .isInstanceOf(AiTaskInvalidStateException.class)
                .matches(e -> ((AiTaskInvalidStateException) e).getErrorCode()
                        == AiTaskErrorCode.AI_TASK_ALREADY_ADVANCED);
    }

    // ── 9c. 相同 Claude hash 重送 → idempotent success ─────────────────
    @Test
    void submitClaudeResult_sameHashRepost_shouldBeIdempotent() {
        LocalDate date = LocalDate.of(2026, 4, 20);
        AiTaskEntity t = service.createTask(date, "PREMARKET", null, List.of(), "t", null);
        service.claim(t.getId());
        ClaudeSubmitRequest req = new ClaudeSubmitRequest("md-v1",
                java.util.Map.of("2303", new BigDecimal("8.0")), null, null);

        AiTaskService.SubmitResult first = service.submitClaudeResult(t.getId(), req);
        AiTaskService.SubmitResult second = service.submitClaudeResult(t.getId(), req);

        assertThat(first.idempotent()).isFalse();
        assertThat(second.idempotent()).isTrue();
        assertThat(second.task().getStatus()).isEqualTo(AiTaskService.STATUS_CLAUDE_DONE);
    }

    // ── 9d. FAILED task 不可 claim 回 running ──────────────────────────
    @Test
    void failedTask_cannotClaimAgain() {
        LocalDate date = LocalDate.of(2026, 4, 20);
        AiTaskEntity t = service.createTask(date, "PREMARKET", null, List.of(), "t", null);
        service.failTask(t.getId(), "simulated");

        assertThatThrownBy(() -> service.claimClaude(t.getId()))
                .isInstanceOf(AiTaskInvalidStateException.class);
    }

    // ── 10. failTask → FAILED ──────────────────────────────────────────
    @Test
    void failTask_shouldMarkFailedWithError() {
        LocalDate date = LocalDate.of(2026, 4, 20);
        AiTaskEntity t = service.createTask(date, "PREMARKET", null, List.of(), "t", null);

        AiTaskEntity failed = service.failTask(t.getId(), "Claude API timeout");

        assertThat(failed.getStatus()).isEqualTo(AiTaskService.STATUS_FAILED);
        assertThat(failed.getErrorMessage()).isEqualTo("Claude API timeout");
    }

    // ── 11. STOCK_EVAL + target_symbol 可與 PREMARKET 並存 ─────────────
    @Test
    void differentTargetSymbol_shouldCreateSeparateTasks() {
        LocalDate date = LocalDate.of(2026, 4, 20);
        AiTaskEntity a = service.createTask(date, "STOCK_EVAL", "2303", List.of(), "a", null);
        AiTaskEntity b = service.createTask(date, "STOCK_EVAL", "2330", List.of(), "b", null);

        assertThat(a.getId()).isNotEqualTo(b.getId());
        assertThat(store).hasSize(2);
    }

    // ── 12. claim() 非 PENDING → throw（v2.1：AiTaskInvalidStateException）─────
    @Test
    void claim_nonPendingTask_shouldThrow() {
        LocalDate date = LocalDate.of(2026, 4, 20);
        AiTaskEntity t = service.createTask(date, "PREMARKET", null, List.of(), "t", null);
        service.claim(t.getId());
        assertThatThrownBy(() -> service.claim(t.getId()))
                .isInstanceOf(AiTaskInvalidStateException.class);
    }
}
