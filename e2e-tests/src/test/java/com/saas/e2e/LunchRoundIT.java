package com.saas.e2e;

import com.saas.e2e.Platform.User;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static com.saas.e2e.Platform.invitedMember;
import static com.saas.e2e.Platform.organizationWithAdmin;
import static com.saas.e2e.Platform.unreadTypes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.awaitility.Awaitility.await;

/**
 * A weekly lunch round from joining to the groups (REQ-LUNCH-01..05, REQ-MOD-01, REQ-NOTIF-03). The closing time is set two
 * minutes ahead, in the organization's time zone, so the real job forms the groups while the test waits; one organization has
 * two participants and another has one, so both outcomes of a round are seen in the same wait.
 */
class LunchRoundIT {

    private static final ZoneId ZONE = ZoneId.of("Europe/Bucharest");

    private static void configureToCloseSoon(User admin) {
        ZonedDateTime closing = ZonedDateTime.now(ZONE).plusMinutes(2).truncatedTo(ChronoUnit.MINUTES);
        admin.api().body(Map.of("day", closing.getDayOfWeek().name(), "cutoff", closing.format(DateTimeFormatter.ofPattern("HH:mm")), "groupSize", 2))
                .put("/organizations/" + admin.tenantId() + "/lunch/config").then().statusCode(200);
    }

    @Test
    @Tag("REQ-LUNCH-01")
    @Tag("REQ-LUNCH-02")
    @Tag("REQ-LUNCH-03")
    @Tag("REQ-LUNCH-04")
    @Tag("REQ-LUNCH-05")
    @Tag("REQ-MOD-01")
    @Tag("REQ-NOTIF-03")
    void roundsCloseAtTheirTimeAndFormGroupsOrAreCancelledAndTheMembersAreTold() {
        // a closing time just after midnight would belong to the next day, and with it the next week
        assumeThat(ZonedDateTime.now(ZONE).plusMinutes(3).toLocalDate()).isEqualTo(ZonedDateTime.now(ZONE).toLocalDate());

        User admin = organizationWithAdmin("Lunch Co");
        User member = invitedMember(admin, "USER");
        User alone = organizationWithAdmin("Lonely Lunch Co");
        String base = "/organizations/" + admin.tenantId() + "/lunch";
        String lonelyBase = "/organizations/" + alone.tenantId() + "/lunch";

        // off until the administrator turns it on
        member.api().get(base + "/rounds/current").then().statusCode(404);
        admin.api().body(Map.of("enabled", true)).put("/organizations/" + admin.tenantId() + "/modules/LUNCH_ROULETTE").then().statusCode(200);
        alone.api().body(Map.of("enabled", true)).put("/organizations/" + alone.tenantId() + "/modules/LUNCH_ROULETTE").then().statusCode(200);
        // only a manager or an administrator configures
        member.api().body(Map.of("day", "FRIDAY", "cutoff", "10:00", "groupSize", 2)).put(base + "/config").then().statusCode(403);
        configureToCloseSoon(admin);
        configureToCloseSoon(alone);

        member.api().put(base + "/rounds/current/enrollment").then().statusCode(200);
        admin.api().put(base + "/rounds/current/enrollment").then().statusCode(200);
        alone.api().put(lonelyBase + "/rounds/current/enrollment").then().statusCode(200);
        assertThat(member.api().get(base + "/rounds/current").jsonPath().getInt("participantCount")).isEqualTo(2);
        assertThat(member.api().get(base + "/rounds/current").jsonPath().getBoolean("enrolled")).isTrue();

        // the job runs every minute: the round of two is formed, the round of one is cancelled
        await().atMost(Duration.ofMinutes(4)).pollInterval(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(admin.api().get(base + "/rounds").jsonPath().getList("items.status", String.class)).contains("FORMED");
            assertThat(alone.api().get(lonelyBase + "/rounds").jsonPath().getList("items.status", String.class)).contains("CANCELLED");
        });

        String roundId = admin.api().get(base + "/rounds").jsonPath().getString("items.find { it.status == 'FORMED' }.id");
        var asMember = member.api().get(base + "/rounds/" + roundId);
        asMember.then().statusCode(200);
        assertThat(asMember.jsonPath().getList("myGroup", String.class)).containsExactlyInAnyOrder(member.id().toString(), admin.id().toString());
        assertThat(asMember.jsonPath().getList("groups")).as("a participant sees their own group only").isNullOrEmpty();
        var asAdmin = admin.api().get(base + "/rounds/" + roundId);
        assertThat(asAdmin.jsonPath().getList("groups")).hasSize(1);
        assertThat(alone.api().get(lonelyBase + "/rounds").jsonPath().getString("items[0].cancelReason")).isEqualTo("TOO_FEW_PARTICIPANTS");

        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            assertThat(unreadTypes(member)).contains("LUNCH_GROUPS_FORMED");
            assertThat(unreadTypes(admin)).contains("LUNCH_GROUPS_FORMED");
            assertThat(unreadTypes(alone)).contains("LUNCH_ROUND_CANCELLED");
        });
        // a round is not part of the journal of the organization (REQ-LUNCH-05)
        assertThat(Platform.journalTypes(admin)).noneMatch(type -> type.startsWith("LUNCH"));
        assertThat(List.of(roundId)).isNotEmpty();
    }

    @Test
    @Tag("REQ-LUNCH-02")
    void aManagerCancelsTheRoundBeforeItsTimeAndNobodyJoinsAfterwards() {
        User admin = organizationWithAdmin("Cancel Lunch Co");
        User manager = invitedMember(admin, "MANAGER");
        User member = invitedMember(admin, "USER");
        String base = "/organizations/" + admin.tenantId() + "/lunch";
        admin.api().body(Map.of("enabled", true)).put("/organizations/" + admin.tenantId() + "/modules/LUNCH_ROULETTE").then().statusCode(200);
        member.api().put(base + "/rounds/current/enrollment").then().statusCode(200);

        member.api().post(base + "/rounds/current/cancel").then().statusCode(403);
        manager.api().post(base + "/rounds/current/cancel").then().statusCode(200).body("status", org.hamcrest.Matchers.equalTo("CANCELLED"));

        admin.api().put(base + "/rounds/current/enrollment").then().statusCode(409);
    }
}
