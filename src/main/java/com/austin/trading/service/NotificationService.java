package com.austin.trading.service;

import com.austin.trading.dto.request.NotificationCreateRequest;
import com.austin.trading.dto.response.NotificationResponse;
import com.austin.trading.entity.NotificationLogEntity;
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
        NotificationLogEntity entity = new NotificationLogEntity();
        entity.setEventTime(request.eventTime());
        entity.setNotificationType(request.notificationType());
        entity.setSource(request.source());
        entity.setTitle(request.title());
        entity.setContent(request.content());
        entity.setPayloadJson(request.payloadJson());
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
                entity.getPayloadJson()
        );
    }
}
