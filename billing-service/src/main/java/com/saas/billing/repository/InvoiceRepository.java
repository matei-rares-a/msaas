package com.saas.billing.repository;

import com.saas.billing.entity.Invoice;
import com.saas.shared.tenancy.TenantScopedRepository;

import java.util.List;
import java.util.UUID;

public interface InvoiceRepository extends TenantScopedRepository<Invoice, UUID> {

    List<Invoice> findAllBySubscriptionId(UUID subscriptionId);
}
