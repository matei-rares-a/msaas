package com.saas.notification.email;

import com.saas.notification.activity.ActivityLogWriter;
import com.saas.notification.entity.EmailLog;
import com.saas.notification.repository.EmailLogRepository;
import com.saas.shared.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Delivers one email and records what happened (REQ-NOTIF-06, REQ-NOTIF-07, REQ-NOTIF-08, REQ-MON-01).
 *
 * <p>A tenant email is sent (with retries, see {@link EmailSender}), then one row of metadata goes into
 * {@code email_logs} - recipient, template, subject, status, time; never the body, which holds links with
 * tokens - and one {@code EMAIL_SENT} / {@code EMAIL_FAILED} entry into that tenant's activity journal, both
 * in one tenant transaction. The journal entry carries identifiers, the template, the subject, the status and
 * the number of attempts, but not the address: the journal is kept for months and exported, and an address in
 * it could not be anonymized when the member is offboarded. The log row's id is the command's id, so a command
 * delivered twice is recognized and skipped before anything is sent.
 *
 * <p>An identity email (password reset or confirmation) belongs to no tenant: it is sent, and nothing is
 * recorded anywhere in a tenant - not in the email log, not in a journal (REQ-PWD-08). A duplicate delivery of
 * one is not recognized (there is no tenant to hold the idempotency record) and sends the email again, which is
 * harmless: the link carries the same one-time token.
 */
class EmailDeliveryService {

    static final String EMAIL_SENT = "EMAIL_SENT";
    static final String EMAIL_FAILED = "EMAIL_FAILED";

    private static final Logger LOG = LoggerFactory.getLogger(EmailDeliveryService.class);

    private final EmailSender sender;
    private final EmailLogRepository emailLogs;
    private final ActivityLogWriter journal;
    private final TransactionTemplate transactionTemplate;

    EmailDeliveryService(EmailSender sender, EmailLogRepository emailLogs, ActivityLogWriter journal,
            PlatformTransactionManager transactionManager) {
        this.sender = sender;
        this.emailLogs = emailLogs;
        this.journal = journal;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    void deliver(OutgoingEmail email) {
        if (email.tenantId() == null) {
            deliverIdentityEmail(email);
            return;
        }
        boolean alreadyHandled = TenantContext.callWith(email.tenantId(),
                () -> transactionTemplate.execute(status -> emailLogs.existsById(email.commandId())));
        if (alreadyHandled) {
            LOG.info("Email command {} was already handled; skipping", email.commandId());
            return;
        }

        EmailSender.Result result = send(email);
        TenantContext.runWith(email.tenantId(), () -> transactionTemplate.executeWithoutResult(status -> record(email, result)));
    }

    private void deliverIdentityEmail(OutgoingEmail email) {
        EmailSender.Result result = send(email);
        if (result.delivered()) {
            LOG.info("Identity email {} delivered", email.template());
        } else {
            // no address here: this is an operating log, not a record of the user's data
            LOG.warn("Identity email {} for user {} could not be delivered after {} attempts ({})",
                    email.template(), email.recipientUserId(), result.attempts(), result.reason());
        }
    }

    private EmailSender.Result send(OutgoingEmail email) {
        String invalid = email.invalidReason();
        if (invalid != null) {
            return new EmailSender.Result(false, 0, invalid);
        }
        return sender.send(email.to(), email.subject(), email.body());
    }

    private void record(OutgoingEmail email, EmailSender.Result result) {
        String status = result.delivered() ? "SENT" : "FAILED";

        EmailLog log = new EmailLog();
        log.setId(email.commandId());
        log.setToEmail(storable(email.to(), 255));
        log.setSubject(storable(email.subject(), 255));
        log.setStatus(status);
        log.setEventType(email.template());
        log.setRecipientUserId(email.recipientUserId());
        emailLogs.save(log);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("emailLogId", email.commandId());
        metadata.put("template", email.template());
        metadata.put("subject", storable(email.subject(), 255));
        metadata.put("status", status);
        metadata.put("attempts", result.attempts());
        if (!result.delivered()) {
            metadata.put("reason", result.reason());
        }
        if (email.recipientUserId() != null) {
            metadata.put("recipientUserId", email.recipientUserId());
        }
        metadata.putAll(email.reference());
        journal.record(email.tenantId(), result.delivered() ? EMAIL_SENT : EMAIL_FAILED, null, metadata);
    }

    /** Control characters out and a length the column holds; a malformed value is stored as far as it is safe, not refused. */
    private static String storable(String value, int max) {
        if (value == null) {
            return "";
        }
        String clean = value.replaceAll("\\p{Cntrl}", " ");
        return clean.length() > max ? clean.substring(0, max) : clean;
    }
}
