package com.saas.e2e;

import com.saas.e2e.Platform.User;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static com.saas.e2e.Platform.as;
import static com.saas.e2e.Platform.db;
import static com.saas.e2e.Platform.identityToken;
import static com.saas.e2e.Platform.journalTypes;
import static com.saas.e2e.Platform.organizationWithAdmin;
import static com.saas.e2e.Platform.register;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;

/**
 * Invitation, accept, login: the way a person gets into an organization (REQ-AUTH-03, REQ-ROLE-02, REQ-MGR-01, REQ-INV-02,
 * REQ-BILL-03), and what the email about it leaves behind (REQ-NOTIF-06, REQ-NOTIF-07).
 */
class InvitationFlowIT {

    private static String codeOf(User admin, String email) {
        return db("saas_shard_1", "SELECT token FROM org.invitations WHERE email = ? AND tenant_id = ?::uuid", email, admin.tenantId().toString());
    }

    @Test
    @Tag("REQ-AUTH-03")
    @Tag("REQ-ROLE-02")
    void aNewAccountHasNoOrganizationAnInvitationGivesItOneAndTheNextLoginIsIntoIt() {
        User admin = organizationWithAdmin("Invite Co");
        User person = register("person");

        // no organization yet: a list that is empty and an identity token that opens only two doors
        var login = as(null).body(Map.of("email", person.email, "password", Platform.PASSWORD)).post("/auth/login");
        login.then().statusCode(200);
        assertThat(login.jsonPath().getList("tenants")).isEmpty();
        String identity = login.path("identityToken");
        assertThat(identity).isNotBlank();
        as(identity).get("/organizations/" + admin.tenantId() + "/theme").then().statusCode(401);
        as(identity).get("/notifications/in-app").then().statusCode(401);

        admin.api().body(Map.of("email", person.email, "role", "USER")).post("/organizations/" + admin.tenantId() + "/invitations").then().statusCode(201);
        as(identity).body(Map.of("token", codeOf(admin, person.email))).post("/invitations/accept").then().statusCode(200)
                .body("tenantId", org.hamcrest.Matchers.equalTo(admin.tenantId().toString())).body("role", org.hamcrest.Matchers.equalTo("USER"));

        User inside = person.signIn();
        assertThat(inside.tenantId()).isEqualTo(admin.tenantId());
        inside.api().get("/organizations/" + admin.tenantId() + "/theme").then().statusCode(200);
        // an invitation works once
        as(identityToken(register("late"))).body(Map.of("token", codeOf(admin, person.email))).post("/invitations/accept").then().statusCode(greaterThanOrEqualTo(400));
    }

    @Test
    @Tag("REQ-MGR-01")
    @Tag("REQ-ROLE-02")
    void anAdminInvitesWithAnyRoleAManagerOnlyWithUserAndAUserNotAtAll() {
        User admin = organizationWithAdmin("Roles Co");
        User manager = Platform.invitedMember(admin, "MANAGER");
        User user = Platform.invitedMember(admin, "USER");
        String path = "/organizations/" + admin.tenantId() + "/invitations";

        admin.api().body(Map.of("email", Platform.uniqueEmail("a"), "role", "ADMIN")).post(path).then().statusCode(201);
        manager.api().body(Map.of("email", Platform.uniqueEmail("b"), "role", "USER")).post(path).then().statusCode(201);
        manager.api().body(Map.of("email", Platform.uniqueEmail("c"), "role", "ADMIN")).post(path).then().statusCode(403);
        manager.api().body(Map.of("email", Platform.uniqueEmail("d"), "role", "MANAGER")).post(path).then().statusCode(403);
        user.api().body(Map.of("email", Platform.uniqueEmail("e"), "role", "USER")).post(path).then().statusCode(403);
    }

    @Test
    @Tag("REQ-INV-02")
    void anInvitationThatWasRevokedCannotBeAccepted() {
        User admin = organizationWithAdmin("Revoke Co");
        User person = register("person");
        var created = admin.api().body(Map.of("email", person.email, "role", "USER")).post("/organizations/" + admin.tenantId() + "/invitations");
        created.then().statusCode(201);

        admin.api().delete("/organizations/" + admin.tenantId() + "/invitations/" + created.path("id")).then().statusCode(204);

        as(identityToken(person)).body(Map.of("token", codeOf(admin, person.email))).post("/invitations/accept").then().statusCode(greaterThanOrEqualTo(400));
    }

    @Test
    @Tag("REQ-BILL-03")
    void theNumberOfMembersIsLimitedByThePlanOfTheOrganization() {
        User admin = Platform.organizationWithPlan("Plan Co", Platform.plan(2), 2);
        String path = "/organizations/" + admin.tenantId() + "/invitations";

        // the admin holds one place, the first invitation the second, and there is no third
        admin.api().body(Map.of("email", Platform.uniqueEmail("fits"), "role", "USER")).post(path).then().statusCode(201);
        admin.api().body(Map.of("email", Platform.uniqueEmail("over"), "role", "USER")).post(path).then().statusCode(409);
    }

    @Test
    @Tag("REQ-NOTIF-06")
    @Tag("REQ-NOTIF-07")
    @Tag("REQ-MON-01")
    void theEmailAboutAnInvitationIsLoggedAsMetadataAndInTheTenantJournal() {
        User admin = organizationWithAdmin("Email Co");
        String email = Platform.uniqueEmail("mailed");

        admin.api().body(Map.of("email", email, "role", "USER")).post("/organizations/" + admin.tenantId() + "/invitations").then().statusCode(201);

        await().atMost(Duration.ofSeconds(90)).untilAsserted(() -> {
            assertThat(db("saas_shard_1", "SELECT status FROM notif.email_logs WHERE to_email = ? AND tenant_id = ?::uuid", email, admin.tenantId().toString())).isEqualTo("SENT");
            assertThat(journalTypes(admin)).contains("EMAIL_SENT");
        });
        // only metadata is kept: no body, no code
        assertThat(db("saas_shard_1", "SELECT row_to_json(e)::text FROM notif.email_logs e WHERE to_email = ?", email))
                .doesNotContain(codeOf(admin, email));
    }
}
