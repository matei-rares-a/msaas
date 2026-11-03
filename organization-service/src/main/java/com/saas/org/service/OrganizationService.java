package com.saas.org.service;

import com.saas.org.cache.MembershipAccessCache;
import com.saas.org.dto.AdminDemotionRequestResponse;
import com.saas.org.dto.MemberRoleResponse;
import com.saas.org.dto.OrganizationResponse;
import com.saas.org.dto.RoleChangeOutcome;
import com.saas.org.entity.AdminDemotionRequest;
import com.saas.org.entity.Membership;
import com.saas.org.entity.Tenant;
import com.saas.org.event.AdminDemotionDecidedPayload;
import com.saas.org.event.AdminDemotionEscalatedPayload;
import com.saas.org.event.AdminDemotionRequestedPayload;
import com.saas.org.event.MemberRoleChangedPayload;
import com.saas.org.repository.AdminDemotionRequestRepository;
import com.saas.org.repository.MembershipRepository;
import com.saas.org.repository.TenantSettingsRepository;
import com.saas.org.repository.TenantRepository;
import com.saas.shared.events.EventEnvelope;
import com.saas.shared.outbox.OutboxDestination;
import com.saas.shared.outbox.OutboxWriter;
import com.saas.shared.tenancy.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.UUID;

/**
 * Organization = tenant creation/management (REQ-AUTH-03, REQ-ROLE-06, REQ-ROLE-01, REQ-ROLE-05,
 * REQ-MGR-03, REQ-MGR-05, REQ-ROLE-03, REQ-ROLE-04). Uses a programmatic {@link TransactionTemplate},
 * not {@code @Transactional}: the AOP proxy would start the transaction (and
 * {@link com.saas.shared.tenancy.TenantAwareJpaTransactionManager} would read the tenant context)
 * before this method body gets a chance to set it via {@link TenantContext#callWith}.
 */
@Service
public class OrganizationService {

    private final TenantRepository tenantRepository;
    private final MembershipRepository membershipRepository;
    private final AdminDemotionRequestRepository demotionRequestRepository;
    private final TenantSettingsRepository settingsRepository;
    private final ThemeService themeService;
    private final OutboxWriter outboxWriter;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;
    private final MembershipAccessCache memberAccess;

    public OrganizationService(TenantRepository tenantRepository, MembershipRepository membershipRepository,
            AdminDemotionRequestRepository demotionRequestRepository, TenantSettingsRepository settingsRepository,
            ThemeService themeService, OutboxWriter outboxWriter, Clock clock, PlatformTransactionManager transactionManager,
            MembershipAccessCache memberAccess) {
        this.tenantRepository = tenantRepository;
        this.membershipRepository = membershipRepository;
        this.demotionRequestRepository = demotionRequestRepository;
        this.settingsRepository = settingsRepository;
        this.themeService = themeService;
        this.outboxWriter = outboxWriter;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.memberAccess = memberAccess;
    }

    /** The creator becomes ADMIN, with write access, of the brand-new tenant. */
    public OrganizationResponse createOrganization(String name, UUID creatorUserId) {
        UUID tenantId = UUID.randomUUID();
        return TenantContext.callWith(tenantId, () -> transactionTemplate.execute(status -> {
            Tenant tenant = new Tenant();
            tenant.setId(tenantId);
            tenant.setName(name);
            tenantRepository.save(tenant);

            Membership membership = new Membership();
            membership.setId(UUID.randomUUID());
            membership.setUserId(creatorUserId);
            membership.setRole(Membership.Role.ADMIN);
            membership.setReadOnly(false);
            membershipRepository.save(membership);

            settingsRepository.save(themeService.newDefaultSettings()); // REQ-PERS-05

            return new OrganizationResponse(tenant.getId(), tenant.getName());
        }));
    }

    /** RLS-scoped (unlike {@link MembershipLookupService}'s cross-tenant reader); used by auth to get
     * the UI-facing tenant name for a token it already knows which single tenant it's for (REQ-AUTH-05). */
    public String tenantName(UUID tenantId) {
        return TenantContext.callWith(tenantId, () -> tenantRepository.findById(tenantId)
                .map(Tenant::getName)
                .orElseThrow(() -> new IllegalArgumentException("organization not found")));
    }

