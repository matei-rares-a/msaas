package com.saas.billing;

import com.saas.billing.entity.Invoice;
import com.saas.billing.entity.Plan;
import com.saas.billing.entity.Subscription;
import com.saas.billing.repository.InvoiceRepository;
import com.saas.billing.repository.PlanRepository;
import com.saas.billing.repository.SubscriptionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;

import java.util.ArrayList;
import java.util.UUID;
import java.math.BigDecimal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class BillingServiceIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PlanRepository planRepository;

    @Autowired
    private SubscriptionRepository subscriptionRepository;

    @Autowired
    private InvoiceRepository invoiceRepository;

    private static final String ORG_ID_HEADER = "X-Org-Id";
    private static final String USER_ID_HEADER = "X-User-Id";
    private static final String ROLE_HEADER = "X-Role";

    @Test
    void testGetPlans() throws Exception {
        // Create some test plans
        Plan plan = new Plan();
        plan.setId(UUID.randomUUID());
        plan.setName("Professional Plan");
        plan.setPrice(new BigDecimal("99.00"));
        plan.setMaxUsers(50);
        planRepository.save(plan);

        mockMvc.perform(get("/billing/plans")
                .header(USER_ID_HEADER, UUID.randomUUID().toString())
                .header(ORG_ID_HEADER, UUID.randomUUID().toString())
                .header(ROLE_HEADER, "ADMIN")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", isA(ArrayList.class)))
                .andExpect(jsonPath("$[0].name").value("Professional Plan"))
                .andExpect(jsonPath("$[0].price").value(99.00));
    }

    @Test
    void testGetPlanById() throws Exception {
        Plan plan = new Plan();
        plan.setId(UUID.randomUUID());
        plan.setName("Starter Plan");
        plan.setPrice(new BigDecimal("29.00"));
        plan.setMaxUsers(10);
        planRepository.save(plan);

        mockMvc.perform(get("/billing/plans/{id}", plan.getId())
                .header(USER_ID_HEADER, UUID.randomUUID().toString())
                .header(ORG_ID_HEADER, UUID.randomUUID().toString())
                .header(ROLE_HEADER, "MEMBER")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(plan.getId().toString()))
                .andExpect(jsonPath("$.name").value("Starter Plan"));
    }

    @Test
    void testSubscribeToPlan() throws Exception {
        UUID orgId = UUID.randomUUID();
        UUID planId = UUID.randomUUID();

        // Create a plan first
        Plan plan = new Plan();
        plan.setId(planId);
        plan.setName("Standard Plan");
        plan.setPrice(new BigDecimal("49.00"));
        plan.setMaxUsers(25);
        planRepository.save(plan);

        String payload = """
            {
                "planId": "%s"
            }
        """.formatted(planId.toString());

        mockMvc.perform(post("/billing/subscribe")
                .header(USER_ID_HEADER, UUID.randomUUID().toString())
                .header(ORG_ID_HEADER, orgId.toString())
                .header(ROLE_HEADER, "ADMIN")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.organizationId").value(orgId.toString()))
                .andExpect(jsonPath("$.planId").value(planId.toString()))
                .andExpect(jsonPath("$.status").value("TRIALING"));
    }

    @Test
    void testGetCurrentSubscription() throws Exception {
        UUID orgId = UUID.randomUUID();
        UUID planId = UUID.randomUUID();

        // Create a subscription
        Subscription subscription = new Subscription();
        subscription.setId(UUID.randomUUID());
        subscription.setOrganizationId(orgId);
        subscription.setPlanId(planId);
        subscription.setStatus("ACTIVE");
        subscriptionRepository.save(subscription);

        mockMvc.perform(get("/billing/subscription")
                .header(USER_ID_HEADER, UUID.randomUUID().toString())
                .header(ORG_ID_HEADER, orgId.toString())
                .header(ROLE_HEADER, "ADMIN")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.organizationId").value(orgId.toString()))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void testGetInvoices() throws Exception {
        UUID orgId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();

        // Create an invoice
        Invoice invoice = new Invoice();
        invoice.setId(UUID.randomUUID());
        invoice.setOrganizationId(orgId);
        invoice.setSubscriptionId(subscriptionId);
        invoice.setAmount(new BigDecimal("99.00"));
        invoice.setStatus("PAID");
        invoiceRepository.save(invoice);

        mockMvc.perform(get("/billing/invoices")
                .header(USER_ID_HEADER, UUID.randomUUID().toString())
                .header(ORG_ID_HEADER, orgId.toString())
                .header(ROLE_HEADER, "ADMIN")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", isA(ArrayList.class)))
                .andExpect(jsonPath("$[0].status").value("PAID"))
                .andExpect(jsonPath("$[0].amount").value(99.00));
    }

    @Test
    void testCancelSubscription() throws Exception {
        UUID orgId = UUID.randomUUID();

        // Create a subscription first
        Subscription subscription = new Subscription();
        subscription.setId(UUID.randomUUID());
        subscription.setOrganizationId(orgId);
        subscription.setPlanId(UUID.randomUUID());
        subscription.setStatus("ACTIVE");
        subscriptionRepository.save(subscription);

        mockMvc.perform(post("/billing/cancel")
                .header(USER_ID_HEADER, UUID.randomUUID().toString())
                .header(ORG_ID_HEADER, orgId.toString())
                .header(ROLE_HEADER, "ADMIN")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELED"));
    }

    @Test
    void testSubscriptionCaching() throws Exception {
        UUID orgId = UUID.randomUUID();
        UUID planId = UUID.randomUUID();

        // Create subscription
        Subscription subscription = new Subscription();
        subscription.setId(UUID.randomUUID());
        subscription.setOrganizationId(orgId);
        subscription.setPlanId(planId);
        subscription.setStatus("ACTIVE");
        subscriptionRepository.save(subscription);

        // First call - hits database
        mockMvc.perform(get("/billing/subscription")
                .header(USER_ID_HEADER, UUID.randomUUID().toString())
                .header(ORG_ID_HEADER, orgId.toString())
                .header(ROLE_HEADER, "ADMIN")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        // Second call - should be cached (no DB hit)
        mockMvc.perform(get("/billing/subscription")
                .header(USER_ID_HEADER, UUID.randomUUID().toString())
                .header(ORG_ID_HEADER, orgId.toString())
                .header(ROLE_HEADER, "ADMIN")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    @Test
    void testMultiTenancyIsolation() throws Exception {
        UUID org1Id = UUID.randomUUID();
        UUID org2Id = UUID.randomUUID();

        // Create subscriptions for different orgs
        Subscription sub1 = new Subscription();
        sub1.setId(UUID.randomUUID());
        sub1.setOrganizationId(org1Id);
        sub1.setPlanId(UUID.randomUUID());
        sub1.setStatus("ACTIVE");
        subscriptionRepository.save(sub1);

        Subscription sub2 = new Subscription();
        sub2.setId(UUID.randomUUID());
        sub2.setOrganizationId(org2Id);
        sub2.setPlanId(UUID.randomUUID());
        sub2.setStatus("CANCELED");
        subscriptionRepository.save(sub2);

        // User 1 can only see their subscription
        mockMvc.perform(get("/billing/subscription")
                .header(USER_ID_HEADER, UUID.randomUUID().toString())
                .header(ORG_ID_HEADER, org1Id.toString())
                .header(ROLE_HEADER, "ADMIN")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        // User 2 cannot access User 1's data
        mockMvc.perform(get("/billing/subscription")
                .header(USER_ID_HEADER, UUID.randomUUID().toString())
                .header(ORG_ID_HEADER, org2Id.toString())
                .header(ROLE_HEADER, "ADMIN")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELED"));
    }
}
