package com.austin.trading.service;

import com.austin.trading.domain.enums.SchedulerHealthLevel;
import com.austin.trading.entity.SchedulerExecutionLogEntity;
import com.austin.trading.repository.SchedulerExecutionLogRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SchedulerLogServiceTests {

    private static final LocalDateTime START = LocalDateTime.of(2026, 5, 18, 9, 0);
    private static final LocalDateTime END = LocalDateTime.of(2026, 5, 18, 9, 0, 1);

    @Test
    void success_defaultsToSuccessReal() {
        SchedulerExecutionLogEntity saved = saveByCalling(service(), svc ->
                svc.success("Job", START, END, "workflow completed"));

        assertThat(saved.getStatus()).isEqualTo("SUCCESS");
        assertThat(saved.getHealthLevel()).isEqualTo(SchedulerHealthLevel.SUCCESS_REAL.name());
        assertThat(saved.getHealthReason()).isEqualTo("workflow completed");
    }

    @Test
    void success_infersEmptyDataForNoCandidateMessages() {
        SchedulerExecutionLogEntity saved = saveByCalling(service(), svc ->
                svc.success("OpenDataPrepJob", START, END, "No candidates"));

        assertThat(saved.getStatus()).isEqualTo("SUCCESS");
        assertThat(saved.getHealthLevel()).isEqualTo(SchedulerHealthLevel.EMPTY_DATA.name());
    }

    @Test
    void success_infersFallbackAndDegradedLevels() {
        SchedulerExecutionLogEntity fallback = saveByCalling(service(), svc ->
                svc.success("Job", START, END, "fallback_used=true produced partial output"));
        SchedulerExecutionLogEntity degraded = saveByCalling(service(), svc ->
                svc.success("ProbeJob", START, END, "failures=2"));

        assertThat(fallback.getHealthLevel()).isEqualTo(SchedulerHealthLevel.SUCCESS_WITH_FALLBACK.name());
        assertThat(degraded.getHealthLevel()).isEqualTo(SchedulerHealthLevel.DEGRADED.name());
    }

    @Test
    void explicitLevelMethodsKeepLegacySuccessStatus() {
        SchedulerExecutionLogEntity saved = saveByCalling(service(), svc ->
                svc.degraded("DailyHealthCheckJob", START, END, "incomplete=1 stale=0 systemIssues=0"));

        assertThat(saved.getStatus()).isEqualTo("SUCCESS");
        assertThat(saved.getHealthLevel()).isEqualTo(SchedulerHealthLevel.DEGRADED.name());
    }

    @Test
    void failed_setsFailedHealthLevel() {
        SchedulerExecutionLogEntity saved = saveByCalling(service(), svc ->
                svc.failed("Job", START, END, "boom"));

        assertThat(saved.getStatus()).isEqualTo("FAILED");
        assertThat(saved.getHealthLevel()).isEqualTo(SchedulerHealthLevel.FAILED.name());
    }

    @Test
    void longHealthReasonIsTruncated() {
        String longMessage = "x".repeat(600);
        SchedulerExecutionLogEntity saved = saveByCalling(service(), svc ->
                svc.success("Job", START, END, longMessage));

        assertThat(saved.getMessage()).hasSize(500);
        assertThat(saved.getHealthReason()).hasSize(500);
    }

    private SchedulerLogService service() {
        SchedulerExecutionLogRepository repo = mock(SchedulerExecutionLogRepository.class);
        when(repo.save(any(SchedulerExecutionLogEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        return new SchedulerLogService(repo);
    }

    private SchedulerExecutionLogEntity saveByCalling(SchedulerLogService service, ServiceCall call) {
        call.apply(service);
        ArgumentCaptor<SchedulerExecutionLogEntity> captor = ArgumentCaptor.forClass(SchedulerExecutionLogEntity.class);
        verify(serviceRepository(service)).save(captor.capture());
        return captor.getValue();
    }

    private SchedulerExecutionLogRepository serviceRepository(SchedulerLogService service) {
        try {
            java.lang.reflect.Field field = SchedulerLogService.class.getDeclaredField("schedulerExecutionLogRepository");
            field.setAccessible(true);
            return (SchedulerExecutionLogRepository) field.get(service);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    @FunctionalInterface
    private interface ServiceCall {
        void apply(SchedulerLogService service);
    }
}
