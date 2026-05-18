package com.austin.trading.service;

import com.austin.trading.dto.request.NotificationCreateRequest;
import com.austin.trading.dto.response.NotificationResponse;
import com.austin.trading.entity.NotificationLogEntity;
import com.austin.trading.notify.NotificationDeliveryResult;
import com.austin.trading.repository.NotificationLogRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NotificationServiceTests {

    @Test
    void create_defaultsOldRequestToCreatedNotDelivered() {
        NotificationLogRepository repo = mock(NotificationLogRepository.class);
        when(repo.save(any(NotificationLogEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        NotificationService service = new NotificationService(repo);

        NotificationResponse response = service.create(new NotificationCreateRequest(
                LocalDateTime.of(2026, 5, 18, 9, 0),
                "TYPE", "SRC", "title", "content", null));

        assertThat(response.deliveryStatus()).isEqualTo(NotificationDeliveryResult.STATUS_CREATED);
        assertThat(response.attempted()).isFalse();
        assertThat(response.delivered()).isFalse();
        assertThat(response.retryCount()).isZero();
    }

    @Test
    void create_mapsDeliveryTruthToResponse() {
        NotificationLogRepository repo = mock(NotificationLogRepository.class);
        when(repo.save(any(NotificationLogEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        NotificationService service = new NotificationService(repo);
        LocalDateTime now = LocalDateTime.of(2026, 5, 18, 9, 30);
        NotificationDeliveryResult deliveryResult = NotificationDeliveryResult.failed(
                "TELEGRAM", 500, "HTTP_500", "boom", 1);

        NotificationResponse response = service.create(new NotificationCreateRequest(
                now, "TG_TYPE", "SRC", "title", "content", null, deliveryResult));

        assertThat(response.provider()).isEqualTo("TELEGRAM");
        assertThat(response.deliveryStatus()).isEqualTo(NotificationDeliveryResult.STATUS_FAILED);
        assertThat(response.attempted()).isTrue();
        assertThat(response.delivered()).isFalse();
        assertThat(response.attemptedAt()).isEqualTo(now);
        assertThat(response.deliveredAt()).isNull();
        assertThat(response.providerHttpStatus()).isEqualTo(500);
        assertThat(response.errorCode()).isEqualTo("HTTP_500");
        assertThat(response.errorBody()).isEqualTo("boom");
        assertThat(response.retryCount()).isEqualTo(1);
    }

    @Test
    void createManual_sanitizesForgedDeliveryTruth() {
        NotificationLogRepository repo = mock(NotificationLogRepository.class);
        when(repo.save(any(NotificationLogEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        NotificationService service = new NotificationService(repo);
        LocalDateTime now = LocalDateTime.of(2026, 5, 18, 10, 0);

        NotificationResponse response = service.createManual(new NotificationCreateRequest(
                now, "TYPE", "API", "title", "content", null,
                "TELEGRAM", NotificationDeliveryResult.STATUS_DELIVERED,
                true, true, now, now, 200, "123", null, null, 0));

        assertThat(response.provider()).isNull();
        assertThat(response.deliveryStatus()).isEqualTo(NotificationDeliveryResult.STATUS_CREATED);
        assertThat(response.attempted()).isFalse();
        assertThat(response.delivered()).isFalse();
        assertThat(response.providerHttpStatus()).isNull();
        assertThat(response.providerMessageId()).isNull();
    }
}