    /**
     * The ids of the tenant's members who hold one of the given roles, for services that need to address a
     * notification to "the admins and managers" without keeping a copy of the roles (REQ-NOTIF-03). Ids only,
     * RLS-scoped to the tenant.
     */
    public java.util.List<UUID> memberIdsWithRoles(UUID tenantId, java.util.Set<Membership.Role> roles) {
        return TenantContext.callWith(tenantId, () -> transactionTemplate.execute(status ->
                membershipRepository.findAllByRoleIn(roles).stream().map(Membership::getUserId).sorted().toList()));
    }

    /** Minimal write path to exercise the read-only flag (REQ-ROLE-06); the full permission matrix is P4.2. */
    public OrganizationResponse renameOrganization(UUID tenantId, UUID callerUserId, String newName) {
        return TenantContext.callWith(tenantId, () -> transactionTemplate.execute(status -> {
            Membership membership = membershipRepository.findByUserId(callerUserId)
                    .orElseThrow(() -> new SecurityException("not a member of this organization"));
            if (membership.isReadOnly()) {
                throw new SecurityException("read-only membership cannot write");
            }
            Tenant tenant = tenantRepository.findById(tenantId)
                    .orElseThrow(() -> new IllegalArgumentException("organization not found"));
            tenant.setName(newName);
            tenantRepository.save(tenant);
            return new OrganizationResponse(tenant.getId(), tenant.getName());
        }));
    }

    /**
     * Role management is ADMIN-only, regardless of how many ADMIN/MANAGER a tenant has (REQ-MGR-03,
     * REQ-ROLE-01, REQ-ROLE-05); any number of MANAGER, including zero, is allowed (REQ-MGR-05).
     * Demoting the last ADMIN is rejected outright (REQ-ROLE-03); demoting one of exactly two ADMIN
     * requires the target's acceptance instead of applying immediately (REQ-ROLE-04).
     */
    public RoleChangeOutcome updateMemberRole(UUID tenantId, UUID callerUserId, UUID targetUserId, Membership.Role newRole) {
        RoleChangeOutcome outcome = TenantContext.callWith(tenantId, () -> transactionTemplate.execute(status -> {
            Membership caller = membershipRepository.findByUserId(callerUserId)
                    .orElseThrow(() -> new SecurityException("not a member of this organization"));
            if (caller.isReadOnly() || caller.getRole() != Membership.Role.ADMIN) {
                throw new SecurityException("only an ADMIN with write access can manage roles");
            }
            Membership target = membershipRepository.findByUserId(targetUserId)
                    .orElseThrow(() -> new IllegalArgumentException("member not found"));

            if (target.getRole() == Membership.Role.ADMIN && newRole != Membership.Role.ADMIN) {
                long adminCount = membershipRepository.countByRole(Membership.Role.ADMIN);
                if (adminCount <= 1) {
                    throw new IllegalStateException("cannot demote the last ADMIN of an organization");
                }
                if (adminCount == 2) {
                    AdminDemotionRequest request = new AdminDemotionRequest();
                    request.setId(UUID.randomUUID());
                    request.setTargetUserId(targetUserId);
                    request.setRequestedBy(callerUserId);
                    request.setNewRole(newRole);
                    request.setStatus(AdminDemotionRequest.Status.PENDING);
                    request.setCreatedAt(clock.instant());
                    demotionRequestRepository.save(request);
                    publish(tenantId, "ADMIN_DEMOTION_REQUESTED", new AdminDemotionRequestedPayload(
                            tenantId, request.getId(), callerUserId, targetUserId, newRole.name()));
                    return RoleChangeOutcome.pending(AdminDemotionRequestResponse.of(request));
                }
            }

            Membership.Role previousRole = target.getRole();
            target.setRole(newRole);
            membershipRepository.save(target);
            publish(tenantId, "MEMBER_ROLE_CHANGED", new MemberRoleChangedPayload(
                    tenantId, callerUserId, targetUserId, previousRole.name(), newRole.name()));
            return RoleChangeOutcome.applied(new MemberRoleResponse(target.getUserId(), target.getRole().name()));
        }));
        memberAccess.evict(tenantId, targetUserId); // the cached role is the old one now (REQ-SCALE-01)
        return outcome;
    }

