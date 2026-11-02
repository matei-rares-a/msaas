package com.saas.notification.graphql;

import com.saas.notification.activity.ActivityLogPage;
import com.saas.notification.activity.ActivityLogQuery;
import com.saas.notification.activity.ActivityLogReader;
import com.saas.notification.activity.ActivityLogRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.ContextValue;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.stereotype.Controller;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The journal's GraphQL read API (REQ-MON-02, REQ-MON-04, REQ-MON-08, REQ-MGR-04). The tenant is the
 * caller's own, taken from the gateway-set identity and never from the query; only ADMIN and MANAGER get
 * in; the page size is capped; and nothing here writes.
 */
@Controller
public class ActivityLogGraphQlController {

    private static final Pattern EVENT_TYPE = Pattern.compile("^[A-Z0-9_]{1,100}$");

    private final ActivityLogReader reader;
    private final int maxPageSize;

    public ActivityLogGraphQlController(ActivityLogReader reader, @Value("${saas.graphql.max-page-size}") int maxPageSize) {
        this.reader = reader;
        this.maxPageSize = maxPageSize;
    }

    @QueryMapping
    public ActivityLogPage activityLog(@Argument Map<String, Object> filter, @Argument Integer page, @Argument Integer size,
            @ContextValue(name = CallerContextInterceptor.USER_ID, required = false) String userId,
            @ContextValue(name = CallerContextInterceptor.TENANT_ID, required = false) String tenantId,
            @ContextValue(name = CallerContextInterceptor.ROLE, required = false) String role) {
        UUID callerTenant = authorize(userId, tenantId, role);
        int pageNumber = page == null ? 0 : page;
        int pageSize = size == null ? 20 : size;
        if (pageNumber < 0) {
            throw new IllegalArgumentException("page must be at least 0");
        }
        if (pageSize < 1 || pageSize > maxPageSize) {
            throw new IllegalArgumentException("size must be between 1 and " + maxPageSize);
        }
        return reader.read(callerTenant, parse(filter), pageNumber, pageSize);
    }

    /** Same representation for every field a client can ask for; the stored values are already safe. */
    @SchemaMapping(typeName = "ActivityLogPage", field = "totalPages")
    public int totalPages(ActivityLogPage page) {
        return page.totalPages();
    }

    @SchemaMapping(typeName = "ActivityLogPage", field = "totalItems")
    public int totalItems(ActivityLogPage page) {
        return (int) Math.min(page.totalItems(), Integer.MAX_VALUE);
    }

    @SchemaMapping(typeName = "ActivityLogEntry", field = "createdAt")
    public String createdAt(ActivityLogRecord entry) {
        return entry.createdAt().toString();
    }

    @SchemaMapping(typeName = "ActivityLogEntry", field = "metadata")
    public String metadata(ActivityLogRecord entry) {
        return entry.metadataJson();
    }

    @SchemaMapping(typeName = "ActivityLogEntry", field = "actorUserId")
    public String actorUserId(ActivityLogRecord entry) {
        return entry.actorUserId() == null ? null : entry.actorUserId().toString();
    }

    /** The caller must be authenticated (identity from the gateway) and ADMIN or MANAGER (REQ-MON-02). */
    private static UUID authorize(String userId, String tenantId, String role) {
        if (userId == null || tenantId == null || role == null) {
            throw new AuthenticationRequiredException("authentication is required");
        }
        UUID tenant;
        try {
            UUID.fromString(userId);
            tenant = UUID.fromString(tenantId);
        } catch (IllegalArgumentException e) {
            throw new AuthenticationRequiredException("authentication is required");
        }
        if (!"ADMIN".equals(role) && !"MANAGER".equals(role)) {
            throw new SecurityException("only an ADMIN or a MANAGER can read the activity journal");
        }
        return tenant;
    }

    @SuppressWarnings("unchecked")
    private static ActivityLogQuery parse(Map<String, Object> filter) {
        if (filter == null) {
            return ActivityLogQuery.none();
        }
        Instant from = instant(filter.get("from"), "from");
        Instant to = instant(filter.get("to"), "to");
        if (from != null && to != null && !from.isBefore(to)) {
            throw new IllegalArgumentException("from must be before to");
        }
        List<String> types = filter.get("eventTypes") == null ? List.of() : (List<String>) filter.get("eventTypes");
        if (types.size() > ActivityLogQuery.MAX_EVENT_TYPES) {
            throw new IllegalArgumentException("at most " + ActivityLogQuery.MAX_EVENT_TYPES + " event types can be filtered at once");
        }
        for (String type : types) {
            if (!EVENT_TYPE.matcher(type).matches()) {
                throw new IllegalArgumentException("not a valid event type: " + type.replaceAll("[^\\x20-\\x7E]", "?"));
            }
        }
        UUID actor = null;
        if (filter.get("actorUserId") != null) {
            try {
                actor = UUID.fromString((String) filter.get("actorUserId"));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("actorUserId must be a UUID");
            }
        }
        return new ActivityLogQuery(from, to, types, actor);
    }

    private static Instant instant(Object value, String name) {
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse((String) value);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(name + " must be an ISO-8601 instant such as 2026-11-02T10:00:00Z");
        }
    }
}
