package com.saas.notification.repository;

import com.saas.notification.entity.InAppNotification;
import com.saas.shared.tenancy.TenantScopedRepository;

import java.util.List;
import java.util.UUID;

public interface InAppNotificationRepository extends TenantScopedRepository<InAppNotification, UUID> {

    List<InAppNotification> findAllByUserIdOrderByCreatedAtDesc(UUID userId);
}
