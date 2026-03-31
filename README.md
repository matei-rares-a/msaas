docker-compose up -d
# Multi-Tenant SaaS Subscription Platform

A production-style SaaS platform demonstrating real-world enterprise architecture patterns with multi-tenancy, role-based access control, event-driven communication, and comprehensive observability.

## 🏗️ Architecture Overview

```
┌─────────────────────────────────────────────────────────────┐
│                   API Gateway (8080)                         │
│                  JWT Validation, CORS                        │
└────────────────────────┬────────────────────────────────────┘
                         │
      ┌──────────────────┼──────────────────┐
      │                  │                  │
   ┌──▼──┐         ┌─────▼────┐       ┌────▼────┐
   │Auth │         │   Org    │       │ Billing │
   │8081 │         │  Service │       │ Service │
   │     │         │   8082   │       │  8083   │
   └─────┘         └──────────┘       └─────────┘
      │                  │                  │
      └──────────────────┼──────────────────┘
                         │
            ┌────────────┴─────────────┐
            │                          │
        PostgreSQL                  Redis
        (4 schemas)                 Cache
            │
      ┌─────┴───────┐
      │             │
   Kafka         RabbitMQ
  (async)       (immediate)
      │             │
   Subscriptions  Emails
   Analytics      Notifications
```

## 📦 Services

| Service | Port | Responsibility | Database |
|---------|------|-----------------|----------|
| **API Gateway** | 8080 | Route, validate JWT, forward context | - |
| **Auth Service** | 8081 | Register, login, password reset, JWT | `saas_auth` |
| **Organization Service** | 8082 | Multi-tenancy, memberships, invitations | `saas_org` |
| **Billing Service** | 8083 | Plans, subscriptions, invoices, payments | `saas_billing` |
| **Notification Service** | 8084 | Email delivery & persistence | `saas_notif` |

## 🔐 Security & Multi-Tenancy

### Authentication Flow
1. **Register**: Email + password → stored with bcrypt hash
2. **Login**: Returns `accessToken` + `refreshToken`
3. **Gateway**: Validates JWT, extracts `userId`, `orgId`, `role`
4. **Services**: Receive headers: `X-User-Id`, `X-Org-Id`, `X-Role`
5. **Isolation**: All queries filtered by `organization_id`

### Token Management
- **Access Token**: 24h expiry, short-lived
- **Refresh Token**: 7d expiry, rotated on each use
- **Security**: JWT signed with HS256, BASE64 encoded

### Role-Based Access Control
```
ADMIN   - Full org control, invite users, change roles
MANAGER - Invite users, view members
MEMBER  - View dashboard, basic features
```

## 📡 Event-Driven Architecture

### Kafka (Async, Analytics-focused)
- **Topics**: `user-events`, `subscription-events`, `notification-events`
- **Use**: Long-term persistence, analytics, audit logs
- **Consumers**:
  - `USER_CREATED` → Org Service creates default org
  - `SUBSCRIPTION_CREATED` → Org Service updates user limits
  - `PASSWORD_RESET` → Notification Service logs

### RabbitMQ (Immediate, Command-focused)
- **Queues**: `email-queue` (fanout: `email-exchange`)
- **Use**: Real-time notifications, immediate actions
- **Messages**:
  - `USER_INVITED` → Notification Service sends invitation email
  - `PASSWORD_RESET` → Stored in email-logs for audit

## 💬 API Endpoints

### Auth Service (All public; gateway allows)
```
POST   /auth/register          - Register new user
POST   /auth/login             - Login → tokens
POST   /auth/refresh           - Refresh access token
POST   /auth/forgot-password   - Request password reset
POST   /auth/reset-password    - Reset with token
```

### Organization Service (All protected)
```
POST   /organizations                   - Create org
GET    /organizations                   - List my orgs
POST   /organizations/{id}/invite       - Invite user (ADMIN/MANAGER)
GET    /organizations/{id}/members      - List members
PATCH  /organizations/{id}/members/{uid}/role - Update role (ADMIN)
```

### Billing Service (All protected)
```
POST   /billing/subscribe       - Subscribe to plan
GET    /billing/subscription    - Get current subscription
POST   /billing/cancel          - Cancel subscription
GET    /billing/invoices        - List invoices
```

### Notification Service (Admin only)
```
GET    /notifications/emails-by/{email} - Inspect email logs
GET    /notifications/health            - Health check
```

## 🗄️ Database Schema

### Auth Service (`saas_auth`)
```sql
users
├─ id (UUID)
├─ email (VARCHAR unique)
├─ password_hash (TEXT)
├─ is_active (BOOLEAN)
└─ created_at, updated_at

refresh_tokens
├─ id (UUID)
├─ user_id → users
├─ token (TEXT)
└─ expires_at

password_reset_tokens
├─ id (UUID)
├─ user_id → users
├─ token (VARCHAR)
├─ expires_at
└─ used (BOOLEAN)
```

### Organization Service (`saas_org`)
```sql
organizations
├─ id (UUID)
├─ name (VARCHAR)
├─ owner_id (UUID)
└─ created_at, updated_at

memberships (Unique: user_id + org_id)
├─ id (UUID)
├─ user_id (UUID) ← multi-tenant key
├─ organization_id → organizations
├─ role (ENUM: ADMIN|MANAGER|MEMBER)
└─ created_at

invitations
├─ id (UUID)
├─ email (VARCHAR)
├─ organization_id → organizations
├─ role (ENUM)
├─ token (VARCHAR)
├─ expires_at
└─ accepted (BOOLEAN)
```

