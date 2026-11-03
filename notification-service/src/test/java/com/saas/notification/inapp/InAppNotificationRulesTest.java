package com.saas.notification.inapp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.saas.notification.inapp.InAppNotificationService.NewNotification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The v1 list of events that create a notification, who each goes to and what it says (REQ-NOTIF-03). The
 * member directory is a fake that records what it was asked, so who counts as "admins and managers" is checked
 * at the question, and who is left out at the answer.
 */
@Tag("REQ-NOTIF-03")
class InAppNotificationRulesTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final List<Set<String>> asked = new ArrayList<>();
    private final UUID tenant = UUID.randomUUID();
    private final UUID admin = UUID.randomUUID();
    private final UUID manager = UUID.randomUUID();
    private InAppNotificationRules rules;

    @BeforeEach
    void rules() {
        rules = new InAppNotificationRules((tenantId, roles) -> {
            assertThat(tenantId).isEqualTo(tenant);
            asked.add(roles);
            return List.of(admin, manager);
        }, objectMapper);
    }

    private JsonNode json(String text) throws Exception {
        return objectMapper.readTree(text);
    }

    @Test
    void aRoleChangeGoesToTheUserWhoseRoleChanged() throws Exception {
        UUID target = UUID.randomUUID();

        var out = rules.notificationsFor("MEMBER_ROLE_CHANGED", tenant, json("{\"targetUserId\":\"" + target
                + "\",\"actorUserId\":\"" + admin + "\",\"previousRole\":\"USER\",\"newRole\":\"MANAGER\"}"));

        assertThat(out).hasSize(1);
        NewNotification n = out.get(0);
        assertThat(n.userId()).isEqualTo(target);
        assertThat(n.eventType()).isEqualTo("MEMBER_ROLE_CHANGED");
        assertThat(n.title()).isEqualTo("Your role changed");
        assertThat(n.message()).isEqualTo("Your role in this organization changed from USER to MANAGER.");
        assertThat(n.level()).isEqualTo("INFO");
        assertThat(asked).isEmpty();
    }

    @Test
    void aRoleChangeYouMakeToYourselfIsNotNewsToYou() throws Exception {
        var out = rules.notificationsFor("MEMBER_ROLE_CHANGED", tenant, json("{\"targetUserId\":\"" + admin
                + "\",\"actorUserId\":\"" + admin + "\",\"previousRole\":\"ADMIN\",\"newRole\":\"ADMIN\"}"));

        assertThat(out).isEmpty();
    }

    @Test
    void aRoleNameThatDoesNotLookLikeARoleIsNotEchoedIntoTheText() throws Exception {
        UUID target = UUID.randomUUID();

        var out = rules.notificationsFor("MEMBER_ROLE_CHANGED", tenant, json("{\"targetUserId\":\"" + target
                + "\",\"previousRole\":\"<script>alert(1)</script>\",\"newRole\":\"MANAGER\"}"));

        assertThat(out.get(0).message()).doesNotContain("script").contains("from another role to MANAGER");
    }

    @Test
    void aDemotionRequestGoesToTheAdminAskedToAcceptIt() throws Exception {
        UUID target = UUID.randomUUID();

        var out = rules.notificationsFor("ADMIN_DEMOTION_REQUESTED", tenant, json("{\"requestId\":\"" + UUID.randomUUID()
                + "\",\"actorUserId\":\"" + admin + "\",\"targetUserId\":\"" + target + "\",\"newRole\":\"MANAGER\"}"));

        assertThat(out).hasSize(1);
        assertThat(out.get(0).userId()).isEqualTo(target);
        assertThat(out.get(0).level()).isEqualTo("WARNING");
        assertThat(out.get(0).title()).isEqualTo("Your answer is needed");
        assertThat(out.get(0).message()).contains("change your role to MANAGER").contains("Accept or reject");
    }

    @Test
    void anAcceptedInvitationGoesToTheAdminsAndManagersExceptTheOneWhoJoined() throws Exception {
        var out = rules.notificationsFor("INVITATION_ACCEPTED", tenant, json("{\"invitationId\":\"" + UUID.randomUUID()
                + "\",\"userId\":\"" + manager + "\",\"role\":\"MANAGER\"}"));

        assertThat(out).extracting(NewNotification::userId).containsExactly(admin);
        assertThat(out.get(0).title()).isEqualTo("Invitation accepted");
        assertThat(out.get(0).message()).isEqualTo("A new member joined the organization as MANAGER.");
        assertThat(asked).containsExactly(Set.of("ADMIN", "MANAGER"));
    }

    @Test
    void aSubscriptionThatJustWentPastDueGoesToTheAdminsAndManagers() throws Exception {
        var out = rules.notificationsFor("SUBSCRIPTION_CHANGED", tenant,
                json("{\"status\":\"PAST_DUE\",\"previousStatus\":\"ACTIVE\",\"planId\":\"" + UUID.randomUUID() + "\",\"maxUsers\":5}"));

        assertThat(out).extracting(NewNotification::userId).containsExactlyInAnyOrder(admin, manager);
        assertThat(out).allSatisfy(n -> {
            assertThat(n.eventType()).isEqualTo("SUBSCRIPTION_PAST_DUE");
            assertThat(n.level()).isEqualTo("WARNING");
            assertThat(n.title()).isEqualTo("Subscription payment failed");
            assertThat(n.message()).contains("read-only");
        });
        assertThat(asked).containsExactly(Set.of("ADMIN", "MANAGER"));
    }

    @Test
    void aSubscriptionWithNoPreviousStatusThatIsPastDueIsStillAnnounced() throws Exception {
        assertThat(rules.notificationsFor("SUBSCRIPTION_CHANGED", tenant, json("{\"status\":\"PAST_DUE\"}"))).hasSize(2);
    }

    @ParameterizedTest(name = "{0} is not announced")
    @ValueSource(strings = {
            "{\"status\":\"PAST_DUE\",\"previousStatus\":\"PAST_DUE\"}",
            "{\"status\":\"ACTIVE\",\"previousStatus\":\"PAST_DUE\"}",
            "{\"status\":\"TRIALING\"}",
            "{\"status\":\"SUSPENDED\",\"previousStatus\":\"PAST_DUE\"}",
            "{\"status\":\"CANCELED\",\"previousStatus\":\"ACTIVE\"}",
            "{}"})
    void aSubscriptionChangeThatIsNotANewPastDueIsNotAnnounced(String payload) throws Exception {
        assertThat(rules.notificationsFor("SUBSCRIPTION_CHANGED", tenant, json(payload))).isEmpty();
        assertThat(asked).isEmpty();
    }

    @Test
    void anExpiringTrialGoesToTheAdminsAndManagersWithTheDateItEnds() throws Exception {
        long endsAt = Instant.parse("2026-11-16T09:30:00Z").getEpochSecond();

        var fromEpoch = rules.notificationsFor("TRIAL_EXPIRING", tenant, json("{\"trialEndsAt\":" + endsAt + ".123}"));
        var fromText = rules.notificationsFor("TRIAL_EXPIRING", tenant, json("{\"trialEndsAt\":\"2026-11-16T09:30:00Z\"}"));

        for (var out : List.of(fromEpoch, fromText)) {
            assertThat(out).extracting(NewNotification::userId).containsExactlyInAnyOrder(admin, manager);
            assertThat(out.get(0).title()).isEqualTo("Your trial is ending soon");
            assertThat(out.get(0).message()).contains("ends on 2026-11-16");
            assertThat(out.get(0).level()).isEqualTo("WARNING");
        }
    }

    @ParameterizedTest(name = "event [{0}]")
    @ValueSource(strings = {"LOGIN", "THEME_CHANGED", "INVITATION_CREATED", "MEMBER_OFFBOARDED", "SEND_INVITATION_EMAIL", "PASSWORD_CHANGED", ""})
    void anEventThatIsNotOnTheListCreatesNothing(String eventType) throws Exception {
        assertThat(rules.notificationsFor(eventType, tenant, json("{\"targetUserId\":\"" + UUID.randomUUID() + "\"}"))).isEmpty();
        assertThat(asked).isEmpty();
    }

    @Test
    void theHandledEventTypesAreExactlyTheOnesTheRulesCover() throws Exception {
        for (String type : InAppNotificationRules.HANDLED_EVENT_TYPES) {
            // every handled type, given a complete payload, must produce something for someone
            var out = rules.notificationsFor(type, tenant, json("{\"targetUserId\":\"" + UUID.randomUUID() + "\",\"userId\":\""
                    + UUID.randomUUID() + "\",\"status\":\"PAST_DUE\",\"trialEndsAt\":\"2026-11-16T00:00:00Z\",\"newRole\":\"USER\",\"requesterUserId\":\""
                    + UUID.randomUUID() + "\",\"approverUserIds\":[\""                     + UUID.randomUUID() + "\"],\"startDate\":\"2026-12-01\",\"endDate\":\"2026-12-02\",\"weekStart\":\"2026-12-07\",\"groups\":[[\""
                                        + UUID.randomUUID() + "\",\"" + UUID.randomUUID() + "\"]],\"participantUserIds\":[\"" + UUID.randomUUID() + "\"]}"));
                                assertThat(out).as(type).isNotEmpty();
                            }
                            assertThat(InAppNotificationRules.HANDLED_EVENT_TYPES).containsExactlyInAnyOrder("MEMBER_ROLE_CHANGED",
                                    "ADMIN_DEMOTION_REQUESTED", "INVITATION_ACCEPTED", "SUBSCRIPTION_CHANGED", "TRIAL_EXPIRING", "LEAVE_REQUESTED",
                                    "LEAVE_APPROVED", "LEAVE_REJECTED", "LEAVE_CANCELLED", "LUNCH_GROUPS_FORMED", "LUNCH_ROUND_CANCELLED");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "MEMBER_ROLE_CHANGED|{\"previousRole\":\"USER\",\"newRole\":\"MANAGER\"}",
            "MEMBER_ROLE_CHANGED|{\"targetUserId\":\"not-an-id\"}",
            "ADMIN_DEMOTION_REQUESTED|{\"newRole\":\"MANAGER\"}",
            "INVITATION_ACCEPTED|{\"role\":\"USER\"}",
            "TRIAL_EXPIRING|{}",
            "TRIAL_EXPIRING|{\"trialEndsAt\":\"soon\"}"})
    void anEventMissingWhatItMustHaveIsRefusedSoItIsRetriedAndDeadLettered(String eventAndPayload) throws Exception {
        String[] parts = eventAndPayload.split("\\|", 2);

        assertThatThrownBy(() -> rules.notificationsFor(parts[0], tenant, json(parts[1]))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void noNotificationCarriesAnEmailAddressOrAName() throws Exception {
        UUID target = UUID.randomUUID();
        List<NewNotification> all = new ArrayList<>();
        all.addAll(rules.notificationsFor("MEMBER_ROLE_CHANGED", tenant, json("{\"targetUserId\":\"" + target + "\",\"previousRole\":\"USER\",\"newRole\":\"ADMIN\"}")));
        all.addAll(rules.notificationsFor("ADMIN_DEMOTION_REQUESTED", tenant, json("{\"targetUserId\":\"" + target + "\",\"newRole\":\"USER\"}")));
        all.addAll(rules.notificationsFor("INVITATION_ACCEPTED", tenant, json("{\"userId\":\"" + target + "\",\"role\":\"USER\",\"email\":\"someone@example.com\"}")));
        all.addAll(rules.notificationsFor("SUBSCRIPTION_CHANGED", tenant, json("{\"status\":\"PAST_DUE\",\"email\":\"someone@example.com\"}")));
        all.addAll(rules.notificationsFor("TRIAL_EXPIRING", tenant, json("{\"trialEndsAt\":\"2026-11-16T00:00:00Z\"}")));

        assertThat(all).isNotEmpty().allSatisfy(n -> assertThat(n.title() + " " + n.message()).doesNotContain("@").doesNotContain(target.toString()));
    }

    // ---- leave requests (REQ-LEAVE-07) ----

    @Test
    @Tag("REQ-LEAVE-07")
    void aNewLeaveRequestGoesToTheOtherApproversNamedInTheEventAndNotToTheRequester() throws Exception {
        UUID requester = UUID.randomUUID();
        UUID approverA = UUID.randomUUID();
        UUID approverB = UUID.randomUUID();

        var out = rules.notificationsFor("LEAVE_REQUESTED", tenant, json("{\"requestId\":\"" + UUID.randomUUID() + "\",\"requesterUserId\":\"" + requester
                + "\",\"approverUserIds\":[\"" + approverA + "\",\"" + requester + "\",\"" + approverB + "\",\"" + approverA
                + "\"],\"startDate\":\"2026-12-01\",\"endDate\":\"2026-12-05\"}"));

        assertThat(out).extracting(NewNotification::userId).containsExactlyInAnyOrder(approverA, approverB);
        assertThat(out).allSatisfy(n -> {
            assertThat(n.eventType()).isEqualTo("LEAVE_REQUESTED");
            assertThat(n.title()).isEqualTo("New leave request");
            assertThat(n.message()).isEqualTo("A leave request for 2026-12-01 to 2026-12-05 is waiting for a decision.");
            assertThat(n.level()).isEqualTo("INFO");
        });
        assertThat(asked).as("the event names who to tell; nobody is looked up").isEmpty();
    }

    @Test
    @Tag("REQ-LEAVE-07")
    void aLeaveRequestWithNoOtherApproverNotifiesNobody() throws Exception {
        UUID requester = UUID.randomUUID();

        assertThat(rules.notificationsFor("LEAVE_REQUESTED", tenant, json("{\"requesterUserId\":\"" + requester + "\",\"approverUserIds\":[],"
                + "\"startDate\":\"2026-12-01\",\"endDate\":\"2026-12-05\"}"))).isEmpty();
        assertThat(rules.notificationsFor("LEAVE_REQUESTED", tenant, json("{\"requesterUserId\":\"" + requester + "\",\"approverUserIds\":[\""
                + requester + "\"],\"startDate\":\"2026-12-01\",\"endDate\":\"2026-12-05\"}"))).isEmpty();
    }

    @Test
    @Tag("REQ-LEAVE-07")
    void anApprovalAndARejectionGoToTheRequesterWithWhatWasDecided() throws Exception {
        UUID requester = UUID.randomUUID();
        String payload = "{\"requestId\":\"" + UUID.randomUUID() + "\",\"actorUserId\":\"" + UUID.randomUUID() + "\",\"requesterUserId\":\"" + requester
                + "\",\"leaveType\":\"ODIHNA\",\"startDate\":\"2026-12-01\",\"endDate\":\"2026-12-05\",\"days\":5,\"previousStatus\":\"PENDING\"}";

        var approved = rules.notificationsFor("LEAVE_APPROVED", tenant, json(payload));
        var rejected = rules.notificationsFor("LEAVE_REJECTED", tenant, json(payload));

        assertThat(approved).hasSize(1);
        assertThat(approved.get(0).userId()).isEqualTo(requester);
        assertThat(approved.get(0).eventType()).isEqualTo("LEAVE_APPROVED");
        assertThat(approved.get(0).title()).isEqualTo("Leave request approved");
        assertThat(approved.get(0).message()).isEqualTo("Your leave request for 2026-12-01 to 2026-12-05 was approved.");
        assertThat(approved.get(0).level()).isEqualTo("INFO");
        assertThat(rejected).hasSize(1);
        assertThat(rejected.get(0).userId()).isEqualTo(requester);
        assertThat(rejected.get(0).eventType()).isEqualTo("LEAVE_REJECTED");
        assertThat(rejected.get(0).message()).isEqualTo("Your leave request for 2026-12-01 to 2026-12-05 was rejected.");
        assertThat(rejected.get(0).level()).isEqualTo("WARNING");
        assertThat(asked).isEmpty();
    }

    @Test
    @Tag("REQ-LEAVE-07")
    void anApproversCancellationOfApprovedLeaveGoesToTheRequesterButTheirOwnDoesNot() throws Exception {
        UUID requester = UUID.randomUUID();
        UUID approver = UUID.randomUUID();
        String byApprover = "{\"actorUserId\":\"" + approver + "\",\"requesterUserId\":\"" + requester + "\",\"startDate\":\"2026-12-01\",\"endDate\":\"2026-12-05\"}";
        String bySelf = "{\"actorUserId\":\"" + requester + "\",\"requesterUserId\":\"" + requester + "\",\"startDate\":\"2026-12-01\",\"endDate\":\"2026-12-05\"}";

        var told = rules.notificationsFor("LEAVE_CANCELLED", tenant, json(byApprover));

        assertThat(told).hasSize(1);
        assertThat(told.get(0).userId()).isEqualTo(requester);
        assertThat(told.get(0).title()).isEqualTo("Leave cancelled");
        assertThat(told.get(0).message()).isEqualTo("Your approved leave for 2026-12-01 to 2026-12-05 was cancelled by an approver.");
        assertThat(told.get(0).level()).isEqualTo("WARNING");
        assertThat(rules.notificationsFor("LEAVE_CANCELLED", tenant, json(bySelf))).isEmpty();
    }

    @Test
    @Tag("REQ-LEAVE-07")
    void anAutoApprovalNotifiesNobodyBecauseTheRequesterIsTheOnlyApprover() throws Exception {
        assertThat(rules.notificationsFor("LEAVE_AUTO_APPROVED", tenant, json("{\"requesterUserId\":\"" + UUID.randomUUID() + "\"}"))).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "LEAVE_REQUESTED|{\"requesterUserId\":\"00000000-0000-0000-0000-000000000001\",\"startDate\":\"2026-12-01\",\"endDate\":\"2026-12-05\"}",
            "LEAVE_REQUESTED|{\"requesterUserId\":\"00000000-0000-0000-0000-000000000001\",\"approverUserIds\":\"x\",\"startDate\":\"2026-12-01\",\"endDate\":\"2026-12-05\"}",
            "LEAVE_REQUESTED|{\"requesterUserId\":\"00000000-0000-0000-0000-000000000001\",\"approverUserIds\":[\"not-an-id\"],\"startDate\":\"2026-12-01\",\"endDate\":\"2026-12-05\"}",
            "LEAVE_REQUESTED|{\"approverUserIds\":[],\"startDate\":\"2026-12-01\",\"endDate\":\"2026-12-05\"}",
            "LEAVE_REQUESTED|{\"requesterUserId\":\"00000000-0000-0000-0000-000000000001\",\"approverUserIds\":[],\"startDate\":\"2026-12-01\"}",
            "LEAVE_APPROVED|{\"startDate\":\"2026-12-01\",\"endDate\":\"2026-12-05\"}",
            "LEAVE_APPROVED|{\"requesterUserId\":\"00000000-0000-0000-0000-000000000001\",\"startDate\":\"<script>\",\"endDate\":\"2026-12-05\"}",
            "LEAVE_REJECTED|{\"requesterUserId\":\"00000000-0000-0000-0000-000000000001\",\"startDate\":\"2026-12-01\",\"endDate\":\"2026-13-45\"}",
            "LEAVE_CANCELLED|{\"actorUserId\":\"00000000-0000-0000-0000-000000000002\",\"startDate\":\"2026-12-01\",\"endDate\":\"2026-12-05\"}"})
    @Tag("REQ-EVT-07")
    void aLeaveEventMissingWhatItMustHaveIsRefusedSoItIsRetriedAndDeadLettered(String eventAndPayload) {
        String[] parts = eventAndPayload.split("\\|", 2);

        assertThatThrownBy(() -> rules.notificationsFor(parts[0], tenant, json(parts[1]))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @Tag("REQ-LEAVE-07")
    @Tag("REQ-EVT-02")
    void aLeaveNotificationNeverEchoesAReasonACommentOrATypeNorAnyIdOrAddress() throws Exception {
        UUID requester = UUID.randomUUID();
        UUID approver = UUID.randomUUID();
        String extras = ",\"reason\":\"Private reason\",\"comment\":\"Private comment\",\"leaveType\":\"ODIHNA\",\"email\":\"someone@example.com\"";
        List<NewNotification> all = new ArrayList<>();
        all.addAll(rules.notificationsFor("LEAVE_REQUESTED", tenant, json("{\"requesterUserId\":\"" + requester + "\",\"approverUserIds\":[\"" + approver
                + "\"],\"startDate\":\"2026-12-01\",\"endDate\":\"2026-12-05\"" + extras + "}")));
        all.addAll(rules.notificationsFor("LEAVE_APPROVED", tenant, json("{\"requesterUserId\":\"" + requester + "\",\"actorUserId\":\"" + approver
                + "\",\"startDate\":\"2026-12-01\",\"endDate\":\"2026-12-05\"" + extras + "}")));
        all.addAll(rules.notificationsFor("LEAVE_CANCELLED", tenant, json("{\"requesterUserId\":\"" + requester + "\",\"actorUserId\":\"" + approver
                + "\",\"startDate\":\"2026-12-01\",\"endDate\":\"2026-12-05\"" + extras + "}")));

        assertThat(all).hasSize(3).allSatisfy(n -> assertThat(n.title() + " " + n.message()).doesNotContain("Private").doesNotContain("ODIHNA")
                .doesNotContain("@").doesNotContain(requester.toString()).doesNotContain(approver.toString()));
    }

    // ---- lunch roulette (REQ-LUNCH-05) ----

    @Test
    @Tag("REQ-LUNCH-05")
    void formedGroupsTellEachMemberOnceHowBigTheirGroupIs() throws Exception {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        UUID d = UUID.randomUUID();
        UUID e = UUID.randomUUID();

        var out = rules.notificationsFor("LUNCH_GROUPS_FORMED", tenant, json("{\"roundId\":\"" + UUID.randomUUID() + "\",\"weekStart\":\"2026-12-07\","
                + "\"groups\":[[\"" + a + "\",\"" + b + "\"],[\"" + c + "\",\"" + d + "\",\"" + e + "\",\"" + a + "\"]]}"));

        assertThat(out).extracting(NewNotification::userId).containsExactlyInAnyOrder(a, b, c, d, e);
        assertThat(out).allSatisfy(n -> {
            assertThat(n.eventType()).isEqualTo("LUNCH_GROUPS_FORMED");
            assertThat(n.title()).isEqualTo("Your lunch group is ready");
            assertThat(n.level()).isEqualTo("INFO");
            assertThat(n.message()).doesNotContain("@").doesNotContain(a.toString()).doesNotContain(b.toString());
        });
        assertThat(out.stream().filter(n -> n.userId().equals(a)).findFirst().orElseThrow().message())
                .isEqualTo("Your lunch group for the week of 2026-12-07 is ready: you will have lunch with 1 other person. Open the lunch page to see who.");
        assertThat(out.stream().filter(n -> n.userId().equals(c)).findFirst().orElseThrow().message())
                .contains("with 3 other people");
        assertThat(asked).isEmpty();
    }

    @Test
    @Tag("REQ-LUNCH-05")
    void aCancelledRoundTellsEveryParticipantNamedInTheEventOnce() throws Exception {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();

        var out = rules.notificationsFor("LUNCH_ROUND_CANCELLED", tenant, json("{\"roundId\":\"" + UUID.randomUUID() + "\",\"weekStart\":\"2026-12-07\","
                + "\"reason\":\"BY_APPROVER\",\"participantUserIds\":[\"" + a + "\",\"" + b + "\",\"" + a + "\"]}"));

        assertThat(out).extracting(NewNotification::userId).containsExactlyInAnyOrder(a, b);
        assertThat(out).allSatisfy(n -> {
            assertThat(n.eventType()).isEqualTo("LUNCH_ROUND_CANCELLED");
            assertThat(n.title()).isEqualTo("Lunch round cancelled");
            assertThat(n.message()).isEqualTo("The lunch round for the week of 2026-12-07 was cancelled.");
            assertThat(n.level()).isEqualTo("WARNING");
        });
        assertThat(asked).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "LUNCH_GROUPS_FORMED|{\"groups\":[[\"00000000-0000-0000-0000-000000000001\"]]}",
            "LUNCH_GROUPS_FORMED|{\"weekStart\":\"2026-12-07\"}",
            "LUNCH_GROUPS_FORMED|{\"weekStart\":\"2026-12-07\",\"groups\":\"x\"}",
            "LUNCH_GROUPS_FORMED|{\"weekStart\":\"2026-12-07\",\"groups\":[\"x\"]}",
            "LUNCH_GROUPS_FORMED|{\"weekStart\":\"2026-12-07\",\"groups\":[[\"not-an-id\"]]}",
            "LUNCH_GROUPS_FORMED|{\"weekStart\":\"<script>\",\"groups\":[[\"00000000-0000-0000-0000-000000000001\"]]}",
            "LUNCH_ROUND_CANCELLED|{\"participantUserIds\":[\"00000000-0000-0000-0000-000000000001\"]}",
            "LUNCH_ROUND_CANCELLED|{\"weekStart\":\"2026-12-07\"}",
            "LUNCH_ROUND_CANCELLED|{\"weekStart\":\"2026-12-07\",\"participantUserIds\":\"x\"}",
            "LUNCH_ROUND_CANCELLED|{\"weekStart\":\"2026-12-07\",\"participantUserIds\":[\"not-an-id\"]}"})
    @Tag("REQ-EVT-07")
    void aLunchEventMissingWhatItMustHaveIsRefusedSoItIsRetriedAndDeadLettered(String eventAndPayload) {
        String[] parts = eventAndPayload.split("\\|", 2);

        assertThatThrownBy(() -> rules.notificationsFor(parts[0], tenant, json(parts[1]))).isInstanceOf(IllegalArgumentException.class);
    }
}
