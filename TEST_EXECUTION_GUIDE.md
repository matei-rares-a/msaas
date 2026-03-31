# Test Execution & Debugging Guide

Complete reference for running, monitoring, and debugging tests across the platform.

## Table of Contents

1. [Quick Start](#quick-start)
2. [Test Execution Commands](#test-execution-commands)
3. [Test Monitoring & Reporting](#test-monitoring--reporting)
4. [Debugging Test Failures](#debugging-test-failures)
5. [Coverage Analysis](#coverage-analysis)
6. [Performance Profiling](#performance-profiling)

---

## Quick Start

### First-Time Setup

```bash
# 1. Navigate to project root
cd c:\local_store\files\my_workspace\msaas

# 2. Ensure Docker is running
docker ps  # Should list containers, not error

# 3. Build all services first
mvn clean install -DskipTests

# 4. Run unit tests only (fast, ~30 seconds)
mvn test

# 5. View test summary
# Look for: [INFO] Tests run: XX, Failures: 0, Errors: 0
```

### Minimal Test Run (5 services)

```bash
# Auth Service tests only
cd spring_base/auth-service
mvn clean test

# Quick check: Did it work?
echo %ERRORLEVEL%  # Windows: 0 = success, non-zero = failure
```

---

## Test Execution Commands

### Unit Tests (Fast, No Docker)

```bash
# All unit tests
mvn test

# Auth service only
mvn test -pl auth-service

# Organization service only
mvn test -pl organization-service

# Skip tests in specific module
mvn test -pl !notification-service

# Run specific test class
mvn test -Dtest=AuthServiceUnitTests

# Run specific test method
mvn test -Dtest=AuthServiceUnitTests#testRegisterValidEmail
```

### Integration Tests (Requires Docker)

```bash
# All integration tests (including testcontainers)
mvn verify

# Specific service
cd auth-service && mvn verify

# Integration tests only (skip unit tests)
mvn verify -DskipUnitTests

# With detailed output
mvn verify -X

# Parallel execution (faster on multi-core)
mvn verify -T1C  # 1 thread per core
```

### End-to-End Tests

```bash
# Run all E2E tests
mvn test -Dtest=*E2ETest

# Run specific E2E scenario
mvn test -Dtest=UserJourneyE2ETest

# Run with test coverage
mvn test -Dtest=*E2ETest jacoco:report

# Run with environment variable for Docker
set TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
mvn test -Dtest=*E2ETest
```

### Full Test Suite (All Phases)

```bash
# Compile → Unit Tests → Integration Tests → Verify
mvn clean verify

# With coverage report
mvn clean verify jacoco:report

# Generate HTML coverage report
# Check: auth-service/target/site/jacoco/index.html
```

---

## Test Monitoring & Reporting

### Real-Time Test Output

```bash
# Show test names as they execute
mvn test -X

# Capture output to file
mvn test > test-results.log 2>&1

# View test results while running (tail)
mvn verify > test.log && tail -f test.log
```

### Test Reports

#### Surefire Report (Default)

```bash
# Generate Surefire HTML report
mvn surefire-report:report

# Open report in browser
start auth-service/target/site/surefire-report.html  # Windows
open auth-service/target/site/surefire-report.html   # macOS
```

#### JaCoCo Coverage Report

```bash
# Generate coverage report
mvn jacoco:report

# Open coverage HTML
start auth-service/target/site/jacoco/index.html

# View coverage by class
# Click through: com.example.demo → AuthService
```

#### Consolidated Report

```bash
# Run all verifications and reports
mvn clean verify site

# Main reports location: target/site/
# - index.html (overview)
# - surefire-report.html (test results)
# - jacoco/index.html (coverage)
```

### Viewing Test Results

```bash
# Summary (quick check)
mvn test | grep -E "Tests run:|BUILD"

# Detailed failures
mvn test | grep -A 10 "FAILURE"

# Count total tests
mvn test | grep -o "Tests run: [0-9]*" | head -1
```

---

## Debugging Test Failures

### Step 1: Identify Failure Type

```bash
# Run with full stack trace
mvn test -DtrimStackTrace=false

# Capture to diagnostic file
mvn test > diagnostics.log 2>&1
```

### Step 2: Common Failure Patterns

#### Docker Connection Failed
```
[ERROR] Test ERROR
Caused by: com.github.dockerjava.api.exception.DockerClientException: 
Could not connect to Docker

SOLUTION:
  1. Verify Docker running: docker ps
  2. Check Docker daemon (Windows): wsl --list --verbose
  3. Retry with explicit socket: 
     set DOCKER_HOST=npipe:////./pipe/docker_engine
     mvn test
```

#### Connection Timeout (Testcontainers)
```
[ERROR] Timeout waiting for testcontainers

SOLUTION:
  1. Increase timeout in application-test.yml:
     testcontainers:
       docker-client-strategy-timeout: 60000
  2. Check network: docker network list
  3. Rebuild image: docker pull postgres:15
```

#### Port Already in Use
```
[ERROR] Address already in use: 8080

SOLUTION:
  1. Ensure @SpringBootTest uses RANDOM_PORT:
     @SpringBootTest(webEnvironment = RANDOM_PORT)
  2. Check running services: netstat -ano | findstr :8080
  3. Kill process: taskkill /PID <pid> /F
```

#### Assertion Failed (Test Logic)
```
[ERROR] org.junit.jupiter.api.AssertionFailedError
Expected: <200> but was: <401>

SOLUTION:
  1. Check test credentials
  2. Verify token generation logic
  3. Add debugger breakpoint (see Step 3)
```

### Step 3: Interactive Debugging

```bash
# Run test with debugger listening on port 5005
mvn -Dmaven.surefire.debug test -Dtest=AuthServiceUnitTests

# In VS Code: Create launch config
# {
#   "name": "Debug Tests",
#   "type": "java",
#   "request": "attach",
#   "hostName": "localhost",
#   "port": 5005
# }

# Set breakpoint in test, then attach debugger
```

### Step 4: Inspect Database State

```bash
# For PostgreSQL Testcontainers, inspect:

# 1. View running containers
docker ps | grep postgres

# 2. Connect to container
docker exec -it <container_id> \
  psql -U postgres -d postgres \
  -c "SELECT * FROM users LIMIT 5;"

# 3. Check schema
docker exec -it <container_id> \
  psql -U postgres -d postgres \
  -c "\dt"

# 4. View migrations applied
docker exec -it <container_id> \
  psql -U postgres -d postgres \
  -c "SELECT * FROM flyway_schema_history;"
```

### Step 5: Add Diagnostic Logging

```java
// In test file, add logging:

@SpringBootTest
@Testcontainers
public class DiagnosticAuthTest {
    static Logger log = LoggerFactory.getLogger(DiagnosticAuthTest.class);
    
    @Test
    void testWithDiagnostics() {
        log.info("=== Test Start ===");
        
        RegisterRequest request = new RegisterRequest("test@test.com", "password");
        log.info("Register request: {}", request);
        
        ResponseEntity<AuthResponse> response = restTemplate.postForEntity(
            "/auth/register",
            request,
            AuthResponse.class
        );
        
        log.info("Response status: {}", response.getStatusCode());
        log.info("Response body: {}", response.getBody());
        
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }
}
```

Then run with debug output:
```bash
mvn test -Dtest=DiagnosticAuthTest -X | grep "=== Test"
```

---

## Coverage Analysis

### Generate Coverage Report

```bash
# 1. Run tests with coverage
mvn clean verify jacoco:report

# 2. View report
start auth-service/target/site/jacoco/index.html

# 3. Check coverage by file
# Green: >= 80% covered
# Yellow: 50-80% covered  
# Red: < 50% covered
```

### Coverage Interpretation

```
Example: AuthService.java

Line Coverage: 82%
  ✓ 82/100 lines executed during tests
  ✓ Good coverage

Branch Coverage: 75%
  ⚠ Some if/else paths not tested
  → Add tests for error cases

Missing Coverage (red lines in report):
  - Error handling branches
  - Exception cases
  - Edge conditions
```

### Minimum Coverage Enforcement

```bash
# Fail build if coverage below 75%
mvn verify -Djacoco.coveredratio=0.75

# Output:
# [INFO] All coverage goals met: Lines 78%, Branches 72%, Methods 80%
# 
# OR
#
# [ERROR] Coverage is below 75%: 68%
# [ERROR] BUILD FAILURE
```

### Coverage Trends

```bash
# Track coverage over time
for i in {1..5}; do
  echo "Run $i"
  mvn clean test jacoco:report | grep -E "Lines|Branches|Methods"
  echo "---"
done

# Output shows if coverage improving/degrading
```

---

## Performance Profiling

### Test Execution Time

```bash
# Show slowest tests
mvn test -DtestFailureIgnore=true | \
  grep -E "^\[INFO\].*Test.*took" | \
  sort -t ':' -k2 -rn | head -10

# Typical results:
# [INFO] RunWebTests took 4500ms
# [INFO] IntegrationTest took 3200ms
```

### Profile Individual Test

```bash
# Time single test
@Test
public void testPerformance() {
    long start = System.currentTimeMillis();
    
    // Test logic
    
    long duration = System.currentTimeMillis() - start;
    System.out.println("Test took: " + duration + "ms");
}

# Run and capture
mvn test -Dtest=PerformanceTest | grep "took:"
```

### Memory Usage

```bash
# Run tests with memory reporting
mvn test -Xmx1024m -XX:+PrintGCDetails

# View garbage collection activity
# Output includes: GC activity, memory usage
```

### Parallel Test Execution

```bash
# Run tests in parallel (faster)
mvn test -T1C  # 1 thread per CPU core

# Specify fixed thread count
mvn verify -T4  # 4 threads

# Results should be 2-4x faster
# Before: 120 seconds
# After: 30-60 seconds
```

### Identify Slow Tests

```java
// Create custom test listener
@ExtendWith(SlowTestListener.class)
public class Tests {
    @Test
    public void testOperation() { /* ... */ }
}

// Listener implementation
public class SlowTestListener implements TestWatcher {
    private long startTime;
    
    @Override
    public void testStarted(ExtensionContext context) {
        startTime = System.currentTimeMillis();
    }
    
    @Override
    public void testSuccessful(ExtensionContext context) {
        long duration = System.currentTimeMillis() - startTime;
        if (duration > 1000) {  // Slow if > 1 second
            System.out.println("SLOW: " + context.getDisplayName() + 
                             " took " + duration + "ms");
        }
    }
}
```

---

## Continuous Testing Strategy

### Pre-Commit Testing (Local)

```bash
#!/bin/bash
# .git/hooks/pre-commit

echo "Running unit tests..."
mvn test

if [ $? -ne 0 ]; then
    echo "Unit tests failed - commit blocked"
    exit 1
fi

echo "Tests passed - commit allowed"
exit 0
```

### CI/CD Pipeline Testing

```yaml
# .github/workflows/test.yml
name: Automated Testing

on: [push, pull_request]

jobs:
  unit-tests:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - uses: actions/setup-java@v3
        with:
          java-version: '21'
      - run: mvn test
  
  integration-tests:
    runs-on: ubuntu-latest
    services:
      - name: Docker  # For Testcontainers
        uses: docker://docker:dind
    steps:
      - uses: actions/checkout@v3
      - uses: actions/setup-java@v3
        with:
          java-version: '21'
      - run: mvn verify
  
  coverage:
    needs: [unit-tests, integration-tests]
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - uses: actions/setup-java@v3
        with:
          java-version: '21'
      - run: mvn clean verify jacoco:report
      - uses: codecov/codecov-action@v3
```

### Post-Deployment Testing (Production)

```bash
#!/bin/bash
# Run critical smoke tests against production

echo "Testing Production API..."

# Check auth service health
curl -f http://api.example.com/auth/health || exit 1

# Test login flow
RESPONSE=$(curl -s -X POST http://api.example.com/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"health@test.com","password":"test"}')

if [[ ! "$RESPONSE" =~ "accessToken" ]]; then
    echo "Production smoke test FAILED"
    exit 1
fi

echo "Production smoke test PASSED"
exit 0
```

---

## Complete Test Execution Templates

### Template 1: Daily Regression Testing

```bash
#!/bin/bash
# daily-test.sh

echo "=== Daily Regression Test Suite ==="
echo "Time: $(date)"
echo ""

# Level 1: Unit tests (fast)
echo "1. Running unit tests..."
mvn clean test
if [ $? -ne 0 ]; then echo "❌ Unit tests FAILED"; exit 1; fi
echo "✓ Unit tests passed"
echo ""

# Level 2: Integration tests (medium)
echo "2. Running integration tests..."
mvn verify
if [ $? -ne 0 ]; then echo "❌ Integration tests FAILED"; exit 1; fi
echo "✓ Integration tests passed"
echo ""

# Level 3: Coverage report
echo "3. Generating coverage report..."
mvn jacoco:report
echo "✓ Coverage report generated"
echo ""

# Level 4: Summary
echo "=== TEST SUITE COMPLETE ==="
echo "All tests passed successfully!"
echo "Coverage report: target/site/jacoco/index.html"
```

### Template 2: Pre-Release Testing

```bash
#!/bin/bash
# pre-release-test.sh

echo "=== Pre-Release Test Suite ==="

# Full verification
mvn clean verify \
  -Dorg.slf4j.simpleLogger.defaultLogLevel=info \
  -Djacoco.enabled=true \
  -T1C

# Check results
if [ $? -eq 0 ]; then
    echo "✓ All tests passed"
    echo "✓ Ready for release"
    echo ""
    echo "Next: Create git tag and build Docker images"
else
    echo "❌ Tests failed - DO NOT RELEASE"
    exit 1
fi
```

### Template 3: Troubleshooting Test Failures

```bash
#!/bin/bash
# debug-test.sh -- Run when tests fail

SERVICE=${1:-auth-service}
CLASS=${2:-}

echo "=== Debugging $SERVICE ==="

cd $SERVICE

# Step 1: Clean
echo "Step 1: Cleaning build..."
mvn clean

# Step 2: Compile
echo "Step 2: Compiling..."
mvn compile
if [ $? -ne 0 ]; then echo "Compilation failed"; exit 1; fi

# Step 3: Run with full output
echo "Step 3: Running tests with verbose output..."
if [ -z "$CLASS" ]; then
    mvn test -X -DtrimStackTrace=false
else
    mvn test -X -Dtest=$CLASS -DtrimStackTrace=false
fi

# Step 4: Generate diagnostic report
echo "Step 4: Generating diagnostic report..."
mvn surefire-report:report

echo ""
echo "Diagnostic files:"
echo "  - Logs: target/surefire-reports/"
echo "  - HTML Report: target/site/surefire-report.html"
```

---

## Checklist: Before Committing Code

- [ ] `mvn clean test` passes (0 failures)
- [ ] New unit tests written for new code
- [ ] Coverage did not decrease (check jacoco report)
- [ ] No compile warnings (`mvn clean compile 2>&1 | grep -i warning`)
- [ ] Integration tests pass: `mvn verify`
- [ ] Manual smoke test: start app and test key workflow
- [ ] Code review completed
- [ ] Branch name follows convention: `feature/X`, `bugfix/Y`

---

## Quick Reference: Common Commands

```bash
# Most useful commands
mvn clean               # Delete all compiled code
mvn compile             # Compile only
mvn test                # Unit tests only (30 sec)
mvn verify              # All tests (2 min)
mvn jacoco:report       # Coverage report
mvn clean verify site   # Everything + HTML reports

# Specific test
mvn test -Dtest=AuthServiceUnitTests#testLogin

# With output capture
mvn test > test.log 2>&1 && grep "Tests run:" test.log

# Fast execution
mvn verify -DskipITs   # Skip slow integration tests
mvn verify -T1C        # Parallel execution
```

