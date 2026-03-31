# Testing Guide - SaaS Platform

## Overview

This document provides comprehensive testing strategies and instructions for the Multi-Tenant SaaS platform, covering unit tests, integration tests, end-to-end tests, and Angular frontend tests.

---

## Backend Testing Strategy

### 1. Unit Tests with Mockito

**Purpose**: Test service business logic in isolation

**Coverage Focus**:
- Service method behavior
- Exception handling
- Business rule validation
- State mutations

**Example Structure**:
```typescript
@Test
void testActionWithValidInput() {
    // Arrange - setup test data and mocks
    when(repository.findById(id)).thenReturn(Optional.of(entity));
    
    // Act - execute the method
    Result result = service.action(input);
    
    // Assert - verify the result
    assertEquals(expected, result);
    verify(repository, times(1)).save(any());
}
```

**Files**:
- `auth-service/src/test/java/.../AuthServiceUnitTests.java`
- `org-service/src/test/java/.../OrganizationServiceUnitTests.java`
- (Similar for other services)

### 2. Integration Tests with TestContainers

**Purpose**: Test service endpoints and database interactions

**Coverage Focus**:
- REST endpoints
- Database persistence
- Transaction handling
- Multi-tenancy enforcement

**Dependencies**:
```xml
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>testcontainers</artifactId>
    <version>1.17.6</version>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>postgresql</artifactId>
    <version>1.17.6</version>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>kafka</artifactId>
    <version>1.17.6</version>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>rabbitmq</artifactId>
    <version>1.17.6</version>
    <scope>test</scope>
</dependency>
```

**Files**:
- `auth-service/src/test/java/.../AuthServiceIntegrationTests.java`
- `org-service/src/test/java/.../OrganizationServiceIntegrationTests.java`
- `billing-service/src/test/java/.../BillingServiceIntegrationTests.java`
- `notification-service/src/test/java/.../NotificationServiceIntegrationTests.java`

**Docker Requirements**:
- PostgreSQL (test database)
- Kafka (message broker)
- RabbitMQ (message broker)
- Redis (cache)

### 3. Test Application Properties

**Location**: `src/test/resources/application-test.yml`

**Purpose**: Configure services for testing environment

**Key Configurations**:
```yaml
spring.jpa.hibernate.ddl-auto=create-drop  # Fresh DB per test
spring.flyway.enabled=true  # Run migrations
logging.level.root=WARN  # Reduce noise
server.port=0  # Random port assignment
```

---

## Running Tests

### Prerequisites

```bash
# Ensure Docker is running for test containers
docker-compose up -d

# Wait for services to be ready (30-60 seconds)
```

### Run All Tests

```bash
# All tests across all services
mvn clean test

# With coverage report
mvn clean test jacoco:report

# View coverage report
open target/site/jacoco/index.html
```

### Run Tests for Specific Service

```bash
cd auth-service
mvn clean test

cd ../organization-service
mvn clean test

cd ../billing-service
mvn clean test

cd ../notification-service
mvn clean test
```

### Run Specific Test Class

```bash
mvn test -Dtest=AuthServiceIntegrationTests
mvn test -Dtest=OrganizationServiceUnitTests
```

### Run Tests with Pattern

```bash
# All integration tests
mvn test -Dtest=*IntegrationTests

# All unit tests
mvn test -Dtest=*UnitTests
```

### Run with Specific Profile

```bash
mvn test -Dspring.profiles.active=test
```

---

## Test Coverage Targets

| Component | Target Coverage | Current |
|-----------|-----------------|---------|
| **Auth Service** | 80%+ | - |
| **Org Service** | 80%+ | - |
| **Billing Service** | 75%+ | - |
| **Notification Service** | 70%+ | - |
| **API Gateway** | 60%+ | - |

---

## Unit Test Examples

### AuthService Unit Tests

```java
@Test
void testRegisterNewUser() {
    // Mocked repository and password encoder
    when(userRepository.findByEmail(email)).thenReturn(Optional.empty());
    when(passwordEncoder.encode(password)).thenReturn(hashed);
    
    // Call service
    User result = authService.register(request);
    
    // Verify
    assertNotNull(result.getId());
    verify(userRepository).save(any(User.class));
}

@Test
void testLoginWithInvalidPasswordThrowsException() {
    // Setup
    when(userRepository.findByEmail(email)).thenReturn(Optional.of(user));
    when(passwordEncoder.matches(wrong, hash)).thenReturn(false);
    
    // Assert exception
    assertThrows(UnauthorizedException.class, () ->
        authService.login(request)
    );
}
```

