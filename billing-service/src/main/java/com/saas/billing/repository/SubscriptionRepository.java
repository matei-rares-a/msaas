package com.saas.billing.repository;

import com.saas.billing.entity.Subscription;
import com.saas.shared.tenancy.TenantScopedRepository;

import java.util.Optional;
import java.util.UUID;

public interface SubscriptionRepository extends TenantScopedRepository<Subscription, UUID> {

    Optional<Subscription> findByStatus(Subscription.Status status);
}
