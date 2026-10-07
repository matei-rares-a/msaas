package com.saas.billing.dto;

/** Tenant-wide write access (REQ-BILL-04): ACTIVE, READ_ONLY (PAST_DUE grace period) or SUSPENDED. */
public record AccessStatusResponse(String status) {
}
