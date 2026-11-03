package com.saas.e2e;

import com.saas.e2e.Platform.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static com.saas.e2e.Platform.as;
import static com.saas.e2e.Platform.db;
import static com.saas.e2e.Platform.dbUpdate;
import static com.saas.e2e.Platform.journalTypes;
import static com.saas.e2e.Platform.organizationWithAdmin;
import static com.saas.e2e.Platform.register;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;

/**
 * Forgetting a password and choosing a new one (REQ-PWD-02, REQ-PWD-03, REQ-PWD-04, REQ-PWD-08, REQ-PWD-09): the code an email
 * would carry is read from the database. The reset limits are 3 a hour per email and 10 an hour per address, so each test asks
 * as few times as it can.
 */
class PasswordResetIT {

    private static final String NEW_PASSWORD = "Another-Long-Passphrase-42";

    /** Every request comes from the gateway's address, so the 10 an hour per address would be spent by the earlier tests of the run. */
    @BeforeEach
    void startWithNoRequestsOnRecord() {
        dbUpdate("saas_global", "DELETE FROM auth.password_reset_requests");
    }

    private static void forgot(String email) {
        as(null).body(Map.of("email", email)).post("/auth/forgot-password").then().statusCode(200);
    }

    /** The newest code the email would have carried (the database is what an inbox would show). */
    private static String latestCode(String email) {
        return db("saas_global", "SELECT t.token FROM auth.password_reset_tokens t JOIN auth.users u ON u.id = t.user_id WHERE u.email = ? ORDER BY t.created_at DESC LIMIT 1", email);
    }

    private static void reset(String code, String password, int expectedStatus) {
        var response = as(null).body(Map.of("token", code, "newPassword", password)).post("/auth/reset-password");
        if (expectedStatus == 200) {
            response.then().statusCode(200);
        } else {
            response.then().statusCode(greaterThanOrEqualTo(expectedStatus));
        }
    }

    @Test
    @Tag("REQ-PWD-02")
    @Tag("REQ-PWD-04")
    @Tag("REQ-PWD-08")
    void aResetChangesThePasswordEndsEverySessionWorksOnceAndIsNotInTheTenantJournal() {
        User admin = organizationWithAdmin("Reset Co");
        var first = as(null).body(Map.of("email", admin.email, "password", Platform.PASSWORD)).post("/auth/login");
        String refreshToken = first.path("refreshToken");
        forgot(admin.email);
        String code = latestCode(admin.email);

        reset(code, NEW_PASSWORD, 200);

        as(null).body(Map.of("email", admin.email, "password", Platform.PASSWORD)).post("/auth/login").then().statusCode(greaterThanOrEqualTo(400));
        as(null).body(Map.of("email", admin.email, "password", NEW_PASSWORD)).post("/auth/login").then().statusCode(200).body("accessToken", org.hamcrest.Matchers.notNullValue());
        as(null).body(Map.of("refreshToken", refreshToken)).post("/auth/refresh").then().statusCode(greaterThanOrEqualTo(400));
        reset(code, "Yet-Another-Long-Passphrase-7", 400);

        // the journal of the organization is about what its members did there: a reset is not in it, though a later action is
        admin.api().body(Map.of("email", Platform.uniqueEmail("later"), "role", "USER")).post("/organizations/" + admin.tenantId() + "/invitations").then().statusCode(201);
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> assertThat(journalTypes(admin)).contains("INVITATION_CREATED"));
        assertThat(journalTypes(admin)).doesNotContain("PASSWORD_RESET_REQUESTED", "PASSWORD_CHANGED", "USER_REGISTERED");
    }

    @Test
    @Tag("REQ-PWD-02")
    void aNewRequestMakesTheCodesOfTheEarlierOnesUseless() {
        User person = register("forgetful");
        forgot(person.email);
        String old = latestCode(person.email);
        forgot(person.email);
        String fresh = latestCode(person.email);

        assertThat(fresh).isNotEqualTo(old);
        reset(old, NEW_PASSWORD, 400);
        reset(fresh, NEW_PASSWORD, 200);
    }

    @Test
    @Tag("REQ-PWD-03")
    void theAnswerDoesNotSayWhetherTheAccountExists() {
        User person = register("known");

        var known = as(null).body(Map.of("email", person.email)).post("/auth/forgot-password");
        var unknown = as(null).body(Map.of("email", Platform.uniqueEmail("nobody"))).post("/auth/forgot-password");

        assertThat(unknown.statusCode()).isEqualTo(known.statusCode());
        assertThat(unknown.asString()).isEqualTo(known.asString());
        assertThat(db("saas_global", "SELECT COUNT(*)::text FROM auth.users WHERE email = ?", "nobody")).isEqualTo("0");
    }

    @Test
    @Tag("REQ-PWD-06")
    void aPasswordThatIsTooShortIsRefusedAtRegistrationAndAtReset() {
        as(null).body(Map.of("email", Platform.uniqueEmail("short"), "password", "Sh0rt-pw")).post("/auth/register").then().statusCode(400);
        User person = register("policy");
        forgot(person.email);

        reset(latestCode(person.email), "tooshort", 400);
    }
}
