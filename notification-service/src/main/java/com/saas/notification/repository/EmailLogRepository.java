package com.saas.notification.repository;

import com.saas.notification.entity.EmailLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface EmailLogRepository extends JpaRepository<EmailLog, UUID> {
    List<EmailLog> findByToEmail(String toEmail);
}
