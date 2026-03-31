package com.saas.notification.repository;

import com.saas.notification.entity.InAppNotification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface InAppNotificationRepository extends JpaRepository<InAppNotification, UUID> {
    List<InAppNotification> findAllByOrganizationIdAndUserIdOrderByCreatedAtDesc(UUID organizationId, UUID userId);
}
