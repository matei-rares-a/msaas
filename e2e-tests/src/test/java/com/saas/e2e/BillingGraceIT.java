package com.saas.e2e;

import com.saas.e2e.Platform.User;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static com.saas.e2e.Platform.db;
import static com.saas.e2e.Platform.dbUpdate;
import static com.saas.e2e.Platform.organizationWithAdmin;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Billing and the rest of the platform agree on what a tenant may do (REQ-BILL-06, REQ-BILL-04): a trial that runs out is
 * charged, the payment fails, the organization goes read-only for the grace period and, when that is over, is suspended. The
 * stack's payment provider fails every charge and billing's daily jobs run every few seconds; the test moves the dates back
 * instead of waiting for them.
 */
class BillingGraceIT {

    private static void waitForStatus(User admin, String status) {
        await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofSeconds(1)).untilAsserted(() ->
                assertThat(admin.api().get("/billing/subscription").then().statusCode(200).extract().path("status").toString()).isEqualTo(status));
    }

    @Test
    @Tag("REQ-BILL-06")
    @Tag("REQ-BILL-04")
    @Tag("REQ-BILL-05")
    void anExpiredTrialIsChargedAFailedChargeMakesTheOrganizationReadOnlyAndTheEndOfTheGraceSuspendsIt() {
        User admin = organizationWithAdmin("Grace Co");
        String tenant = admin.tenantId().toString();
        String organization = "/organizations/" + tenant;
        assertThat(admin.api().get("/billing/subscription").path("status").toString()).isEqualTo("TRIALING");
        // a write works, so the "may it write" answer the organization service keeps is the active one
        admin.api().body(Map.of("name", "Grace Co 2")).patch(organization).then().statusCode(200);

        dbUpdate("saas_shard_1", "UPDATE billing.subscriptions SET start_date = NOW() - INTERVAL '15 days' WHERE tenant_id = ?::uuid", tenant);
        waitForStatus(admin, "PAST_DUE");

        // the grace period: the organization reads, and writes nothing, whoever asks
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofSeconds(1)).untilAsserted(() ->
                admin.api().body(Map.of("name", "Grace Co 3")).patch(organization).then().statusCode(403));
        admin.api().get(organization + "/theme").then().statusCode(200);
        admin.api().body(Map.of("paletteId", "default")).put(organization + "/theme").then().statusCode(403);
        admin.api().body(Map.of("email", Platform.uniqueEmail("blocked"), "role", "USER")).post(organization + "/invitations").then().statusCode(403);
        assertThat(db("saas_shard_1", "SELECT p.status FROM billing.payments p JOIN billing.invoices i ON i.id = p.invoice_id WHERE i.tenant_id = ?::uuid LIMIT 1", tenant))
                .as("every payment attempt is kept").isEqualTo("FAILED");

        dbUpdate("saas_shard_1", "UPDATE billing.subscriptions SET past_due_since = NOW() - INTERVAL '8 days' WHERE tenant_id = ?::uuid", tenant);
        waitForStatus(admin, "SUSPENDED");

        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofSeconds(1)).untilAsserted(() ->
                admin.api().body(Map.of("name", "Grace Co 4")).patch(organization).then().statusCode(403));
    }

    @Test
    @Tag("REQ-BILL-04")
    void anOrganizationInItsGracePeriodKeepsItsDataForItsMembers() {
        User admin = organizationWithAdmin("Readable Co");
        String tenant = admin.tenantId().toString();
        dbUpdate("saas_shard_1", "UPDATE billing.subscriptions SET start_date = NOW() - INTERVAL '15 days' WHERE tenant_id = ?::uuid", tenant);
        waitForStatus(admin, "PAST_DUE");

        admin.api().get("/organizations/" + tenant + "/theme").then().statusCode(200);
        admin.api().get("/billing/invoices").then().statusCode(200);
        admin.api().get("/notifications/in-app").then().statusCode(200);
    }
}
