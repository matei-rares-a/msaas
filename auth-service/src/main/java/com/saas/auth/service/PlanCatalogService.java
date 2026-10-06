package com.saas.auth.service;

import com.saas.auth.entity.Plan;
import com.saas.auth.repository.PlanRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** The platform's plan catalog (REQ-DATA-05, REQ-DATA-12, REQ-BILL-07): global, EUR-only, plans are
 * only ever deactivated - there is no delete method here, and the DB rejects deletion regardless. */
@Service
@RequiredArgsConstructor
public class PlanCatalogService {

    private final PlanRepository planRepository;

    @Transactional
    public Plan createPlan(String name, BigDecimal priceEur, int maxUsers) {
        Plan plan = new Plan();
        plan.setId(UUID.randomUUID());
        plan.setName(name);
        plan.setPriceEur(priceEur);
        plan.setMaxUsers(maxUsers);
        plan.setActive(true);
        return planRepository.save(plan);
    }

    @Transactional
    public Plan deactivatePlan(UUID planId) {
        Plan plan = planRepository.findById(planId)
                .orElseThrow(() -> new IllegalArgumentException("plan not found"));
        plan.setActive(false);
        return planRepository.save(plan);
    }

    public List<Plan> listActivePlans() {
        return planRepository.findAllByActiveTrue();
    }
}