### OrganizationService Unit Tests

```java
@Test
void testCreateOrganization() {
    // Creates org and adds owner as ADMIN member
    Organization result = orgService.create(request, ownerId);
    
    assert result.getName().equals(request.getName());
    assert result.getOwnerId().equals(ownerId);
    verify(membershipRepository).save(any(Membership.class));
}

@Test
void testRoleBasedAccess() {
    // ADMIN can manage members
    assertTrue(orgService.canManageMembers(adminMembership));
    
    // MEMBER cannot manage
    assertFalse(orgService.canManageMembers(memberMembership));
}
```

---

## Integration Test Examples

### AuthService Integration Tests

```java
@Test
void testUserRegistration() throws Exception {
    // POST /auth/register
    mockMvc.perform(post("/auth/register")
        .contentType(MediaType.APPLICATION_JSON)
        .content(registerPayload))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accessToken").exists())
        .andExpect(jsonPath("$.refreshToken").exists());
}

@Test
void testTokenRefresh() throws Exception {
    // POST /auth/refresh with valid refresh token
    mockMvc.perform(post("/auth/refresh")
        .contentType(MediaType.APPLICATION_JSON)
        .content(refreshPayload))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accessToken").exists());
}
```

### Billing Service Integration Tests

```java
@Test
void testSubscribeToPlan() throws Exception {
    // POST /billing/subscribe
    mockMvc.perform(post("/billing/subscribe")
        .header("X-Org-Id", orgId)
        .header("X-User-Id", userId)
        .header("X-Role", "ADMIN")
        .contentType(MediaType.APPLICATION_JSON)
        .content(payload))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("TRIALING"));
}
```

---

## End-to-End Test Scenarios

### Scenario 1: User Registration → Organization Creation → Billing

```bash
1. User registers via POST /auth/register
2. Receives JWT tokens
3. Calls POST /organizations to create org
4. Org Service publishes USER_CREATED event
5. Default organization created
6. User accesses billing plans via GET /billing/plans
7. Subscribes via POST /billing/subscribe
```

### Scenario 2: Team Collaboration

```bash
1. ADMIN user creates organization
2. ADMIN invites MANAGER via POST /organizations/{id}/invite
3. Email notification sent (logged in notification-service)
4. Manager accepts invitation
5. Manager invites MEMBER
6. MEMBER views shared subscription
```

### Scenario 3: Multi-Tenancy Isolation

```bash
1. Create two organizations (Org A, Org B)
2. Add User 1 to Org A, User 2 to Org B
3. User 1 tries to access Org B's members
4. Request blocked with 403 Forbidden
5. Verify User 1 only sees Org A data
```

---

## Angular Frontend Testing

### Unit Tests

**Files**:
- `src/app/core/services/*.service.spec.ts`
- `src/app/features/*/*.component.spec.ts`

**Example**:
```typescript
describe('AuthService', () => {
  let service: AuthService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [AuthService]
    });
    service = TestBed.inject(AuthService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  it('should login and store token', () => {
    service.login({ email, password }).subscribe(response => {
      expect(localStorage.getItem('accessToken')).toBeTruthy();
    });

    const req = httpMock.expectOne('http://localhost:8080/auth/login');
    req.flush({ accessToken: 'token', refreshToken: 'refresh' });
  });
});
```

### Run Angular Tests

```bash
cd angular_base

# Run all tests
npm test

# Run with coverage
npm test -- --code-coverage

# Run specific test
npm test -- --include='**/auth.service.spec.ts'

# Watch mode
npm test -- --watch
```

---

## Testcontainers Configuration

### Shared Test Configuration

Create a base class for all integration tests:

```java
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
public abstract class BaseIntegrationTest {
    
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>()
        .withDatabaseName("saas_test")
        .withUsername("admin")
        .withPassword("admin");

    @Container
    static KafkaContainer kafka = new KafkaContainer()
        .withExposedPorts(9092);

    @Container
    static GenericContainer<?> rabbitmq = new GenericContainer<>()
        .withImage("rabbitmq:3-management-alpine")
        .withExposedPorts(5672, 15672);

    @Container
    static GenericContainer<?> redis = new GenericContainer<>()
        .withImage("redis:7-alpine")
        .withExposedPorts(6379);
}
```

