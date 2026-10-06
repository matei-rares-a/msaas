package com.saas.org.dto;

/** A single tenant's display name (REQ-AUTH-05: UI info in the access token). */
public record TenantNameResponse(String name) {
}
