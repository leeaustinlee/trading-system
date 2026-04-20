package com.austin.trading.repository;

import com.austin.trading.entity.NotificationLogEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface NotificationLogRepository extends JpaRepository<NotificationLogEntity, Long> {

    List<NotificationLogEntity> findAllByOrderByEventTimeDesc(Pageable pageable);

    boolean existsByNotificationTypeAndTitleAndEventTimeAfter(
            String notificationType,
            String title,
            LocalDateTime eventTime
    );
}
