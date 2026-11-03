package com.saas.org.cache;

import com.saas.org.repository.MembershipRepository;
import com.saas.shared.tenancy.TenantContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;
import java.util.UUID;

/**
 * A member's role and read-only flag, cached for the reads that only need to know "is this a member, and what may
 * they do" (REQ-SCALE-01). Never used by the login: the token is issued from the memberships themselves
 * ({@code MembershipLookupService}), and writes check the database inside their own transaction. Whoever changes
 * a membership calls {@link #evict} after the transaction commits.
 */
@Component
public class MembershipAccessCache {

    /** What the cache keeps about a member; ids and flags only. */
    public record MemberAccess(String role, boolean readOnly) {
    }

    private final TenantCache cache;
    private final MembershipRepository membershipRepository;
    private final TransactionTemplate transactionTemplate;

    public MembershipAccessCache(TenantCache cache, MembershipRepository membershipRepository,
            PlatformTransactionManager transactionManager) {
        this.cache = cache;
        this.membershipRepository = membershipRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /** Empty when the user is not a member of the tenant (and nothing is remembered about that). */
    public Optional<MemberAccess> find(UUID tenantId, UUID userId) {
        return Optional.ofNullable(cache.get(CacheRegions.MEMBER_ACCESS, tenantId, userId.toString(), MemberAccess.class, cache.ttl(),
                () -> TenantContext.callWith(tenantId, () -> transactionTemplate.execute(status ->
                        membershipRepository.findByUserId(userId)
                                .map(m -> new MemberAccess(m.getRole().name(), m.isReadOnly()))
                                .orElse(null)))));
    }

    public void evict(UUID tenantId, UUID userId) {
        cache.evict(CacheRegions.MEMBER_ACCESS, tenantId, userId.toString());
    }
}
