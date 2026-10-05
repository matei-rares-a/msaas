package com.saas.org.dto;

/** Either the role change was applied immediately, or it is pending the target admin's acceptance (REQ-ROLE-04). */
public record RoleChangeOutcome(MemberRoleResponse applied, AdminDemotionRequestResponse pendingRequest) {

    public static RoleChangeOutcome applied(MemberRoleResponse response) {
        return new RoleChangeOutcome(response, null);
    }

    public static RoleChangeOutcome pending(AdminDemotionRequestResponse response) {
        return new RoleChangeOutcome(null, response);
    }

    public boolean isPending() {
        return pendingRequest != null;
    }
}
