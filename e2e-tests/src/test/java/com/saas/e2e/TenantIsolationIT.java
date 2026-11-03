package com.saas.e2e;

import com.saas.e2e.Platform.User;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.saas.e2e.Platform.as;
import static com.saas.e2e.Platform.journalTypes;
import static com.saas.e2e.Platform.organizationWithAdmin;
import static com.saas.e2e.Platform.invitedMember;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;

/**
 * One tenant never reaches another's data, through the whole stack: the gateway, the services and the database (REQ-DATA-03,
 * REQ-AUTH-04, REQ-AUTH-05). Two organizations with an ADMIN each, and a member who belongs to both.
 */
class TenantIsolationIT {

    @Test
    @Tag("REQ-DATA-03")
    @Tag("REQ-AUTH-05")
    void aTokenOfOneOrganizationCannotReadOrChangeAnother() {
        User a = organizationWithAdmin("Isolation A");
        User b = organizationWithAdmin("Isolation B");

        a.api().get("/organizations/" + a.tenantId() + "/theme").then().statusCode(200);
        a.api().get("/organizations/" + b.tenantId() + "/theme").then().statusCode(403);
        a.api().body(Map.of("paletteId", "default")).put("/organizations/" + b.tenantId() + "/theme").then().statusCode(403);
        a.api().get("/organizations/" + b.tenantId() + "/invitations").then().statusCode(403);
        a.api().body(Map.of("name", "Hijacked")).patch("/organizations/" + b.tenantId()).then().statusCode(403);
    }

    @Test
    @Tag("REQ-AUTH-05")
    void theTenantComesFromTheTokenNeverFromAHeaderTheClientSends() {
        User a = organizationWithAdmin("Header A");
        User b = organizationWithAdmin("Header B");

        // the gateway overwrites the context headers with what the token says
        a.api().header("X-Tenant-Id", b.tenantId().toString()).header("X-User-Id", b.id().toString())
                .get("/organizations/" + b.tenantId() + "/theme").then().statusCode(403);
        a.api().header("X-Tenant-Id", b.tenantId().toString()).header("X-Role", "ADMIN")
                .get("/organizations/" + a.tenantId() + "/theme").then().statusCode(200);
    }

    @Test
    @Tag("REQ-DATA-03")
    void whatHappensInOneOrganizationNeverShowsInTheJournalOrTheListsOfAnother() {
        User a = organizationWithAdmin("Journal A");
        User b = organizationWithAdmin("Journal B");
        String email = Platform.uniqueEmail("invited");

        a.api().body(Map.of("email", email, "role", "USER")).post("/organizations/" + a.tenantId() + "/invitations").then().statusCode(201);

        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> assertThat(journalTypes(a)).contains("INVITATION_CREATED"));
        assertThat(journalTypes(b)).doesNotContain("INVITATION_CREATED");
        assertThat(b.api().get("/organizations/" + b.tenantId() + "/invitations").jsonPath().getList("email", String.class)).doesNotContain(email);
        assertThat(b.api().get("/billing/subscription").jsonPath().getString("id")).as("each organization sees its own subscription").isNotEqualTo(a.api().get("/billing/subscription").jsonPath().getString("id"));
    }

    @Test
    @Tag("REQ-AUTH-04")
    @Tag("REQ-AUTH-02")
    void aPersonInTwoOrganizationsChoosesOneAtLoginAndHoldsOneTokenPerOrganization() {
        User adminA = organizationWithAdmin("Both A");
        User adminB = organizationWithAdmin("Both B");
        User member = invitedMember(adminA, "USER");
        String tokenA = member.token();
        adminB.api().body(Map.of("email", member.email, "role", "MANAGER")).post("/organizations/" + adminB.tenantId() + "/invitations").then().statusCode(201);
        String code = Platform.db("saas_shard_1", "SELECT token FROM org.invitations WHERE email = ? AND tenant_id = ?::uuid", member.email, adminB.tenantId().toString());
        as(tokenA).body(Map.of("token", code)).post("/invitations/accept").then().statusCode(200);

        // two organizations: the login asks which one, and only then is a token made
        var login = as(null).body(Map.of("email", member.email, "password", Platform.PASSWORD)).post("/auth/login");
        login.then().statusCode(200);
        assertThat((Object) login.path("accessToken")).isNull();
        assertThat(login.<String>path("loginSessionId")).isNotBlank();
        assertThat(login.jsonPath().getList("tenants.tenantId", String.class)).containsExactlyInAnyOrder(adminA.tenantId().toString(), adminB.tenantId().toString());

        User inA = member.signInTo(adminA.tenantId());
        String tokenForA = inA.token();
        User inB = member.signInTo(adminB.tenantId());
        String tokenForB = inB.token();

        assertThat(tokenForA).isNotEqualTo(tokenForB);
        as(tokenForA).get("/organizations/" + adminA.tenantId() + "/theme").then().statusCode(200);
        as(tokenForA).get("/organizations/" + adminB.tenantId() + "/theme").then().statusCode(403);
        as(tokenForB).get("/organizations/" + adminB.tenantId() + "/theme").then().statusCode(200);
        as(tokenForB).get("/organizations/" + adminA.tenantId() + "/theme").then().statusCode(403);
        // a login session works once
        as(null).body(Map.of("loginSessionId", login.path("loginSessionId"), "tenantId", adminA.tenantId().toString())).post("/auth/login/tenant").then().statusCode(200);
        as(null).body(Map.of("loginSessionId", login.path("loginSessionId"), "tenantId", adminB.tenantId().toString())).post("/auth/login/tenant").then().statusCode(org.hamcrest.Matchers.greaterThanOrEqualTo(400));
    }
}