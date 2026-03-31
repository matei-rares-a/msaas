1. FULL DATABASE SCHEMA (PostgreSQL)

Use:

snake_case
UUID as primary keys
organization_id everywhere (multi-tenancy)
audit fields on all tables
🔐 Auth Service Schema
CREATE TABLE users (
    id UUID PRIMARY KEY,
    email VARCHAR(255) UNIQUE NOT NULL,
    password_hash TEXT NOT NULL,
    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

CREATE TABLE refresh_tokens (
    id UUID PRIMARY KEY,
    user_id UUID REFERENCES users(id),
    token TEXT NOT NULL,
    expires_at TIMESTAMP,
    created_at TIMESTAMP
);

CREATE TABLE password_reset_tokens (
    id UUID PRIMARY KEY,
    user_id UUID REFERENCES users(id),
    token TEXT,
    expires_at TIMESTAMP,
    created_at TIMESTAMP
);
🏢 Organization Service Schema
CREATE TABLE organizations (
    id UUID PRIMARY KEY,
    name VARCHAR(255),
    owner_id UUID,
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

CREATE TABLE memberships (
    id UUID PRIMARY KEY,
    user_id UUID,
    organization_id UUID,
    role VARCHAR(50), -- ADMIN, MANAGER, MEMBER
    created_at TIMESTAMP,
    UNIQUE(user_id, organization_id)
);

CREATE TABLE invitations (
    id UUID PRIMARY KEY,
    email VARCHAR(255),
    organization_id UUID,
    role VARCHAR(50),
    token TEXT,
    expires_at TIMESTAMP,
    created_at TIMESTAMP
);
💳 Billing Service Schema (Simulated)
CREATE TABLE plans (
    id UUID PRIMARY KEY,
    name VARCHAR(100),
    price DECIMAL,
    max_users INT,
    features JSONB,
    created_at TIMESTAMP
);

CREATE TABLE subscriptions (
    id UUID PRIMARY KEY,
    organization_id UUID,
    plan_id UUID,
    status VARCHAR(50), -- trialing, active, past_due, canceled
    start_date TIMESTAMP,
    end_date TIMESTAMP,
    created_at TIMESTAMP
);

CREATE TABLE invoices (
    id UUID PRIMARY KEY,
    organization_id UUID,
    subscription_id UUID,
    amount DECIMAL,
    status VARCHAR(50), -- paid, unpaid
    issued_at TIMESTAMP
);

CREATE TABLE payments (
    id UUID PRIMARY KEY,
    invoice_id UUID,
    status VARCHAR(50), -- success, failed
    processed_at TIMESTAMP
);
📧 Notification Service Schema
CREATE TABLE email_logs (
    id UUID PRIMARY KEY,
    to_email VARCHAR(255),
    subject VARCHAR(255),
    body TEXT,
    status VARCHAR(50),
    created_at TIMESTAMP
);
🧠 Shared Tables (Optional)
CREATE TABLE audit_logs (
    id UUID PRIMARY KEY,
    user_id UUID,
    action VARCHAR(255),
    entity VARCHAR(255),
    entity_id UUID,
    created_at TIMESTAMP
);
🧱 2. MICROSERVICE FOLDER STRUCTURE

Root:

/saas-platform
  /api-gateway
  /auth-service
  /organization-service
  /billing-service
  /notification-service
  /shared-lib
  docker-compose.yml
📦 Each Service Structure
/auth-service
  /src/main/java/com/app/auth
    /controller
    /service
    /repository
    /dto
    /mapper
    /entity
    /security
    /config
    /exception
    /event
  /src/main/resources
    application.yml
  Dockerfile

Same structure for all services.

📚 Shared Library (IMPORTANT for “senior look”)
/shared-lib
  /dto
  /events
  /security
  /util

Contains:

JWT utilities
Event classes
Common DTOs
Base exception classes
🔄 3. EVENT-DRIVEN ARCHITECTURE
⚖️ Kafka vs RabbitMQ Decision

Use both (this looks VERY senior if done right):

🟢 Kafka (high throughput, async, eventual consistency)

Use for:

Billing events
Audit logs
Analytics
Subscription lifecycle
🔵 RabbitMQ (low latency, command/event)

Use for:

Email sending
User invitations
Password reset
Immediate reactions
📡 Event Flow Examples
🔐 User Registration Flow
Auth Service:
creates user
emits event → USER_CREATED (Kafka)
Organization Service:
listens → creates default org
🏢 Invite User Flow
Organization Service:
emits → USER_INVITED (RabbitMQ)
Notification Service:
sends email (stored in DB)
💳 Subscription Flow
Billing Service:
creates subscription
emits → SUBSCRIPTION_CREATED (Kafka)
Organization Service:
updates limits
Notification Service:
sends confirmation email
📡 Event Definitions
Kafka Events
{
  "eventType": "SUBSCRIPTION_CREATED",
  "organizationId": "...",
  "planId": "...",
  "timestamp": "..."
}
{
  "eventType": "USER_CREATED",
  "userId": "...",
  "email": "..."
}
RabbitMQ Events
{
  "eventType": "USER_INVITED",
  "email": "...",
  "organizationId": "...",
  "inviteToken": "..."
}
{
  "eventType": "PASSWORD_RESET",
  "email": "...",
  "token": "..."
}
⚙️ 4. DOCKER COMPOSE (INFRA)
version: '3.8'

services:
  postgres:
    image: postgres:15
    environment:
      POSTGRES_DB: saas
      POSTGRES_USER: admin
      POSTGRES_PASSWORD: admin
    ports:
      - "5432:5432"

  kafka:
    image: bitnami/kafka:latest
    ports:
      - "9092:9092"

  rabbitmq:
    image: rabbitmq:3-management
    ports:
      - "5672:5672"
      - "15672:15672"

  redis:
    image: redis:latest
    ports:
      - "6379:6379"
🧠 5. MULTI-TENANCY ENFORCEMENT

Mandatory in ALL services:

Extract organization_id from JWT
Inject into queries
Never trust frontend
🔐 6. SECURITY FLOW
User logs in → gets JWT
Gateway validates JWT
Gateway forwards:
user_id
organization_id
role

via headers:

X-User-Id
X-Org-Id
X-Role
🧪 7. TESTING STRATEGY
Use Testcontainers:
PostgreSQL
Kafka
RabbitMQ

8. SPRING BOOT BASE TEMPLATE (APPLY TO ALL SERVICES)
Dependencies (Gradle)
dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-web'
    implementation 'org.springframework.boot:spring-boot-starter-security'
    implementation 'org.springframework.boot:spring-boot-starter-data-jpa'
    implementation 'org.springframework.boot:spring-boot-starter-validation'

    implementation 'org.springframework.kafka:spring-kafka'
    implementation 'org.springframework.boot:spring-boot-starter-amqp'

    implementation 'org.postgresql:postgresql'
    implementation 'org.projectlombok:lombok'

    implementation 'org.mapstruct:mapstruct:1.5.5.Final'
    annotationProcessor 'org.mapstruct:mapstruct-processor:1.5.5.Final'

    implementation 'org.springdoc:springdoc-openapi-starter-webmvc-ui:2.5.0'

    testImplementation 'org.testcontainers:postgresql'
}
⚙️ 9. APPLICATION.YML TEMPLATE
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/saas
    username: admin
    password: admin

  jpa:
    hibernate:
      ddl-auto: validate
    show-sql: true

  kafka:
    bootstrap-servers: localhost:9092

  rabbitmq:
    host: localhost
    port: 5672

server:
  port: 8081
🔐 10. SECURITY CONFIG (JWT)
JwtFilter
@Component
@RequiredArgsConstructor
public class JwtFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
        throws ServletException, IOException {

        String token = request.getHeader("Authorization");

        if (token != null && token.startsWith("Bearer ")) {
            token = token.substring(7);

            String userId = jwtService.extractUserId(token);
            String role = jwtService.extractRole(token);
            String orgId = jwtService.extractOrgId(token);

            request.setAttribute("X-User-Id", userId);
            request.setAttribute("X-Role", role);
            request.setAttribute("X-Org-Id", orgId);
        }

        filterChain.doFilter(request, response);
    }
}
SecurityConfig
@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtFilter jwtFilter;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
            .csrf().disable()
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/auth/**").permitAll()
                .anyRequest().authenticated()
            )
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
            .build();
    }
}
📡 11. KAFKA CONFIG
Producer
@Configuration
public class KafkaProducerConfig {

    @Bean
    public ProducerFactory<String, Object> producerFactory() {
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        return new DefaultKafkaProducerFactory<>(config);
    }

    @Bean
    public KafkaTemplate<String, Object> kafkaTemplate() {
        return new KafkaTemplate<>(producerFactory());
    }
}
Consumer
@KafkaListener(topics = "subscription-events", groupId = "org-service")
public void consumeSubscriptionEvent(String event) {
    // handle event
}
🐇 12. RABBITMQ CONFIG
Config
@Configuration
public class RabbitConfig {

    @Bean
    public Queue queue() {
        return new Queue("email-queue");
    }

    @Bean
    public TopicExchange exchange() {
        return new TopicExchange("email-exchange");
    }

    @Bean
    public Binding binding(Queue queue, TopicExchange exchange) {
        return BindingBuilder.bind(queue)
                .to(exchange)
                .with("email.routing");
    }
}
Producer
@Service
@RequiredArgsConstructor
public class RabbitProducer {

    private final RabbitTemplate rabbitTemplate;

    public void send(Object message) {
        rabbitTemplate.convertAndSend("email-exchange", "email.routing", message);
    }
}
Consumer
@RabbitListener(queues = "email-queue")
public void receive(Object message) {
    // process email
}
🔐 13. AUTH SERVICE IMPLEMENTATION
Entity
@Entity
@Table(name = "users")
@Data
public class User {
    @Id
    private UUID id;

    private String email;
    private String passwordHash;
    private boolean isActive;

    private LocalDateTime createdAt;
}
Repository
public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByEmail(String email);
}
Service
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    public void register(RegisterRequest request) {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail(request.getEmail());
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));

        userRepository.save(user);

        kafkaTemplate.send("user-events", Map.of(
            "eventType", "USER_CREATED",
            "userId", user.getId(),
            "email", user.getEmail()
        ));
    }
}
Controller
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    public void register(@RequestBody RegisterRequest request) {
        authService.register(request);
    }
}
🏢 14. ORGANIZATION SERVICE (EVENT CONSUMER)
@Service
public class UserEventConsumer {

    @KafkaListener(topics = "user-events")
    public void handleUserCreated(Map<String, Object> event) {

        if ("USER_CREATED".equals(event.get("eventType"))) {
            // create default organization
        }
    }
}
💳 15. BILLING SERVICE (EVENT PRODUCER)
public void createSubscription(UUID orgId, UUID planId) {

    kafkaTemplate.send("subscription-events", Map.of(
        "eventType", "SUBSCRIPTION_CREATED",
        "organizationId", orgId,
        "planId", planId
    ));
}
📧 16. NOTIFICATION SERVICE (RABBITMQ CONSUMER)
@RabbitListener(queues = "email-queue")
public void sendEmail(Map<String, Object> event) {

    // save to email_logs
}
🧠 17. GLOBAL EXCEPTION HANDLER
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> handle(Exception ex) {
        return ResponseEntity.status(500).body(ex.getMessage());
    }
}
📊 18. LOGGING WITH CORRELATION ID
@Component
public class CorrelationFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
        throws ServletException, IOException {

        String correlationId = UUID.randomUUID().toString();
        MDC.put("correlationId", correlationId);

        filterChain.doFilter(request, response);

        MDC.clear();
    }
}
🧠 19. NEXT STEP (OPTIONAL BUT POWERFUL)

If you want to push this to elite-level realism, next additions:

API Gateway implementation (Spring Cloud Gateway)
Redis caching layer
Feature flags system
Angular folder structure + services + interceptors
Full JWT service implementation
Flyway migration scripts

19. API GATEWAY (Spring Cloud Gateway)
Goal

Central entry point:

JWT validation
Routing
Logging
Rate limiting
Dependencies
implementation 'org.springframework.cloud:spring-cloud-starter-gateway'
implementation 'org.springframework.boot:spring-boot-starter-security'
application.yml
server:
  port: 8080

spring:
  cloud:
    gateway:
      routes:
        - id: auth-service
          uri: http://localhost:8081
          predicates:
            - Path=/auth/**

        - id: organization-service
          uri: http://localhost:8082
          predicates:
            - Path=/organizations/**

        - id: billing-service
          uri: http://localhost:8083
          predicates:
            - Path=/billing/**

        - id: notification-service
          uri: http://localhost:8084
          predicates:
            - Path=/notifications/**
JWT Filter (Gateway Level)
@Component
public class GatewayJwtFilter implements GlobalFilter {

    private final JwtService jwtService;

    public GatewayJwtFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {

        String token = exchange.getRequest()
            .getHeaders()
            .getFirst(HttpHeaders.AUTHORIZATION);

        if (token != null && token.startsWith("Bearer ")) {

            token = token.substring(7);

            ServerHttpRequest mutated = exchange.getRequest().mutate()
                .header("X-User-Id", jwtService.extractUserId(token))
                .header("X-Org-Id", jwtService.extractOrgId(token))
                .header("X-Role", jwtService.extractRole(token))
                .build();

            return chain.filter(exchange.mutate().request(mutated).build());
        }

        return chain.filter(exchange);
    }
}
🔐 20. FULL JWT SERVICE (SHARED LIB)
@Service
public class JwtService {

    private final String SECRET = "secret-key";

    public String generateToken(String userId, String role, String orgId) {
        return Jwts.builder()
                .setSubject(userId)
                .claim("role", role)
                .claim("orgId", orgId)
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + 86400000))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes()))
                .compact();
    }

    public String extractUserId(String token) {
        return parse(token).getSubject();
    }

    public String extractRole(String token) {
        return parse(token).get("role", String.class);
    }

    public String extractOrgId(String token) {
        return parse(token).get("orgId", String.class);
    }

    private Claims parse(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(SECRET.getBytes())
                .build()
                .parseClaimsJws(token)
                .getBody();
    }
}
🧠 21. REDIS CACHING (5+ YEARS SIGNAL)
Use Cases
Cache subscriptions
Cache user roles
Reduce DB calls
Config
@Configuration
@EnableCaching
public class RedisConfig {

    @Bean
    public RedisConnectionFactory connectionFactory() {
        return new LettuceConnectionFactory();
    }
}
Usage
@Cacheable(value = "subscriptions", key = "#orgId")
public Subscription getSubscription(UUID orgId) {
    return repository.findByOrganizationId(orgId);
}
🚩 22. RATE LIMITING (BUCKET4J)
@Component
public class RateLimiterFilter extends OncePerRequestFilter {

    private final Bucket bucket = Bucket4j.builder()
        .addLimit(Bandwidth.simple(100, Duration.ofMinutes(1)))
        .build();

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
        throws ServletException, IOException {

        if (bucket.tryConsume(1)) {
            filterChain.doFilter(request, response);
        } else {
            response.setStatus(429);
        }
    }
}
🧪 23. FLYWAY MIGRATIONS
Example
-- V1__init.sql

CREATE TABLE users (
    id UUID PRIMARY KEY,
    email VARCHAR(255) NOT NULL
);
Config
spring:
  flyway:
    enabled: true
    locations: classpath:db/migration
🖥️ 24. ANGULAR FRONTEND STRUCTURE
/frontend
  /src/app
    /core
      auth.service.ts
      http.interceptor.ts
    /shared
      components/
    /features
      /auth
      /dashboard
      /organization
      /billing
Auth Service
@Injectable({ providedIn: 'root' })
export class AuthService {

  private api = 'http://localhost:8080/auth';

  login(data: any) {
    return this.http.post(`${this.api}/login`, data);
  }
}
HTTP Interceptor
@Injectable()
export class AuthInterceptor implements HttpInterceptor {

  intercept(req: HttpRequest<any>, next: HttpHandler) {

    const token = localStorage.getItem('token');

    if (token) {
      req = req.clone({
        setHeaders: {
          Authorization: `Bearer ${token}`
        }
      });
    }

    return next.handle(req);
  }
}
Route Guard
@Injectable({ providedIn: 'root' })
export class AuthGuard implements CanActivate {

  canActivate(): boolean {
    return !!localStorage.getItem('token');
  }
}
📊 25. FEATURE MODULES REQUIREMENTS
Auth Module
login/register
store JWT
refresh token handling
Dashboard Module
organization switcher
role display
Organization Module
invite user
list members
change roles
Billing Module
show subscription
upgrade/downgrade plan
list invoices
🔁 26. REFRESH TOKEN FLOW
Access token expires
Frontend calls /auth/refresh
Backend validates refresh token
Returns new access token
🧠 27. FEATURE FLAGS SYSTEM
@Entity
public class FeatureFlag {
    @Id
    private UUID id;
    private String name;
    private boolean enabled;
}

Usage:

if (featureFlagService.isEnabled("NEW_DASHBOARD")) {
    // logic
}
📦 28. FINAL POLISH (IMPORTANT FOR RECRUITERS)

Must include:

Swagger UI → /swagger-ui.html
Docker Compose up → everything runs
Seed script (default user + org)
Logs readable (JSON format)
Clear README
🚀 FINAL NOTE FOR COPILOT

Implement:

Clean architecture per service
DTO separation (no entity exposure)
Event-driven flows (Kafka + RabbitMQ)
Multi-tenancy enforced everywhere
Security centralized at gateway
Angular consumes ONLY gateway API