---

## CI/CD Integration

### GitHub Actions Example

```yaml
name: Tests

on: [push, pull_request]

jobs:
  test:
    runs-on: ubuntu-latest
    
    services:
      postgres:
        image: postgres:15
        env:
          POSTGRES_USER: admin
          POSTGRES_PASSWORD: admin
          POSTGRES_DB: saas_test
        options: >-
          --health-cmd pg_isready
          --health-interval 10s
          --health-timeout 5s
          --health-retries 5
        ports:
          - 5432:5432

    steps:
      - uses: actions/checkout@v3
      - uses: actions/setup-java@v3
        with:
          java-version: 21
          distribution: 'temurin'
      - run: mvn clean test --batch-mode
      - run: mvn jacoco:report
      - uses: codecov/codecov-action@v3
```

---

## Test Best Practices

### ✅ DO

- Test business logic, not implementation details
- Use meaningful test names (`testUserCannotLoginWithWrongPassword`)
- Keep tests focused and independent
- Use test data builders for complex objects
- Mock external dependencies
- Test edge cases and error scenarios
- Verify behavior, not state when possible

### ❌ DON'T

- Create tight coupling between tests
- Test framework code (test `@RequestMapping` routes)
- Use hardcoded test data
- Create tests that are harder to understand than the code
- Skip tests due to flakiness (fix the root cause)
- Test private methods directly (test through public API)

---

## Debugging Tests

### View Detailed Test Output

```bash
mvn test -X -e
```

### Print Debug Statements

```java
@Test
void debugTest() {
    System.out.println("Debug message: " + value);
    logger.debug("Detailed log: {}", variable);
}
```

### Run Single Test in IDE

Most IDEs allow right-click on test method → "Run Test"

### Mock HTTP Responses

```java
mockMvc.perform(get("/api/endpoint"))
    .andDo(print())  // Print request and response
    .andExpect(status().isOk());
```

---

## Coverage Reports

###Generate Coverage Report

```bash
mvn clean test jacoco:report
```

### View Coverage

```bash
# Open in browser
open target/site/jacoco/index.html
```

### Minimum Coverage Enforcement

```xml
<plugin>
    <groupId>org.jacoco</groupId>
    <artifactId>jacoco-maven-plugin</artifactId>
    <executions>
        <execution>
            <goals>
                <goal>check</goal>
            </goals>
            <configuration>
                <rules>
                    <rule>
                        <element>PACKAGE</element>
                        <minimumCoverage>75</minimumCoverage>
                    </rule>
                </rules>
            </configuration>
        </execution>
    </executions>
</plugin>
```

---

## Troubleshooting

### Issue: Tests fail with "Connection refused"

**Solution**: Ensure backend services are running
```bash
docker-compose up -d
```

### Issue: Testcontainers image download fails

**Solution**: Check internet connection and Docker daemon

### Issue: Tests are slow

**Solution**: 
- Use in-memory H2 for tests instead of PostgreSQL
- Run tests in parallel: `mvn test -T 1C`
- Profile slow tests: `mvn test -DreuseForks=false`

### Issue: Flaky tests

**Solution**:
- Add explicit waits before assertions
- Use `@DirtiesContext` to reset Spring context between tests
- Verify mock setup is complete

---

## Next Steps

1. **Implement** unit tests for all services
2. **Add** integration tests with Testcontainers
3. **Set up** CI/CD pipeline with test reporting
4. **Configure** coverage thresholds
5. **Document** test data and fixtures
6. **Add** performance tests with JMH
7. **Integrate** with code quality tools (SonarQube)

---

## Resources

- [Spring Boot Testing Guide](https://spring.io/guides/gs/testing-web/)
- [Testcontainers Documentation](https://www.testcontainers.org/)
- [JUnit 5 User Guide](https://junit.org/junit5/docs/current/user-guide/)
- [Mockito Documentation](https://javadoc.io/doc/org.mockito/mockito-core/latest/org/mockito/Mockito.html)
- [Angular Testing Guide](https://angular.io/guide/testing)

