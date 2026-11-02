package com.saas.shared.events;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** What the activity journal may record (REQ-MON-01): a fixed, reviewed list of sensitive actions. */
@Tag("REQ-MON-01")
class ActivityEventCatalogTest {

    @Test
    void theCatalogCoversEverySensitiveActionTheRequirementsName() {
        assertThat(ActivityEventCatalog.eventTypes()).contains(
                "LOGIN", "MEMBER_ROLE_CHANGED", "INVITATION_CREATED", "INVITATION_REVOKED", "INVITATION_ACCEPTED",
                "ADMIN_DEMOTION_REQUESTED", "ADMIN_DEMOTION_ACCEPTED", "ADMIN_DEMOTION_REJECTED", "ADMIN_DEMOTION_ESCALATED",
                "SUBSCRIPTION_CHANGED", "THEME_CHANGED", "LOGO_CHANGED");
    }

    @Test
    void passwordAndIdentityEventsAreNeverAuditedInATenantJournal() {
        assertThat(ActivityEventCatalog.eventTypes()).noneMatch(type -> type.contains("PASSWORD"));
        assertThat(ActivityEventCatalog.find("USER_REGISTERED")).isEmpty();
        assertThat(ActivityEventCatalog.find("PASSWORD_RESET_REQUESTED")).isEmpty();
        assertThat(ActivityEventCatalog.find("PASSWORD_CHANGED")).isEmpty();
        assertThat(ActivityEventCatalog.find(null)).isEmpty();
    }

    @Test
    void noMetadataFieldIsNamedLikePersonalData() {
        for (String type : ActivityEventCatalog.eventTypes()) {
            var entry = ActivityEventCatalog.find(type).orElseThrow();
            assertThat(entry.metadataFields()).as(type).allSatisfy(field ->
                    assertThat(field.toLowerCase()).doesNotContain("email", "name", "phone", "password", "token"));
        }
    }

    @Test
    void theOnlySystemActionIsTheEscalationOfAnUnansweredDemotion() {
        var systemActions = ActivityEventCatalog.eventTypes().stream()
                .filter(type -> ActivityEventCatalog.find(type).orElseThrow().actorField() == null).toList();

        assertThat(systemActions).containsExactly("ADMIN_DEMOTION_ESCALATED");
    }
}
