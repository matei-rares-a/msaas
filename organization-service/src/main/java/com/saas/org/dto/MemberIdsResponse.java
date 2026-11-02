package com.saas.org.dto;

import java.util.List;
import java.util.UUID;

/** Ids only (REQ-EVT-02 in spirit): who holds a role in a tenant, for services that address notifications. */
public record MemberIdsResponse(List<UUID> userIds) {
}
