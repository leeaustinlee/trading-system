package com.austin.trading.scheduler;

import com.austin.trading.entity.AiTaskEntity;
import com.austin.trading.entity.FinalDecisionEntity;
import com.austin.trading.notify.NotificationFacade;
import com.austin.trading.repository.AiTaskRepository;
import com.austin.trading.repository.FinalDecisionRepository;
import com.austin.trading.service.DailyOrchestrationService;
import com.austin.trading.service.SchedulerLogService;
import com.austin.trading.service.ScoreConfigService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.domain.Pageable;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 健康檢查單元測試。
 *
 * <p>覆蓋四個基本檢查：DB ping / stuck ai_task / claude-submit dir 堆積 / 今日 final_decision 紀錄。</p>
 */
class DailyHealthCheckJobTests {

    @TempDir
    Path tempDir;

    @Test
    void countClaudeSubmitFiles_countsJsonAndTmpFiles() throws IOException {
        Files.writeString(tempDir.resolve("claude-OPENING-1.json"), "{}");
        Files.writeString(tempDir.resolve("claude-OPENING-2.json.tmp"), "{}");
        Files.writeString(tempDir.resolve("claude-OPENING-3.json.processing"), "{}");
        Files.writeString(tempDir.resolve("README.md"), "ignored");
        Files.createDirectory(tempDir.resolve("processed"));

        DailyHealthCheckJob job = newJob(tempDir.toString());
        int n = job.countClaudeSubmitFiles(tempDir.toString());
        assertThat(n).isEqualTo(3);
    }

    @Test
    void countClaudeSubmitFiles_returnsZeroForMissingDir() throws IOException {
        DailyHealthCheckJob job = newJob("/nonexistent/path/" + System.nanoTime());
        assertThat(job.countClaudeSubmitFiles("/nonexistent/path/" + System.nanoTime())).isEqualTo(0);
    }

    @Test
    void countStuckAiTasks_detectsLongRunningClaudeTasks() {
        AiTaskRepository aiTaskRepo = mock(AiTaskRepository.class);
        AiTaskEntity stuck = new AiTaskEntity();
        stuck.setStatus("CLAUDE_RUNNING");
        stuck.setClaudeStartedAt(LocalDateTime.now().minusHours(2));
        AiTaskEntity fresh = new AiTaskEntity();
        fresh.setStatus("CLAUDE_RUNNING");
        fresh.setClaudeStartedAt(LocalDateTime.now().minusMinutes(5));
        when(aiTaskRepo.findByStatusOrderByCreatedAtAsc("CLAUDE_RUNNING"))
                .thenReturn(List.of(stuck, fresh));
        when(aiTaskRepo.findByStatusOrderByCreatedAtAsc("CODEX_RUNNING")).thenReturn(List.of());
        when(aiTaskRepo.findByStatusOrderByCreatedAtAsc("RUNNING")).thenReturn(List.of());

        DailyHealthCheckJob job = newJob(aiTaskRepo, tempDir.toString());
        int stuckCount = job.countStuckAiTasks(LocalDateTime.now().minusMinutes(60));
        assertThat(stuckCount).isEqualTo(1);
    }

    @Test
    void runHealthCheck_healthyState_returnsNullAndDoesNotSendAlert() throws Exception {
        DataSource ds = mock(DataSource.class);
        Connection conn = mock(Connection.class);
        when(ds.getConnection()).thenReturn(conn);
        when(conn.isValid(anyInt())).thenReturn(true);

        AiTaskRepository aiTaskRepo = mock(AiTaskRepository.class);
        when(aiTaskRepo.findByStatusOrderByCreatedAtAsc(anyString())).thenReturn(List.of());
        when(aiTaskRepo.findByTradingDateAndTaskTypeAndTargetSymbolIsNull(any(), eq("OPENING")))
                .thenReturn(java.util.Optional.empty());

        FinalDecisionRepository finalRepo = mock(FinalDecisionRepository.class);
        FinalDecisionEntity todayDecision = new FinalDecisionEntity();
        todayDecision.setTradingDate(LocalDate.now());
        when(finalRepo.findAllByOrderByTradingDateDescCreatedAtDesc(any(Pageable.class)))
                .thenReturn(List.of(todayDecision));

        DailyOrchestrationService orch = mock(DailyOrchestrationService.class);
        when(orch.sweepStaleRunning(anyInt(), anyInt())).thenReturn(List.of());
        when(orch.listIncompleteSteps(any())).thenReturn(List.of());

        ScoreConfigService cfg = mock(ScoreConfigService.class);
        when(cfg.getBoolean(eq("scheduling.line_notify_enabled"), anyBoolean())).thenReturn(true);
        when(cfg.getBoolean(eq("final_decision.require_codex"), anyBoolean())).thenReturn(true);

        NotificationFacade line = mock(NotificationFacade.class);
        SchedulerLogService logSvc = mock(SchedulerLogService.class);

        DailyHealthCheckJob job = new DailyHealthCheckJob(orch, line, logSvc, cfg, ds,
                aiTaskRepo, finalRepo, tempDir.toString());

        String result = job.runHealthCheck();

        assertThat(result).isNull();
        verify(line, never()).notifySystemAlert(anyString(), anyString());
    }

