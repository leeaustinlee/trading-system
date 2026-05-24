package com.austin.trading.service;

import com.austin.trading.entity.SystemBuildTraceEntity;
import com.austin.trading.repository.SystemBuildTraceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SystemBuildTraceServiceTraceTest {
    @Test
    void recordsSuccessAndFailureAsRecoverableBuildTrace() {
        SystemBuildTraceRepository repo = mock(SystemBuildTraceRepository.class);
        when(repo.saveAndFlush(any(SystemBuildTraceEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        SystemBuildTraceService service = new SystemBuildTraceService(repo, new ObjectMapper());

        SystemBuildTraceEntity started = service.start("REPLAY_METRICS", LocalDate.of(2026, 5, 25), null, Map.of("replayOnly", true));
        assertThat(started.getStatus()).isEqualTo("PARTIAL");
        assertThat(started.getSafetyBoundaryJson()).contains("replayOnly");

        when(repo.findById(null)).thenReturn(Optional.of(started));
        SystemBuildTraceEntity success = service.success(null, 2, 7, 0, 0, Map.of("builtCount", 7));
        assertThat(success.getStatus()).isEqualTo("SUCCESS");
        assertThat(success.getDeletedCount()).isEqualTo(2);
        assertThat(success.getInsertedCount()).isEqualTo(7);
        assertThat(success.getDurationMs()).isNotNull();

        SystemBuildTraceEntity failed = service.failed(null, 2, 0, 0, new IllegalStateException("boom"), Map.of("stage", "test"));
        assertThat(failed.getStatus()).isEqualTo("FAILED");
        assertThat(failed.getErrorMessage()).contains("boom");
    }
}
