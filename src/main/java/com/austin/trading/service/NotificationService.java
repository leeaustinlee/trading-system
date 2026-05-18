package com.austin.trading.service;

import com.austin.trading.dto.request.NotificationCreateRequest;
import com.austin.trading.dto.response.NotificationResponse;
import com.austin.trading.entity.NotificationLogEntity;
import com.austin.trading.notify.NotificationDeliveryResult;
import com.austin.trading.repository.NotificationLogRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

@Service
public class NotificationService {

    private final NotificationLogRepository notificationLogRepository;

    public NotificationService(NotificationLogRepository notificationLogRepository) {
        this.notificationLogRepository = notificationLogRepository;
    }

    public List<NotificationResponse> getLatestNotifications(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        return notificationLogRepository.findAllByOrderByEventTimeDesc(PageRequest.of(0, safeLimit))
                .stream()
                .map(this::toResponse)
                .toList();
    }

    public List<NotificationResponse> getNotificationsByDate(LocalDate date, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        LocalDateTime start = date.atStartOfDay();
        LocalDateTime end   = date.atTime(LocalTime.MAX);
        return notificationLogRepository.findAllByEventTimeBetweenOrderByEventTimeDesc(
                        start, end, PageRequest.of(0, safeLimit))
                .stream()
                .map(this::toResponse)
                .toList();
    }

    public Optional<NotificationResponse> getLatestNotification() {
        return getLatestNotifications(1).stream().findFirst();
    }

    public Optional<NotificationResponse> getNotificationById(Long id) {
        return notificationLogRepository.findById(id).map(this::toResponse);
    }

    public NotificationResponse create(NotificationCreateRequest request) {
        return createInternal(request);
    }

    /**
     * Public/API-created notification rows are manual records, not provider delivery proof.
     *
     * <p>Do not trust delivery truth fields from HTTP clients; only sender/template code should
     * attach {@link NotificationDeliveryResult}. This prevents /api/notifications from forging
     * DELIVERED rows without an actual Telegram/LINE provider attempt.</p>
     */
    public NotificationResponse createManual(NotificationCreateRequest request) {
        return createInternal(new NotificationCreateRequest(
                request.eventTime(),
                request.notificationType(),
                request.source(),
                request.title(),
                request.content(),
                request.payloadJson()
        ));
    }

    private NotificationResponse createInternal(NotificationCreateRequest request) {
        NotificationLogEntity entity = new NotificationLogEntity();
        entity.setEventTime(request.eventTime());
        entity.setNotificationType(request.notificationType());
        entity.setSource(request.source());
        entity.setTitle(request.title());
        entity.setContent(request.content());
        entity.setPayloadJson(request.payloadJson());
        entity.setProvider(request.provider());
        entity.setDeliveryStatus(request.deliveryStatus());
        entity.setAttempted(request.attempted());
        entity.setDelivered(request.delivered());
        entity.setAttemptedAt(request.attemptedAt());
        entity.setDeliveredAt(request.deliveredAt());
        entity.setProviderHttpStatus(request.providerHttpStatus());
        entity.setProviderMessageId(request.providerMessageId());
        entity.setErrorCode(request.errorCode());
        entity.setErrorBody(request.errorBody());
        entity.setRetryCount(request.retryCount());
        return toResponse(notificationLogRepository.save(entity));
    }

    public boolean existsRecent(String notificationType, String title, LocalDateTime after) {
        return notificationLogRepository.existsByNotificationTypeAndTitleAndEventTimeAfter(
                notificationType, title, after);
    }

    private NotificationResponse toResponse(NotificationLogEntity entity) {
        return new NotificationResponse(
                entity.getId(),
                entity.getEventTime(),
                entity.getNotificationType(),
                entity.getSource(),
                entity.getTitle(),
                entity.getContent(),
                entity.getPayloadJson(),
                entity.getProvider(),
                defaultString(entity.getDeliveryStatus(), NotificationDeliveryResult.STATUS_CREATED),
                defaultBoolean(entity.getAttempted()),
                defaultBoolean(entity.getDelivered()),
                entity.getAttemptedAt(),
                entity.getDeliveredAt(),
                entity.getProviderHttpStatus(),
                entity.getProviderMessageId(),
                entity.getErrorCode(),
                entity.getErrorBody(),
                entity.getRetryCount() == null ? 0 : entity.getRetryCount()
        );
    }

    private static boolean defaultBoolean(Boolean value) {
        return value != null && value;
    }

    private static String defaultString(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