    @Test
    void runHealthCheck_dbPingFails_emitsSystemAlert() throws Exception {
        DataSource ds = mock(DataSource.class);
        when(ds.getConnection()).thenThrow(new SQLException("connection refused"));

        AiTaskRepository aiTaskRepo = mock(AiTaskRepository.class);
        when(aiTaskRepo.findByStatusOrderByCreatedAtAsc(anyString())).thenReturn(List.of());
        when(aiTaskRepo.findByTradingDateAndTaskTypeAndTargetSymbolIsNull(any(), anyString()))
                .thenReturn(java.util.Optional.empty());

        FinalDecisionRepository finalRepo = mock(FinalDecisionRepository.class);
        FinalDecisionEntity todayDecision = new FinalDecisionEntity();
        todayDecision.setTradingDate(LocalDate.now());
        when(finalRepo.findAllByOrderByTradingDateDescCreatedAtDesc(any(Pageable.class)))
                .thenReturn(List.of(todayDecision));

        DailyOrchestrationService orch = mock(DailyOrchestrationService.class);
        when(orch.sweepStaleRunning(anyInt(), anyInt())).thenReturn(List.of());
        when(orch.listIncompleteSteps(any())).thenReturn(List.of());

        ScoreConfigService cfg = mock(ScoreConfigService.class);
        when(cfg.getBoolean(eq("scheduling.line_notify_enabled"), anyBoolean())).thenReturn(true);
        when(cfg.getBoolean(eq("final_decision.require_codex"), anyBoolean())).thenReturn(true);

        NotificationFacade line = mock(NotificationFacade.class);
        SchedulerLogService logSvc = mock(SchedulerLogService.class);

        DailyHealthCheckJob job = new DailyHealthCheckJob(orch, line, logSvc, cfg, ds,
                aiTaskRepo, finalRepo, tempDir.toString());

        String alert = job.runHealthCheck();
        assertThat(alert).isNotNull();
        assertThat(alert).contains("DB_PING_FAIL");
        verify(line).notifySystemAlert(eq("每日健康檢查"), anyString());
    }

    // ─────────────────────────────────────────────────────────────────────

    private DailyHealthCheckJob newJob(String submitDir) {
        return newJob(mock(AiTaskRepository.class), submitDir);
    }

    private DailyHealthCheckJob newJob(AiTaskRepository aiTaskRepo, String submitDir) {
        DataSource ds = mock(DataSource.class);
        FinalDecisionRepository finalRepo = mock(FinalDecisionRepository.class);
        when(finalRepo.findAllByOrderByTradingDateDescCreatedAtDesc(any(Pageable.class)))
                .thenReturn(List.of());
        DailyOrchestrationService orch = mock(DailyOrchestrationService.class);
        when(orch.sweepStaleRunning(anyInt(), anyInt())).thenReturn(List.of());
        when(orch.listIncompleteSteps(any())).thenReturn(List.of());
        ScoreConfigService cfg = mock(ScoreConfigService.class);
        NotificationFacade line = mock(NotificationFacade.class);
        SchedulerLogService logSvc = mock(SchedulerLogService.class);
        return new DailyHealthCheckJob(orch, line, logSvc, cfg, ds,
                aiTaskRepo, finalRepo, submitDir);
    }
}
