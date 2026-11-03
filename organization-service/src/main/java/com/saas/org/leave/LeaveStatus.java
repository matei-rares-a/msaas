package com.saas.org.leave;

/** PENDING -> APPROVED / REJECTED / CANCELLED, and APPROVED -> CANCELLED while it has not started (REQ-LEAVE-04). */
public enum LeaveStatus {
    PENDING, APPROVED, REJECTED, CANCELLED
}
