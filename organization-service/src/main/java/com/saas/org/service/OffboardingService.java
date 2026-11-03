package com.saas.org.service;

import com.saas.org.entity.Invitation;
import com.saas.org.entity.Membership;
import com.saas.org.event.MemberOffboardedPayload;
import com.saas.org.leave.LeaveBalanceOverrideRepository;
import com.saas.org.leave.LeaveRequestRepository;
import com.saas.org.lunch.LunchParticipantRepository;
import com.saas.org.repository.AdminDemotionRequestRepository;
import com.saas.org.repository.InvitationRepository;
import com.saas.org.repository.MembershipRepository;
import com.saas.shared.events.EventEnvelope;
import com.saas.shared.outbox.OutboxDestination;
import com.saas.shared.outbox.OutboxWriter;
import com.saas.shared.tenancy.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

/**
 * Removes one user's data from one tenant (REQ-DATA-01). Everything that lives in this service is
 * handled in a single transaction: the membership goes, the email of the invitation the user accepted
 * is anonymized (a placeholder keeps the row, so invitation history stays consistent), pending role
 * changes aimed at the user are dropped, and so are the user's leave requests, allowance override and lunch roulette participations. Everything owned by other services (notifications, email-log
 * recipients, the global identity) and by later modules is cleaned up by their consumers of the
 * {@code MEMBER_OFFBOARDED} event, which carries ids only (REQ-EVT-02) - so Kafka replay never
 * resurrects personal data. The global identity is never touched here: it may serve other tenants.
 * Same {@link TransactionTemplate} + {@link TenantContext#callWith} pattern as {@link OrganizationService}.
 */
@Service
public class OffboardingService {

    private static final String ANONYMIZED_EMAIL_DOMAIN = "@anonymized.invalid";

    private final MembershipRepository membershipRepository;
    private final InvitationRepository invitationRepository;
    private final AdminDemotionRequestRepository demotionRequestRepository;
    private final LeaveRequestRepository leaveRequestRepository;
    private final LeaveBalanceOverrideRepository leaveBalanceOverrideRepository;
    private final LunchParticipantRepository lunchParticipantRepository;
    private final OutboxWriter outboxWriter;
    private final TransactionTemplate transactionTemplate;

    public OffboardingService(MembershipRepository membershipRepository, InvitationRepository invitationRepository,
            AdminDemotionRequestRepository demotionRequestRepository, LeaveRequestRepository leaveRequestRepository,
            LeaveBalanceOverrideRepository leaveBalanceOverrideRepository, LunchParticipantRepository lunchParticipantRepository,
            OutboxWriter outboxWriter,
            PlatformTransactionManager transactionManager) {
        this.membershipRepository = membershipRepository;
        this.invitationRepository = invitationRepository;
        this.demotionRequestRepository = demotionRequestRepository;
        this.leaveRequestRepository = leaveRequestRepository;
        this.leaveBalanceOverrideRepository = leaveBalanceOverrideRepository;
        this.lunchParticipantRepository = lunchParticipantRepository;
        this.outboxWriter = outboxWriter;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * ADMIN with write access only. An ADMIN cannot be offboarded directly: demoting an ADMIN already
     * carries the last-ADMIN safeguards (REQ-ROLE-03, REQ-ROLE-04), so the role is changed first and
     * the user removed afterwards - there is no second code path that could leave a tenant without ADMIN.
     */
    public void offboardMember(UUID tenantId, UUID callerUserId, UUID targetUserId) {
        TenantContext.callWith(tenantId, () -> transactionTemplate.execute(status -> {
            Membership caller = membershipRepository.findByUserId(callerUserId)
                    .orElseThrow(() -> new SecurityException("not a member of this organization"));
            if (caller.isReadOnly() || caller.getRole() != Membership.Role.ADMIN) {
                throw new SecurityException("only an ADMIN with write access can offboard a member");
            }
            Membership target = membershipRepository.findByUserId(targetUserId)
                    .orElseThrow(() -> new IllegalArgumentException("member not found"));
            if (target.getRole() == Membership.Role.ADMIN) {
                throw new IllegalStateException("an ADMIN cannot be offboarded; change the role first");
            }

            for (Invitation invitation : invitationRepository.findAllByAcceptedBy(targetUserId)) {
                invitation.setEmail("removed-" + invitation.getId() + ANONYMIZED_EMAIL_DOMAIN);
                invitationRepository.save(invitation);
            }
            demotionRequestRepository.deleteAllByTargetUserId(targetUserId);
            // the member's leave requests and allowance override go with them (REQ-LEAVE-08): in this transaction, before the
            // membership they reference, so there is no window and no replayed event that could delete a re-joined member's data
            leaveRequestRepository.deleteAllByUserId(targetUserId);
            leaveBalanceOverrideRepository.deleteAllByUserId(targetUserId);
            lunchParticipantRepository.deleteAllByUserId(targetUserId); // REQ-LUNCH-05: enrolments and the groups they were in
            leaveRequestRepository.flush();
            leaveBalanceOverrideRepository.flush();
            lunchParticipantRepository.flush();
            membershipRepository.delete(target);

            outboxWriter.writeEvent(EventEnvelope.forTenant("MEMBER_OFFBOARDED", 1, tenantId,
                    new MemberOffboardedPayload(tenantId, targetUserId)), OutboxDestination.KAFKA, "org.events");
            return null;
        }));
    }
}
