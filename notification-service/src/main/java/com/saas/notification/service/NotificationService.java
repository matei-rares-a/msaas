package com.saas.notification.service;

import com.saas.notification.entity.EmailLog;
import com.saas.notification.repository.EmailLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Only metadata is persisted; the body would contain the invitation token (REQ-NOTIF-06).
 * Delivery, retries and the activity-log entries arrive in P9.4. Identity emails (password reset)
 * belong to no tenant and are never stored here (REQ-PWD-08).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final EmailLogRepository emailLogRepository;

    @Transactional
    public void sendInvitationEmail(String toEmail, String tenantName, String inviteToken) {
        EmailLog emailLog = new EmailLog();
        emailLog.setId(UUID.randomUUID());
        emailLog.setToEmail(toEmail);
        emailLog.setSubject("You're invited to join " + tenantName);
        emailLog.setStatus("SENT");
        emailLog.setEventType("USER_INVITED");

        emailLogRepository.save(emailLog);
        log.info("Invitation email logged for tenant {}", emailLog.getTenantId());
    }
}
