package com.saas.e2e;

import com.saas.e2e.Platform.User;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;

import static com.saas.e2e.Platform.invitedMember;
import static com.saas.e2e.Platform.journalTypes;
import static com.saas.e2e.Platform.organizationWithAdmin;
import static com.saas.e2e.Platform.unreadTypes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * A leave request from a member, a decision by the administrator, and what each of them is told and can see afterwards
 * (REQ-MOD-01, REQ-LEAVE-01, REQ-LEAVE-03, REQ-LEAVE-05, REQ-LEAVE-06, REQ-LEAVE-07, REQ-NOTIF-03, REQ-MON-01). The notifications
 * travel from organization-service through Kafka to notification-service, so they are waited for, not assumed.
 */
class LeaveFlowIT {

    private static LocalDate monday() {
        return LocalDate.now(ZoneId.of("Europe/Bucharest")).with(TemporalAdjusters.next(DayOfWeek.MONDAY)).plusWeeks(2);
    }

    private static Map<String, Object> request(LocalDate first, LocalDate last, String reason) {
        return Map.of("type", "ODIHNA", "startDate", first.toString(), "endDate", last.toString(), "reason", reason);
    }

    @Test
    @Tag("REQ-MOD-01")
    void aModuleThatIsOffDoesNotExistForTheMembersAndTheAdminTurnsItOn() {
        User admin = organizationWithAdmin("Modules Co");
        User member = invitedMember(admin, "USER");
        String leave = "/organizations/" + admin.tenantId() + "/leave";

        member.api().get(leave + "/balance").then().statusCode(404);
        member.api().body(Map.of("enabled", true)).put("/organizations/" + admin.tenantId() + "/modules/LEAVE").then().statusCode(403);

        admin.api().body(Map.of("enabled", true)).put("/organizations/" + admin.tenantId() + "/modules/LEAVE").then().statusCode(200);
        member.api().get(leave + "/balance").then().statusCode(200);
        assertThat(admin.api().get("/organizations/" + admin.tenantId() + "/modules").jsonPath().getBoolean("modules.LEAVE")).isTrue();
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> assertThat(journalTypes(admin)).contains("MODULE_CHANGED"));
    }

    @Test
    @Tag("REQ-LEAVE-01")
    @Tag("REQ-LEAVE-03")
    @Tag("REQ-LEAVE-05")
    @Tag("REQ-LEAVE-06")
    @Tag("REQ-LEAVE-07")
    @Tag("REQ-NOTIF-03")
    void aRequestIsDecidedByTheAdministratorAndEachPartyIsToldAndSeesTheResult() {
        User admin = organizationWithAdmin("Leave Co");
        User member = invitedMember(admin, "USER");
        String base = "/organizations/" + admin.tenantId();
        admin.api().body(Map.of("enabled", true)).put(base + "/modules/LEAVE").then().statusCode(200);
        LocalDate first = monday();
        LocalDate last = first.plusDays(2);

        var created = member.api().body(request(first, last, "family trip")).post(base + "/leave/requests");
        created.then().statusCode(201);
        assertThat(created.<String>path("status")).isEqualTo("PENDING");
        assertThat(created.<Integer>path("days")).isEqualTo(3);
        assertThat(member.api().get(base + "/leave/balance").jsonPath().getInt("pending")).isEqualTo(3);

        // the administrator is told; the requester is not told about their own request
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> assertThat(unreadTypes(admin)).contains("LEAVE_REQUESTED"));
        assertThat(unreadTypes(member)).doesNotContain("LEAVE_REQUESTED");
        // the reason is for the people who decide
        assertThat(admin.api().queryParam("scope", "ALL").get(base + "/leave/requests").jsonPath().getList("items.reason", String.class)).contains("family trip");
        member.api().queryParam("scope", "ALL").get(base + "/leave/requests").then().statusCode(403);
        member.api().body(Map.of("comment", "ok")).post(base + "/leave/requests/" + created.path("id") + "/approve").then().statusCode(403);

        admin.api().body(Map.of("comment", "enjoy")).post(base + "/leave/requests/" + created.path("id") + "/approve").then().statusCode(200);

        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> assertThat(unreadTypes(member)).contains("LEAVE_APPROVED"));
        assertThat(member.api().get(base + "/leave/balance").jsonPath().getInt("approved")).isEqualTo(3);
        // the team calendar says who is away and when, and nothing about why
        var calendar = admin.api().queryParam("from", first.toString()).queryParam("to", last.toString()).get(base + "/leave/calendar");
        calendar.then().statusCode(200);
        assertThat(calendar.jsonPath().getList("userId", String.class)).contains(member.id().toString());
        assertThat(calendar.asString()).doesNotContain("family trip").doesNotContain("ODIHNA");
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> assertThat(journalTypes(admin)).contains("LEAVE_APPROVED"));
        // the decision is recorded; the reason of the request never is
        assertThat(admin.api().body(Map.of("query", "query { activityLog(size: 100) { items { metadata } } }")).post("/notifications/graphql").asString())
                .doesNotContain("family trip");
    }

    @Test
    @Tag("REQ-LEAVE-04")
    void aRequestThatIsCancelledGivesItsDaysBack() {
        User admin = organizationWithAdmin("Cancel Co");
        User member = invitedMember(admin, "USER");
        String base = "/organizations/" + admin.tenantId();
        admin.api().body(Map.of("enabled", true)).put(base + "/modules/LEAVE").then().statusCode(200);
        LocalDate first = monday().plusWeeks(1);

        var created = member.api().body(request(first, first.plusDays(1), "x")).post(base + "/leave/requests");
        created.then().statusCode(201);
        int available = member.api().get(base + "/leave/balance").jsonPath().getInt("available");

        member.api().post(base + "/leave/requests/" + created.path("id") + "/cancel").then().statusCode(200);

        assertThat(member.api().get(base + "/leave/balance").jsonPath().getInt("available")).isEqualTo(available + 2);
    }

    @Test
    @Tag("REQ-LEAVE-08")
    @Tag("REQ-DATA-01")
    void whenAMemberIsRemovedTheirRequestsAreGoneButTheAccountIsNot() {
        User admin = organizationWithAdmin("Offboard Co");
        User member = invitedMember(admin, "USER");
        String base = "/organizations/" + admin.tenantId();
        admin.api().body(Map.of("enabled", true)).put(base + "/modules/LEAVE").then().statusCode(200);
        member.api().body(request(monday().plusWeeks(2), monday().plusWeeks(2).plusDays(1), "gone soon")).post(base + "/leave/requests").then().statusCode(201);
        assertThat(Platform.db("saas_shard_1", "SELECT COUNT(*)::text FROM org.leave_requests WHERE tenant_id = ?::uuid", admin.tenantId().toString())).isEqualTo("1");

        admin.api().delete(base + "/members/" + member.id()).then().statusCode(204);

        assertThat(Platform.db("saas_shard_1", "SELECT COUNT(*)::text FROM org.leave_requests WHERE tenant_id = ?::uuid", admin.tenantId().toString())).isEqualTo("0");
        assertThat(Platform.db("saas_global", "SELECT COUNT(*)::text FROM auth.users WHERE email = ?", member.email)).as("the identity is untouched").isEqualTo("1");
        assertThat(List.of(member.id())).isNotEmpty();
    }
}
