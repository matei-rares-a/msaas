package com.saas.notification.repository;

import com.saas.notification.entity.InAppNotification;
import com.saas.shared.tenancy.TenantScopedRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InAppNotificationRepository extends TenantScopedRepository<InAppNotification, UUID> {

    List<InAppNotification> findAllByUserIdOrderByCreatedAtDesc(UUID userId);

    Page<InAppNotification> findAllByUserId(UUID userId, Pageable pageable);

    Optional<InAppNotification> findByIdAndUserId(UUID id, UUID userId);

    List<InAppNotification> findAllByUserIdAndReadFalse(UUID userId);

    long countByUserIdAndReadFalse(UUID userId);

    void deleteAllByUserId(UUID userId);
}
