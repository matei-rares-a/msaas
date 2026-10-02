package com.saas.notification.service;

import com.saas.notification.entity.EmailLog;
import com.saas.notification.repository.EmailLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final EmailLogRepository emailLogRepository;

    // Only metadata is persisted: the body would contain invitation/reset tokens. Delivery itself comes in P9.4.
    @Transactional
    public void sendInvitationEmail(String toEmail, String organizationName, String inviteToken) {
        EmailLog emailLog = new EmailLog();
        emailLog.setId(UUID.randomUUID());
        emailLog.setToEmail(toEmail);
        emailLog.setSubject("You're invited to join " + organizationName);
        emailLog.setStatus("SENT");
        emailLog.setEventType("USER_INVITED");

        emailLogRepository.save(emailLog);
        log.info("Invitation email sent to: {}", toEmail);
    }

    @Transactional
    public void sendPasswordResetEmail(String toEmail, String resetToken) {
        EmailLog emailLog = new EmailLog();
        emailLog.setId(UUID.randomUUID());
        emailLog.setToEmail(toEmail);
        emailLog.setSubject("Password Reset Request");
        emailLog.setStatus("SENT");
        emailLog.setEventType("PASSWORD_RESET");

        emailLogRepository.save(emailLog);
        log.info("Password reset email sent to: {}", toEmail);
    }
}
