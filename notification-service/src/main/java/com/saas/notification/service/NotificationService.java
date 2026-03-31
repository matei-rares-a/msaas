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

    @Transactional
    public void sendInvitationEmail(String toEmail, String organizationName, String inviteToken) {
        String subject = "You're invited to join " + organizationName;
        String body = buildInvitationBody(organizationName, inviteToken);

        EmailLog log = new EmailLog();
        log.setId(UUID.randomUUID());
        log.setToEmail(toEmail);
        log.setSubject(subject);
        log.setBody(body);
        log.setStatus("SENT");
        log.setEventType("USER_INVITED");

        emailLogRepository.save(log);
        log.info("Invitation email sent to: {}", toEmail);
    }

    @Transactional
    public void sendPasswordResetEmail(String toEmail, String resetToken) {
        String subject = "Password Reset Request";
        String body = buildPasswordResetBody(resetToken);

        EmailLog log = new EmailLog();
        log.setId(UUID.randomUUID());
        log.setToEmail(toEmail);
        log.setSubject(subject);
        log.setBody(body);
        log.setStatus("SENT");
        log.setEventType("PASSWORD_RESET");

        emailLogRepository.save(log);
        log.info("Password reset email sent to: {}", toEmail);
    }

    private String buildInvitationBody(String orgName, String token) {
        return "You're invited to join organization: " + orgName + "\n" +
                "Accept invitation: /auth/invite?token=" + token;
    }

    private String buildPasswordResetBody(String token) {
        return "Reset your password: /auth/reset?token=" + token;
    }
}
