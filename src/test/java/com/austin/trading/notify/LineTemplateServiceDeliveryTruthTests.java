package com.austin.trading.notify;

import com.austin.trading.dto.request.NotificationCreateRequest;
import com.austin.trading.service.NotificationService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LineTemplateServiceDeliveryTruthTests {

    @Test
    void skippedSendPersistsSkippedNotDeliveredTruth() {
        LineSender sender = mock(LineSender.class);
        NotificationService notificationService = mock(NotificationService.class);
        when(sender.sendWithResult(any())).thenReturn(
                NotificationDeliveryResult.skipped("LINE", "DISABLED", "disabled"));
        LineTemplateService service = new LineTemplateService(sender, notificationService);

        service.notifyMidday("hello", LocalDate.of(2026, 5, 18));

        ArgumentCaptor<NotificationCreateRequest> captor = ArgumentCaptor.forClass(NotificationCreateRequest.class);
        verify(notificationService).create(captor.capture());
        NotificationCreateRequest request = captor.getValue();
        assertThat(request.provider()).isEqualTo("LINE");
        assertThat(request.deliveryStatus()).isEqualTo(NotificationDeliveryResult.STATUS_SKIPPED);
        assertThat(request.attempted()).isFalse();
        assertThat(request.delivered()).isFalse();
        assertThat(request.errorCode()).isEqualTo("DISABLED");
    }

    @Test
    void persistFailureIsSwallowedAfterFailedSend() {
        LineSender sender = mock(LineSender.class);
        NotificationService notificationService = mock(NotificationService.class);
        when(sender.sendWithResult(any())).thenReturn(
                NotificationDeliveryResult.failed("LINE", 500, "HTTP_500", "boom", 0));
        doThrow(new RuntimeException("db down")).when(notificationService).create(any());
        LineTemplateService service = new LineTemplateService(sender, notificationService);

        service.notifyMidday("hello", LocalDate.of(2026, 5, 18));

        verify(sender).sendWithResult(any());
        verify(notificationService).create(any());
    }

    @Test
    void senderExceptionIsSwallowedAndPersistedAsFailedTruth() {
        LineSender sender = mock(LineSender.class);
        NotificationService notificationService = mock(NotificationService.class);
        when(sender.sendWithResult(any())).thenThrow(new RuntimeException("sender boom"));
        LineTemplateService service = new LineTemplateService(sender, notificationService);

        service.notifyMidday("hello", LocalDate.of(2026, 5, 18));

        ArgumentCaptor<NotificationCreateRequest> captor = ArgumentCaptor.forClass(NotificationCreateRequest.class);
        verify(notificationService).create(captor.capture());
        NotificationCreateRequest request = captor.getValue();
        assertThat(request.provider()).isEqualTo("LINE");
        assertThat(request.deliveryStatus()).isEqualTo(NotificationDeliveryResult.STATUS_FAILED);
        assertThat(request.attempted()).isTrue();
        assertThat(request.delivered()).isFalse();
        assertThat(request.errorCode()).isEqualTo("RuntimeException");
    }
}
