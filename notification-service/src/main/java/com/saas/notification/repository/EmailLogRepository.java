package com.saas.notification.repository;

import com.saas.notification.entity.EmailLog;
import com.saas.shared.tenancy.TenantScopedRepository;

import java.util.UUID;

public interface EmailLogRepository extends TenantScopedRepository<EmailLog, UUID> {
}