    /** Only the targeted admin can accept their own demotion (REQ-ROLE-04). */
    public MemberRoleResponse acceptAdminDemotion(UUID tenantId, UUID requestId, UUID callerUserId) {
        MemberRoleResponse response = TenantContext.callWith(tenantId, () -> transactionTemplate.execute(status -> {
            AdminDemotionRequest request = requirePendingRequest(requestId, callerUserId);

            Membership target = membershipRepository.findByUserId(request.getTargetUserId())
                    .orElseThrow(() -> new IllegalArgumentException("member not found"));
            target.setRole(request.getNewRole());
            membershipRepository.save(target);

            request.setStatus(AdminDemotionRequest.Status.ACCEPTED);
            request.setRespondedAt(clock.instant());
            demotionRequestRepository.save(request);
            publish(tenantId, "ADMIN_DEMOTION_ACCEPTED", new AdminDemotionDecidedPayload(
                    tenantId, request.getId(), callerUserId, request.getNewRole().name()));

            return new MemberRoleResponse(target.getUserId(), target.getRole().name());
        }));
        memberAccess.evict(tenantId, callerUserId); // the caller is the targeted admin
        return response;
    }

    /** Only the targeted admin can reject their own demotion; nothing changes besides the request status. */
    public void rejectAdminDemotion(UUID tenantId, UUID requestId, UUID callerUserId) {
        TenantContext.callWith(tenantId, () -> transactionTemplate.execute(status -> {
            AdminDemotionRequest request = requirePendingRequest(requestId, callerUserId);
            request.setStatus(AdminDemotionRequest.Status.REJECTED);
            request.setRespondedAt(clock.instant());
            demotionRequestRepository.save(request);
            publish(tenantId, "ADMIN_DEMOTION_REJECTED", new AdminDemotionDecidedPayload(
                    tenantId, request.getId(), callerUserId, request.getNewRole().name()));
            return null;
        }));
    }

    /** One audited action = one event in this transaction's outbox (REQ-EVT-04, REQ-MON-01). */
    private void publish(UUID tenantId, String eventType, com.saas.shared.events.EventPayload payload) {
        outboxWriter.writeEvent(EventEnvelope.forTenant(eventType, 1, tenantId, payload), OutboxDestination.KAFKA, "org.events");
    }

    /** Only the targeted admin can respond to this request. */
    private AdminDemotionRequest requirePendingRequest(UUID requestId, UUID callerUserId) {
        AdminDemotionRequest request = demotionRequestRepository.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("demotion request not found"));
        if (!request.getTargetUserId().equals(callerUserId)) {
            throw new SecurityException("only the targeted admin can respond to this request");
        }
        if (request.getStatus() != AdminDemotionRequest.Status.PENDING) {
            throw new IllegalStateException("this request is no longer pending");
        }
        return request;
    }

    /** Called by {@code AdminDemotionEscalationJob} once per overdue request (REQ-ROLE-04). */
    public void escalateAdminDemotion(UUID tenantId, UUID requestId) {
        TenantContext.callWith(tenantId, () -> transactionTemplate.execute(status -> {
            AdminDemotionRequest request = demotionRequestRepository.findById(requestId).orElse(null);
            if (request == null || request.getStatus() != AdminDemotionRequest.Status.PENDING) {
                return null;
            }
            request.setStatus(AdminDemotionRequest.Status.ESCALATED);
            request.setRespondedAt(clock.instant());
            demotionRequestRepository.save(request);

            EventEnvelope<AdminDemotionEscalatedPayload> envelope = EventEnvelope.forTenant(
                    "ADMIN_DEMOTION_ESCALATED", 1, tenantId,
                    new AdminDemotionEscalatedPayload(request.getId(), request.getTargetUserId(), request.getRequestedBy()));
            outboxWriter.writeEvent(envelope, OutboxDestination.KAFKA, "org.events");
            return null;
        }));
    }
}
