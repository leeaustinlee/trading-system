package com.austin.trading.service;

import com.austin.trading.dto.request.NotificationCreateRequest;
import com.austin.trading.dto.response.NotificationResponse;
import com.austin.trading.entity.NotificationLogEntity;
import com.austin.trading.repository.NotificationLogRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

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
