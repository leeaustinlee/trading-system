package com.austin.trading.controller;

import com.austin.trading.dto.request.CodexSubmitRequest;
import com.austin.trading.dto.response.FinalDecisionResponse;
import com.austin.trading.entity.AiTaskEntity;
import com.austin.trading.notify.NotificationFacade;
import com.austin.trading.service.AiTaskService;
import com.austin.trading.service.AiTaskService.SubmitResult;
import com.austin.trading.service.FinalDecisionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AiTaskControllerNotificationTests {

    private AiTaskService aiTaskService;
    private FinalDecisionService finalDecisionService;
    private NotificationFacade notificationFacade;
    private AiTaskController controller;

    @BeforeEach
    void setUp() {
        aiTaskService = mock(AiTaskService.class);
        finalDecisionService = mock(FinalDecisionService.class);
        notificationFacade = mock(NotificationFacade.class);
        controller = new AiTaskController(aiTaskService, finalDecisionService, notificationFacade);
    }

    @Test
    void submitCodex_notifiesFormalOpeningTaskThroughUnifiedFlow() {
        AiTaskEntity task = buildTask(170L, "OPENING", "CODEX_DONE", LocalDate.of(2026, 5, 11));
        CodexSubmitRequest req = new CodexSubmitRequest(
                "# 開盤結論\n- 只觀察",
                Map.of("1216", new BigDecimal("6.8")),
                List.of(),
                Map.of(),
                null
        );

        when(aiTaskService.submitCodexResult(eq(170L), any(CodexSubmitRequest.class)))
                .thenReturn(new SubmitResult(task, false));
        when(aiTaskService.getById(170L)).thenReturn(Optional.of(task));
        when(finalDecisionService.evaluateAndPersist(task.getTradingDate(), task.getTaskType()))
                .thenReturn(new FinalDecisionResponse("REST", List.of(), List.of(), "觀察"));

        controller.submitCodex(170L, req);

        verify(notificationFacade).notifyAiTaskFinal("OPENING", req.contentMarkdown(), task.getTradingDate());
    }

    @Test
    void submitCodex_doesNotNotifyUnsupportedTaskType() {
        AiTaskEntity task = buildTask(171L, "STOCK_EVAL", "CODEX_DONE", LocalDate.of(2026, 5, 11));
        CodexSubmitRequest req = new CodexSubmitRequest(
                "# 個股研究\n- 先觀察",
                Map.of("2330", new BigDecimal("7.2")),
                List.of(),
                Map.of(),
                null
        );

        when(aiTaskService.submitCodexResult(eq(171L), any(CodexSubmitRequest.class)))
                .thenReturn(new SubmitResult(task, false));
        when(aiTaskService.getById(171L)).thenReturn(Optional.of(task));
        when(finalDecisionService.evaluateAndPersist(task.getTradingDate(), task.getTaskType()))
                .thenReturn(new FinalDecisionResponse("REST", List.of(), List.of(), "個股觀察"));

        controller.submitCodex(171L, req);

        verify(notificationFacade, never()).notifyAiTaskFinal(any(), any(), any());
    }

    private static AiTaskEntity buildTask(Long id, String taskType, String status, LocalDate tradingDate) {
        AiTaskEntity task = new AiTaskEntity();
        task.setId(id);
        task.setTaskType(taskType);
        task.setStatus(status);
        task.setTradingDate(tradingDate);
        return task;
    }
}
