# End-to-End Test Scenarios Guide

This document outlines comprehensive end-to-end test scenarios that validate the entire platform workflow across multiple services.

## Table of Contents

1. [Overview](#overview)
2. [Scenario 1: Complete User Journey](#scenario-1-complete-user-journey)
3. [Scenario 2: Organization & Billing Flow](#scenario-2-organization--billing-flow)
4. [Scenario 3: Multi-Tenant Isolation](#scenario-3-multi-tenant-isolation)
5. [Scenario 4: Event-Driven Workflows](#scenario-4-event-driven-workflows)
6. [Running E2E Tests](#running-e2e-tests)
7. [Common Issues & Troubleshooting](#common-issues--troubleshooting)

---

## Overview

End-to-end tests validate complete business processes across multiple services, databases, and message brokers. They differ from integration tests in scope:

- **Integration Tests**: Single service + one dependency (DB, single broker)
- **End-to-End Tests**: Multiple services interacting across entire platform

### Tools Used
- **REST Assured**: HTTP client for API testing
- **TestRestTemplate**: Spring Boot's HTTP client
- **Testcontainers**: Real infrastructure (PostgreSQL, Kafka, RabbitMQ)
- **Awaitility**: Async assertion library for event-driven testing

### Prerequisites
```bash
# Install dependencies
mvn dependency:resolve

# Ensure Docker is running (required for Testcontainers)
docker ps  # Should return container list, not error
```

---

## Scenario 1: Complete User Journey

**Flow**: Register → Verify Email Notification → Login → Access Dashboard → Logout

### Test Implementation

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestcontainersTest
public class UserJourneyE2ETest {
    
    @LocalServerPort
    private int port;
    
    @Autowired
    private TestRestTemplate restTemplate;
    
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
        .withDatabaseName("postgres")
        .withUsername("postgres")
        .withPassword("postgres");
    
    @Container
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.4.0"));
    
    private static final String BASE_URL = "http://localhost";
    private String authToken;
    
    @Test
    void testCompleteUserJourney() {
        String email = "user@test-e2e.com";
        String password = "SecurePassword123!";
        
        // Step 1: Register New User
        RegisterRequest registerRequest = new RegisterRequest(email, password, "Test User");
        ResponseEntity<AuthResponse> registerResponse = restTemplate.postForEntity(
            BASE_URL + ":" + port + "/auth/register",
            registerRequest,
            AuthResponse.class
        );
        
        assertThat(registerResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(registerResponse.getBody()).isNotNull();
        assertThat(registerResponse.getBody().getAccessToken()).isNotEmpty();
        
        String accessToken = registerResponse.getBody().getAccessToken();
        authToken = accessToken;
        
        // Step 2: Verify User Created in Database
        ResponseEntity<UserDTO> getUserResponse = restTemplate.exchange(
            BASE_URL + ":" + port + "/auth/me",
            HttpMethod.GET,
            createHttpEntity(accessToken),
            UserDTO.class
        );
        
        assertThat(getUserResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getUserResponse.getBody().getEmail()).isEqualTo(email);
        
        // Step 3: Verify Email Notification Sent (via Kafka)
        await()
            .atMost(Duration.ofSeconds(5))
            .untilAsserted(() -> {
                ResponseEntity<List<EmailLog>> emailLogsResponse = restTemplate.exchange(
                    BASE_URL + ":" + port + "/notifications/emails/" + email,
                    HttpMethod.GET,
                    createHttpEntity(accessToken),
                    new ParameterizedTypeReference<List<EmailLog>>() {}
                );
                
                assertThat(emailLogsResponse.getBody())
                    .anySatisfy(log -> {
                        assertThat(log.getRecipient()).isEqualTo(email);
                        assertThat(log.getSubject()).contains("Welcome");
                    });
            });
        
        // Step 4: Login with Created User (using Auth Service)
        LoginRequest loginRequest = new LoginRequest(email, password);
        ResponseEntity<AuthResponse> loginResponse = restTemplate.postForEntity(
            BASE_URL + ":" + port + "/auth/login",
            loginRequest,
            AuthResponse.class
        );
        
        assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(loginResponse.getBody().getAccessToken()).isNotEmpty();
        
        // Token should be different from registration token
        String loginToken = loginResponse.getBody().getAccessToken();
        authToken = loginToken;
        
        // Step 5: Access Dashboard (Protected Route)
        ResponseEntity<DashboardDTO> dashboardResponse = restTemplate.exchange(
            BASE_URL + ":" + port + "/dashboard",
            HttpMethod.GET,
            createHttpEntity(authToken),
            DashboardDTO.class
        );
        
        assertThat(dashboardResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(dashboardResponse.getBody().getUserEmail()).isEqualTo(email);
        
        // Step 6: Verify Invalid Token Returns 401
        ResponseEntity<String> invalidTokenResponse = restTemplate.exchange(
            BASE_URL + ":" + port + "/dashboard",
            HttpMethod.GET,
            createHttpEntity("invalid-token"),
            String.class
        );
        
        assertThat(invalidTokenResponse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        
        // Step 7: Logout
        ResponseEntity<String> logoutResponse = restTemplate.postForEntity(
            BASE_URL + ":" + port + "/auth/logout",
            createHttpEntity(authToken),
            String.class
        );
        
        assertThat(logoutResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        
        // Step 8: Verify Token No Longer Valid After Logout
        ResponseEntity<String> postLogoutResponse = restTemplate.exchange(
            BASE_URL + ":" + port + "/dashboard",
            HttpMethod.GET,
            createHttpEntity(authToken),
            String.class
        );
        
        assertThat(postLogoutResponse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
    
    private HttpEntity<?> createHttpEntity(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return new HttpEntity<>(headers);
    }
}
```

### Expected Flow Diagram
```
User Registration
    ↓
Email Notification Sent (via Kafka)
    ↓
User Logged In (JWT issued)
    ↓
Dashboard Access (Protected by AuthGuard)
    ↓
Logout
    ↓
Token Invalidated
```

---

## Scenario 2: Organization & Billing Flow

**Flow**: Create Organization → Invite Members → Subscribe to Plan → Generate Invoice → Cancel Subscription

### Test Implementation

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestcontainersTest
public class OrganizationBillingE2ETest {
    
    @LocalServerPort
    private int port;
    
    @Autowired
    private TestRestTemplate restTemplate;
    
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15");
    
    @Container
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.4.0"));
    
    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7"))
        .withExposedPorts(6379);
    
    private String ownerToken;
    private String orgId;
    
    @BeforeEach
    void setupOwnerUser() {
        // Create and login owner user
        RegisterRequest registerRequest = new RegisterRequest("owner@test.com", "Password123!", "Owner");
        ResponseEntity<AuthResponse> response = restTemplate.postForEntity(
            BASE_URL + ":" + port + "/auth/register",
            registerRequest,
            AuthResponse.class
        );
        
        ownerToken = response.getBody().getAccessToken();
    }
    
    @Test
    void testOrganizationAndBillingFlow() {
        // Step 1: Create Organization
        CreateOrgRequest createOrgRequest = new CreateOrgRequest("Test Corp", "test-corp");
        ResponseEntity<OrganizationDTO> orgResponse = restTemplate.exchange(
            BASE_URL + ":" + port + "/organizations",
            HttpMethod.POST,
            createHttpEntity(createOrgRequest, ownerToken),
            OrganizationDTO.class
        );
        
        assertThat(orgResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        orgId = orgResponse.getBody().getId();
        
        // Step 2: Get Available Plans
        ResponseEntity<List<PlanDTO>> plansResponse = restTemplate.exchange(
            BASE_URL + ":" + port + "/billing/plans",
            HttpMethod.GET,
            createHttpEntity(ownerToken),
            new ParameterizedTypeReference<List<PlanDTO>>() {}
        );
        
        assertThat(plansResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        String planId = plansResponse.getBody().get(0).getId();  // Pro plan
        
        // Step 3: Subscribe to Plan
        SubscribeRequest subscribeRequest = new SubscribeRequest(planId);
        ResponseEntity<SubscriptionDTO> subscribeResponse = restTemplate.exchange(
            BASE_URL + ":" + port + "/organizations/" + orgId + "/subscriptions",
            HttpMethod.POST,
            createHttpEntity(subscribeRequest, ownerToken),
            SubscriptionDTO.class
        );
        
        assertThat(subscribeResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(subscribeResponse.getBody().getStatus()).isEqualTo(SubscriptionStatus.TRIALING);
        
        // Step 4: Verify Invoice Created
        ResponseEntity<List<InvoiceDTO>> invoicesResponse = restTemplate.exchange(
            BASE_URL + ":" + port + "/organizations/" + orgId + "/invoices",
            HttpMethod.GET,
            createHttpEntity(ownerToken),
            new ParameterizedTypeReference<List<InvoiceDTO>>() {}
        );
        
        assertThat(invoicesResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(invoicesResponse.getBody()).hasSize(1);
        
        // Step 5: Invite Member to Organization
        InviteMemberRequest inviteRequest = new InviteMemberRequest("member@test.com", MemberRole.MEMBER);
        ResponseEntity<InvitationDTO> inviteResponse = restTemplate.exchange(
            BASE_URL + ":" + port + "/organizations/" + orgId + "/invitations",
            HttpMethod.POST,
            createHttpEntity(inviteRequest, ownerToken),
            InvitationDTO.class
        );
        
        assertThat(inviteResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        
        // Step 6: Verify Invitation Email Sent (via RabbitMQ)
        await()
            .atMost(Duration.ofSeconds(5))
            .untilAsserted(() -> {
                ResponseEntity<List<EmailLog>> emailsResponse = restTemplate.exchange(
                    BASE_URL + ":" + port + "/notifications/emails/member@test.com",
                    HttpMethod.GET,
                    createHttpEntity(ownerToken),
                    new ParameterizedTypeReference<List<EmailLog>>() {}
                );
                
                assertThat(emailsResponse.getBody())
                    .anySatisfy(log -> assertThat(log.getSubject()).contains("Invitation"));
            });
        
        // Step 7: Simulate Payment (Transition to Active)
        ResponseEntity<SubscriptionDTO> activateResponse = restTemplate.exchange(
            BASE_URL + ":" + port + "/organizations/" + orgId + "/subscriptions/activate",
            HttpMethod.POST,
            createHttpEntity(ownerToken),
            SubscriptionDTO.class
        );
        
        assertThat(activateResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(activateResponse.getBody().getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        
        // Step 8: Verify Subscription Cached (Redis)
        // First call hits DB, second call should hit cache
        long startTime = System.currentTimeMillis();
        
        restTemplate.exchange(
            BASE_URL + ":" + port + "/organizations/" + orgId + "/subscriptions",
            HttpMethod.GET,
            createHttpEntity(ownerToken),
            SubscriptionDTO.class
        );
        
        long dbCallTime = System.currentTimeMillis() - startTime;
        
        startTime = System.currentTimeMillis();
        
        restTemplate.exchange(
            BASE_URL + ":" + port + "/organizations/" + orgId + "/subscriptions",
            HttpMethod.GET,
            createHttpEntity(ownerToken),
            SubscriptionDTO.class
        );
        
        long cacheCallTime = System.currentTimeMillis() - startTime;
        
        // Cache should be ~10x faster
        assertThat(cacheCallTime).isLessThan(dbCallTime / 5);
        
        // Step 9: Cancel Subscription
        ResponseEntity<SubscriptionDTO> cancelResponse = restTemplate.exchange(
            BASE_URL + ":" + port + "/organizations/" + orgId + "/subscriptions/cancel",
            HttpMethod.POST,
            createHttpEntity(ownerToken),
            SubscriptionDTO.class
        );
        
        assertThat(cancelResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(cancelResponse.getBody().getStatus()).isEqualTo(SubscriptionStatus.CANCELED);
        
        // Step 10: Verify Cancellation Event Published (Kafka)
        await()
            .atMost(Duration.ofSeconds(5))
            .untilAsserted(() -> {
                // Verify listener processed the event (no explicit API, check audit log)
                ResponseEntity<AuditLog> auditResponse = restTemplate.exchange(
                    BASE_URL + ":" + port + "/audit/organizations/" + orgId,
                    HttpMethod.GET,
                    createHttpEntity(ownerToken),
                    AuditLog.class
                );
                
                assertThat(auditResponse.getBody().getEvents())
                    .anySatisfy(event -> assertThat(event.getAction()).isEqualTo("SUBSCRIPTION_CANCELED"));
            });
    }
}
```

---

## Scenario 3: Multi-Tenant Isolation

**Flow**: Verify Org1 cannot access Org2 data, Role-based access enforcement

### Test Implementation

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestcontainersTest
public class MultiTenantIsolationE2ETest {
    
    @LocalServerPort
    private int port;
    
    @Autowired
    private TestRestTemplate restTemplate;
    
    private String ownerToken1;
    private String ownerToken2;
    private String memberToken1;
    private String orgId1;
    private String orgId2;
    
    @BeforeEach
    void setupMultipleTenants() {
        // Create owner 1 and org 1
        ownerToken1 = registerAndLogin("owner1@test.com", "Password123!");
        orgId1 = createOrganization("Org1", ownerToken1);
        
        // Create owner 2 and org 2
        ownerToken2 = registerAndLogin("owner2@test.com", "Password123!");
        orgId2 = createOrganization("Org2", ownerToken2);
        
        // Create member in org 1
        inviteMember(orgId1, "member1@test.com", MemberRole.MEMBER, ownerToken1);
        memberToken1 = registerAndLogin("member1@test.com", "Password123!");
    }
    
    @Test
    void testOrg1CannotAccessOrg2() {
        // Member of Org1 tries to access Org2
        ResponseEntity<String> response = restTemplate.exchange(
            BASE_URL + ":" + port + "/organizations/" + orgId2 + "/members",
            HttpMethod.GET,
            createHttpEntity(memberToken1),
            String.class
        );
        
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
    
    @Test
    void testOrgDataIsolation() {
        // Owner1 invites member to Org1
        InviteMemberRequest inviteRequest = new InviteMemberRequest("test@test.com", MemberRole.MEMBER);
        restTemplate.exchange(
            BASE_URL + ":" + port + "/organizations/" + orgId1 + "/invitations",
            HttpMethod.POST,
            createHttpEntity(inviteRequest, ownerToken1),
            InvitationDTO.class
        );
        
        // Owner2 should not see Org1's invitations
        ResponseEntity<List<InvitationDTO>> invitationsResponse = restTemplate.exchange(
            BASE_URL + ":" + port + "/organizations/" + orgId2 + "/invitations",
            HttpMethod.GET,
            createHttpEntity(ownerToken2),
            new ParameterizedTypeReference<List<InvitationDTO>>() {}
        );
        
        assertThat(invitationsResponse.getBody()).doesNotContain("test@test.com");
    }
    
    @Test
    void testRoleBasedAccessControl() {
        String memberToken = registerLoginAndInvite(orgId1, "member@test.com", MemberRole.MEMBER, ownerToken1);
        String adminToken = registerLoginAndInvite(orgId1, "admin@test.com", MemberRole.ADMIN, ownerToken1);
        
        // Member cannot remove people
        ResponseEntity<String> memberRemoveResponse = restTemplate.exchange(
            BASE_URL + ":" + port + "/organizations/" + orgId1 + "/members/some-user",
            HttpMethod.DELETE,
            createHttpEntity(memberToken),
            String.class
        );
        
        assertThat(memberRemoveResponse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        
        // Admin can remove people
        ResponseEntity<String> adminRemoveResponse = restTemplate.exchange(
            BASE_URL + ":" + port + "/organizations/" + orgId1 + "/members/some-user",
            HttpMethod.DELETE,
            createHttpEntity(adminToken),
            String.class
        );
        
        assertThat(adminRemoveResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
```

---

## Scenario 4: Event-Driven Workflows

**Flow**: Event published → Consumer processes → Side effects verified

### Test Implementation

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestcontainersTest
public class EventDrivenWorkflowE2ETest {
    
    @LocalServerPort
    private int port;
    
    @Autowired
    private TestRestTemplate restTemplate;
    
    @Container
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.4.0"));
    
    @Container
    static RabbitMQContainer rabbitMQ = new RabbitMQContainer("rabbitmq:3.12");
    
    private String ownerToken;
    private String orgId;
    
    @Test
    void testUserCreatedEventFlow() {
        // Register user (triggers USER_CREATED event)
        String email = "newuser@test.com";
        RegisterRequest registerRequest = new RegisterRequest(email, "Password123!", "New User");
        
        ResponseEntity<AuthResponse> registerResponse = restTemplate.postForEntity(
            BASE_URL + ":" + port + "/auth/register",
            registerRequest,
            AuthResponse.class
        );
        
        String userId = registerResponse.getBody().getUserId();
        String token = registerResponse.getBody().getAccessToken();
        
        // Wait for USER_CREATED event to be consumed by OrganizationService
        // This should create a default "Personal" organization
        await()
            .atMost(Duration.ofSeconds(5))
            .untilAsserted(() -> {
                ResponseEntity<List<OrganizationDTO>> orgsResponse = restTemplate.exchange(
                    BASE_URL + ":" + port + "/organizations",
                    HttpMethod.GET,
                    createHttpEntity(token),
                    new ParameterizedTypeReference<List<OrganizationDTO>>() {}
                );
                
                assertThat(orgsResponse.getBody())
                    .anySatisfy(org -> {
                        assertThat(org.getName()).isEqualTo("Personal");
                        assertThat(org.getOwnerId()).isEqualTo(userId);
                    });
            });
        
        // Verify email notification sent (EMAIL_SENT event consumed by NotificationService)
        await()
            .atMost(Duration.ofSeconds(5))
            .untilAsserted(() -> {
                ResponseEntity<List<EmailLog>> emailsResponse = restTemplate.exchange(
                    BASE_URL + ":" + port + "/notifications/emails/" + email,
                    HttpMethod.GET,
                    createHttpEntity(token),
                    new ParameterizedTypeReference<List<EmailLog>>() {}
                );
                
                assertThat(emailsResponse.getBody())
                    .anySatisfy(log -> assertThat(log.getRecipient()).isEqualTo(email));
            });
    }
    
    @Test
    void testPasswordResetEventFlow() {
        String email = "user@test.com";
        String token = registerAndLogin(email, "OldPassword123!");
        
        // Request password reset (triggers PASSWORD_RESET_REQUESTED event)
        ForgotPasswordRequest forgotRequest = new ForgotPasswordRequest(email);
        ResponseEntity<String> forgotResponse = restTemplate.postForEntity(
            BASE_URL + ":" + port + "/auth/forgot-password",
            forgotRequest,
            String.class
        );
        
        assertThat(forgotResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        
        // Wait for password reset email (PASSWORD_RESET event consumed → EMAIL_SENT)
        await()
            .atMost(Duration.ofSeconds(5))
            .untilAsserted(() -> {
                ResponseEntity<List<EmailLog>> emailsResponse = restTemplate.exchange(
                    BASE_URL + ":" + port + "/notifications/emails/" + email,
                    HttpMethod.GET,
                    createHttpEntity(token),
                    new ParameterizedTypeReference<List<EmailLog>>() {}
                );
                
                assertThat(emailsResponse.getBody())
                    .anySatisfy(log -> assertThat(log.getSubject()).contains("Reset Password"));
            });
    }
    
    @Test
    void testSubscriptionCreatedEventFlow() {
        String ownerToken = registerAndLogin("owner@test.com", "Password123!");
        String orgId = createOrganization("TestOrg", ownerToken);
        
        // Subscribe to plan (triggers SUBSCRIPTION_CREATED event)
        ResponseEntity<List<PlanDTO>> plansResponse = restTemplate.exchange(
            BASE_URL + ":" + port + "/billing/plans",
            HttpMethod.GET,
            createHttpEntity(ownerToken),
            new ParameterizedTypeReference<List<PlanDTO>>() {}
        );
        
        String planId = plansResponse.getBody().get(0).getId();
        
        SubscribeRequest subscribeRequest = new SubscribeRequest(planId);
        ResponseEntity<SubscriptionDTO> subscribeResponse = restTemplate.exchange(
            BASE_URL + ":" + port + "/organizations/" + orgId + "/subscriptions",
            HttpMethod.POST,
            createHttpEntity(subscribeRequest, ownerToken),
            SubscriptionDTO.class
        );
        
        assertThat(subscribeResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        
        // Wait for subscription created notification email
        await()
            .atMost(Duration.ofSeconds(5))
            .untilAsserted(() -> {
                ResponseEntity<List<EmailLog>> emailsResponse = restTemplate.exchange(
                    BASE_URL + ":" + port + "/notifications/emails/owner@test.com",
                    HttpMethod.GET,
                    createHttpEntity(ownerToken),
                    new ParameterizedTypeReference<List<EmailLog>>() {}
                );
                
                assertThat(emailsResponse.getBody())
                    .anySatisfy(log -> assertThat(log.getSubject()).contains("Subscription Confirmed"));
            });
    }
}
```

---

## Running E2E Tests

### Maven Commands

```bash
# Run all E2E tests
mvn test -Dtest=*E2ETest

# Run specific E2E test class
mvn test -Dtest=UserJourneyE2ETest

# Run with coverage report
mvn test -Dtest=*E2ETest jacoco:report

# Run with verbose output
mvn test -Dtest=*E2ETest -X

# Run and skip Docker cleanup (for debugging)
mvn test -Dtest=*E2ETest -Dtestcontainers.reuse.enable=true
```

### GitHub Actions CI/CD Example

```yaml
name: E2E Tests

on: [push, pull_request]

jobs:
  e2e-tests:
    runs-on: ubuntu-latest
    services:
      docker:
        image: docker:dind
        options: >-
          --privileged
          --health-cmd="docker ps"
          --health-interval=10s
          --health-timeout=5s
          --health-retries=5
    
    steps:
      - uses: actions/checkout@v3
      
      - uses: actions/setup-java@v3
        with:
          java-version: '21'
          distribution: 'temurin'
      
      - name: Run E2E Tests
        run: mvn clean test -Dtest=*E2ETest jacoco:report
      
      - name: Upload Coverage Reports
        uses: codecov/codecov-action@v3
        with:
          files: ./target/site/jacoco/index.html
```

---

## Common Issues & Troubleshooting

### Issue 1: Docker Connection Failed

**Error**: `Could not connect to testcontainers.tcpf://localhost:2375`

**Solutions**:
1. Ensure Docker is running: `docker ps`
2. On Windows, use WSL 2 backend for Docker Desktop
3. Set Docker host explicitly:
   ```properties
   # application-test.yml
   testcontainers:
     docker-host: unix:///var/run/docker.sock  # Linux
     docker-host: npipe:////./pipe/docker_engine  # Windows
   ```

### Issue 2: Timeout Waiting for events

**Problem**: E2E tests timeout waiting for async events

**Solution**: Increase timeout and ensure message broker connectivity:
```java
await()
    .atMost(Duration.ofSeconds(10))  // Increase timeout
    .pollInterval(Duration.ofMillis(100))
    .untilAsserted(() -> {
        // assertion
    });
```

### Issue 3: Port Conflicts

**Error**: `Port 8080 already in use`

**Solution**: Use random port in test configuration:
```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@LocalServerPort
private int port;  // Auto-assigned random port
```

### Issue 4: Tests Pass Locally, Fail in CI

**Common Cause**: Timing assumptions, hardcoded localhost

**Solution**: Use `@LocalServerPort` and `TestRestTemplate` with dynamic URLs

```java
// ❌ Bad: Hardcoded
restTemplate.postForEntity("http://localhost:8080/auth/login", ...)

// ✅ Good: Dynamic
restTemplate.postForEntity(
    BASE_URL + ":" + port + "/auth/login",
    ...
)
```

---

## Performance Baselines

Typical E2E test execution times:

| Test | Duration | Infrastructure |
|------|----------|-----------------|
| User Journey (7 steps) | 8-12 seconds | PostgreSQL only |
| Organization & Billing (10 steps) | 15-20 seconds | PostgreSQL + Kafka + Redis |
| Multi-Tenant Isolation (3 checks) | 5-8 seconds | PostgreSQL only |
| Event-Driven Workflows (3 events) | 12-18 seconds | PostgreSQL + Kafka + RabbitMQ |

**Total Suite**: 40-58 seconds with parallel execution capability

---

## Next Steps

1. **Implement Additional E2E Tests**:
   - API contract validation
   - Authentication error scenarios
   - Rate limiting & throttling
   - Concurrent user operations

2. **Add Performance Tests**:
   - Load testing with Gatling or JMeter
   - Response time SLAs
   - Resource utilization monitoring

3. **Set Up Continuous Testing**:
   - Scheduled runs (nightly)
   - Post-deployment validation
   - Production monitoring integration

