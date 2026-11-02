package com.saas.notification.repository;

import com.saas.notification.entity.EmailLog;
import com.saas.shared.tenancy.TenantScopedRepository;

import java.util.List;
import java.util.UUID;

public interface EmailLogRepository extends TenantScopedRepository<EmailLog, UUID> {

    List<EmailLog> findAllByRecipientUserId(UUID recipientUserId);
}