### Billing Service (`saas_billing`)
```sql
plans
├─ id (UUID)
├─ name (VARCHAR)
├─ price (DECIMAL)
├─ max_users (INT)
├─ features (JSONB)
└─ created_at

subscriptions
├─ id (UUID)
├─ organization_id (UUID) ← multi-tenant key
├─ plan_id → plans
├─ status (ENUM: TRIALING|ACTIVE|PAST_DUE|CANCELED)
├─ start_date, end_date
└─ created_at, updated_at

invoices
├─ id (UUID)
├─ organization_id (UUID) ← multi-tenant key
├─ subscription_id → subscriptions
├─ amount (DECIMAL)
├─ status (ENUM: PAID|UNPAID)
└─ issued_at, paid_at

payments
├─ id (UUID)
├─ invoice_id → invoices
├─ status (ENUM: SUCCESS|FAILED)
└─ processed_at
```

### Notification Service (`saas_notif`)
```sql
email_logs
├─ id (UUID)
├─ to_email (VARCHAR)
├─ subject (VARCHAR)
├─ body (TEXT)
├─ status (VARCHAR)
├─ event_type (VARCHAR)
└─ created_at
```

## 🚀 Quick Start

### Prerequisites
- Docker & Docker Compose
- Maven 3.8+
- Java 21

### Build & Run

```bash
# 1. Build all services
cd shared-lib && mvn clean install && cd ..
cd api-gateway && mvn clean package -DskipTests && cd ..
cd auth-service && mvn clean package -DskipTests && cd ..
cd organization-service && mvn clean package -DskipTests && cd ..
cd billing-service && mvn clean package -DskipTests && cd ..
cd notification-service && mvn clean package -DskipTests && cd ..

# 2. Start infrastructure
docker-compose up -d

# 3. Wait for services to boot (~30s)
docker-compose logs -f api-gateway

# 4. Test login
curl -X POST http://localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"test@example.com","password":"password123"}'
```

### Service URLs
- **API Gateway**: http://localhost:8080
- **Auth Service**: http://localhost:8081/swagger-ui.html
- **Org Service**: http://localhost:8082/swagger-ui.html
- **Billing Service**: http://localhost:8083/swagger-ui.html
- **Notification Service**: http://localhost:8084/swagger-ui.html
- **RabbitMQ Management**: http://localhost:15672 (guest/guest)

## 🧪 Example Flow

### 1. Register User
```bash
curl -X POST http://localhost:8080/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "email": "alice@example.com",
    "password": "SecurePass123"
  }'
# Returns 201
```

### 2. Login
```bash
curl -X POST http://localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "email": "alice@example.com",
    "password": "SecurePass123"
  }'
# Response:
# {
#   "accessToken": "eyJhbGc...",
#   "refreshToken": "a1b2c3...",
#   "tokenType": "Bearer",
#   "expiresIn": 86400
# }
```

### 3. Create Organization
```bash
curl -X POST http://localhost:8080/organizations \
  -H "Authorization: Bearer {accessToken}" \
  -H "Content-Type: application/json" \
  -d '{"name": "Acme Corp"}'
# Returns: org_id (UUID)
```

### 4. Invite User
```bash
curl -X POST http://localhost:8080/organizations/{org_id}/invite \
  -H "Authorization: Bearer {accessToken}" \
  -H "Content-Type: application/json" \
  -d '{
    "email": "bob@example.com",
    "role": "MANAGER"
  }'
# Triggers RabbitMQ → Notification Service
# Email log persisted in saas_notif.email_logs
```

### 5. Subscribe to Plan
```bash
curl -X POST http://localhost:8080/billing/subscribe \
  -H "Authorization: Bearer {accessToken}" \
  -H "Content-Type: application/json" \
  -d '{"planId": "{plan_uuid}"}'
# Returns: Subscription + Invoice
```

## 🔍 Observability

### Correlation IDs
- Gateway injects `X-Correlation-Id` header
- All services log with correlation ID in MDC
- Tracing across all services for single request

### Logging
- JSON structured logs (Spring Boot default)
- Log levels: DEBUG, INFO, WARN, ERROR
- Services log to stdout (docker-compose captures)

### Metrics
- Spring Boot Actuator enabled on all services
- `/actuator/health` → liveness + readiness
- `/actuator/metrics` → application metrics

### Databases
- View logs: `docker-compose exec postgres psql -U admin -d saas_auth`
- Schemas automatically created by Flyway migrations

## 🏆 Key Features Implemented

✅ **Multi-Tenancy**  
- Organization isolation via `organization_id`
- Enforced at database (unique constraints) + service layer (access control)

✅ **Event-Driven**  
- Kafka for async, eventual consistency (USER_CREATED, SUBSCRIPTION_CREATED)
- RabbitMQ for immediate notifications (USER_INVITED, PASSWORD_RESET)

✅ **Security**  
- BCrypt password hashing
- JWT with 24h access + 7d refresh (rotated)
- Gateway-level validation + per-service extraction

✅ **High-Availability** (Pattern)  
- Stateless services (no in-process caches)
- Redis for distributed caching (billing subscriptions)
- Database schemas versioned with Flyway

✅ **Observability**  
- Correlation ID tracing
- Structured JSON logging
- Spring Actuator metrics

✅ **Developer Experience**  
- Swagger UI on each service
- Docker Compose for one-command start
- Clear README & API documentation

---

**Built with ❤️ as a reference architecture for enterprise SaaS platforms.**
