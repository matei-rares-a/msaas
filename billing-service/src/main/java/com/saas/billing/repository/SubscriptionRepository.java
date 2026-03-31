package com.saas.billing.repository;

import com.saas.billing.entity.Subscription;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SubscriptionRepository extends JpaRepository<Subscription, UUID> {
    Optional<Subscription> findByOrganizationId(UUID orgId);
    List<Subscription> findAllByOrganizationId(UUID orgId);
}
