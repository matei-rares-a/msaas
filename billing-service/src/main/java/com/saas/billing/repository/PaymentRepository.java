package com.saas.billing.repository;

import com.saas.billing.entity.Payment;
import com.saas.shared.tenancy.TenantScopedRepository;

import java.util.List;
import java.util.UUID;

public interface PaymentRepository extends TenantScopedRepository<Payment, UUID> {

    List<Payment> findAllByInvoiceId(UUID invoiceId);
}
