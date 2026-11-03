package com.saas.org.leave;

/** The leave policy an ADMIN sets for the organization (REQ-LEAVE-03): the yearly rest-leave days of a member without an override. */
public record PolicyView(int defaultAllowance) {
}
