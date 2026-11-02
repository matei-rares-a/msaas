package com.saas.notification.inapp;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Who in a tenant holds a role, asked of the service that owns memberships (organization-service) at the time a
 * notification is addressed, so this service keeps no copy of roles that could drift (REQ-NOTIF-03: "to the
 * admins and managers"). Ids only. A lookup that fails throws: the event is then retried and, if it keeps
 * failing, dead-lettered - a notification is never silently dropped because the answer was missing.
 */
public interface MemberDirectory {

    List<UUID> memberIdsWithRoles(UUID tenantId, Set<String> roles);
}
