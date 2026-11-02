package com.saas.notification.graphql;

import com.saas.notification.activity.ActivityLogFilterParser;
import com.saas.notification.activity.ActivityLogPage;
import com.saas.notification.activity.ActivityLogReader;
import com.saas.notification.activity.ActivityLogRecord;
import com.saas.notification.security.Caller;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.ContextValue;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.stereotype.Controller;

import java.util.Map;
import java.util.UUID;

/**
 * The journal's GraphQL read API (REQ-MON-02, REQ-MON-04, REQ-MON-08, REQ-MGR-04). The tenant is the
 * caller's own, taken from the gateway-set identity and never from the query; only ADMIN and MANAGER get
 * in; the page size is capped; and nothing here writes.
 */
@Controller
public class ActivityLogGraphQlController {

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
        return reader.read(callerTenant, ActivityLogFilterParser.parse(filter, false), pageNumber, pageSize);
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
        Caller caller = Caller.authenticate(userId, tenantId, role);
        caller.requireRole("only an ADMIN or a MANAGER can read the activity journal", "ADMIN", "MANAGER");
        return caller.tenantId();
    }
}
