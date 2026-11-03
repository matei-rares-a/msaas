package com.saas.org.leave;

import java.util.UUID;

/**
 * Rest-leave days of one member in one calendar year (REQ-LEAVE-03): {@code available = allocated - approved - pending}.
 * It can be negative when an ADMIN lowers the allowance below what is already taken or waiting.
 */
public record BalanceView(UUID userId, int year, int allocated, int approved, int pending, int available) {
}
