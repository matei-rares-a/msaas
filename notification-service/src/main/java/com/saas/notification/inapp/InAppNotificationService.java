package com.saas.notification.inapp;

import com.saas.notification.entity.InAppNotification;
import com.saas.notification.repository.InAppNotificationRepository;
import com.saas.notification.security.Caller;
import com.saas.shared.tenancy.TenantContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * In-app notifications (REQ-NOTIF-01..05). Everything a user can do is scoped to the caller's own tenant and
 * the caller's own notifications: the tenant comes from the token ({@link Caller}), the rows are filtered by
 * tenant (Hibernate's tenant discriminator) and by RLS, and every lookup also names the user, so another
 * user's notification - or the same user's in another tenant - is simply "not found".
 *
 * <p>Notifications are created only here, called by the system's own consumers inside their tenant
 * transaction ({@link #create}); there is no way to create one from a request (REQ-NOTIF-02).
 */
public class InAppNotificationService {

    public static final int MAX_PAGE_SIZE = 100;
    static final int MAX_TITLE = 255;
    static final int MAX_MESSAGE = 2000;

    /** What a consumer asks to have created. */
    public record NewNotification(UUID userId, String eventType, String title, String message, String level) {
    }

    private final InAppNotificationRepository repository;
    private final TransactionTemplate tenantTransaction;
    private final TransactionTemplate purgeTransaction;
    private final JdbcTemplate purgeJdbc;
    private final Clock clock;

    public InAppNotificationService(InAppNotificationRepository repository, PlatformTransactionManager transactionManager,
            DataSource dataSource, Clock clock) {
        this.repository = repository;
        this.tenantTransaction = new TransactionTemplate(transactionManager);
        this.purgeTransaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        this.purgeJdbc = new JdbcTemplate(dataSource);
        this.clock = clock;
    }

    /** Creates a notification for a user of the tenant whose transaction the caller is in (the tenant context is already set). */
    public void create(NewNotification notification) {
        InAppNotification row = new InAppNotification();
        row.setId(UUID.randomUUID());
        row.setUserId(notification.userId());
        row.setEventType(notification.eventType());
        row.setTitle(truncate(notification.title(), MAX_TITLE));
        row.setMessage(truncate(notification.message(), MAX_MESSAGE));
        row.setLevel(notification.level());
        row.setRead(false);
        row.setCreatedAt(clock.instant());
        repository.save(row);
    }

    public NotificationPage list(Caller caller, int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("page must be at least 0 and size between 1 and " + MAX_PAGE_SIZE);
        }
        return inTenant(caller, () -> {
            Page<InAppNotification> found = repository.findAllByUserId(caller.userId(),
                    PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id"))));
            return new NotificationPage(found.getContent().stream().map(InAppNotificationService::view).toList(), page, size,
                    found.getTotalElements(), repository.countByUserIdAndReadFalse(caller.userId()));
        });
    }

    public long unreadCount(Caller caller) {
        return inTenant(caller, () -> repository.countByUserIdAndReadFalse(caller.userId()));
    }

    /** Idempotent: reading one that is already read changes nothing. */
    public NotificationView markRead(Caller caller, UUID notificationId) {
        return inTenant(caller, () -> {
            InAppNotification row = repository.findByIdAndUserId(notificationId, caller.userId())
                    .orElseThrow(NotificationNotFoundException::new);
            if (!row.isRead()) {
                row.setRead(true);
                repository.save(row);
            }
            return view(row);
        });
    }

    /** Marks every unread notification of the caller in the caller's tenant as read; @return how many changed. */
    public int markAllRead(Caller caller) {
        return inTenant(caller, () -> {
            List<InAppNotification> unread = repository.findAllByUserIdAndReadFalse(caller.userId());
            unread.forEach(row -> row.setRead(true));
            repository.saveAll(unread);
            return unread.size();
        });
    }

    /**
     * Deletes every notification created before {@code cutoff}, in every tenant (REQ-NOTIF-05). The sweep has no
     * tenant, so it tells the database which rows it may reach through {@code app.purge_before} (see the
     * {@code retention_purge_*} policies of the migration), for this transaction only.
     */
    public int purgeOlderThan(Instant cutoff) {
        Integer deleted = purgeTransaction.execute(status -> {
            purgeJdbc.queryForObject("SELECT set_config('app.purge_before', ?, true)", String.class, cutoff.toString());
            return purgeJdbc.update("DELETE FROM notif.in_app_notifications WHERE created_at < ?", Timestamp.from(cutoff));
        });
        return deleted == null ? 0 : deleted;
    }

    private <T> T inTenant(Caller caller, java.util.function.Supplier<T> action) {
        return TenantContext.callWith(caller.tenantId(), () -> tenantTransaction.execute(status -> action.get()));
    }

    private static NotificationView view(InAppNotification row) {
        return new NotificationView(row.getId(), row.getEventType(), row.getTitle(), row.getMessage(), row.getLevel(),
                row.isRead(), row.getCreatedAt());
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
