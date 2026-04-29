Project: Multi-Tenant SaaS Subscription Platform (Local-First)

A production-style SaaS platform simulating real-world enterprise architecture, focused on multi-tenancy, role-based access, billing simulation, and internal tooling.


ADD GRAPQL API

📌 1. Architecture Overview
Backend (Microservices)

Split into services:

API Gateway
Auth Service
User/Organization Service
Billing Service (local simulation)
Notification Service (local email simulation)

Communication:

REST (initially)
Optional: internal messaging (event-driven with Kafka/RabbitMQ simulation)

Each service:

Own database schema (PostgreSQL, same instance)
Dockerized
Independent configuration
🔐 2. Auth Service
Features
User registration & login
JWT-based authentication
Refresh token rotation
Password hashing (BCrypt)
Password reset flow
Endpoints
POST /auth/register
POST /auth/login
POST /auth/refresh
POST /auth/forgot-password
POST /auth/reset-password
Requirements
Store users in PostgreSQL
Return JWT + refresh token
Token expiration handling
Role support (ADMIN, MANAGER, MEMBER)
🏢 3. Organization Service (Multi-Tenancy Core)
Features
Create organization
Invite users
Assign roles
Switch active organization
Data Model
organizations
users
memberships (user_id, org_id, role)
Endpoints
POST /organizations
GET /organizations
POST /organizations/{id}/invite
POST /organizations/{id}/members
PATCH /organizations/{id}/members/{userId}/role
Requirements
Every entity linked to organization_id
Enforce tenant isolation
Role-based access control
💳 4. Billing Service (Local Simulation)
Goal

Simulate Stripe-like behavior locally.

Features
Create subscription plans
Assign subscription to organization
Handle subscription lifecycle:
trialing
active
past_due
canceled
Data Model
plans
subscriptions
invoices
payments
Endpoints
POST /billing/plans
POST /billing/subscribe
GET /billing/subscription
POST /billing/cancel
GET /billing/invoices
Requirements
Simulate webhook events internally:
subscription_created
payment_success
payment_failed
Feature gating:
limit users per plan
restrict features
📧 5. Notification Service (Local Email Simulation)
Goal

Replace external email provider with local system.

Features
Email template system
Event-based email triggering
Email Types
Invite user
Password reset
Subscription confirmation
Invoice generated
Implementation
Store emails in DB (email_logs table)
Optionally expose:
GET /emails (to inspect sent emails)
Architecture
Service receives events:
USER_INVITED
PASSWORD_RESET
SUBSCRIPTION_CREATED
🌐 6. API Gateway
Responsibilities
Route requests to services
Central authentication validation
Logging
Rate limiting
Requirements
Validate JWT centrally
Forward user context to services
Handle CORS
🗄️ 7. Database (PostgreSQL - Local)
Requirements
Single PostgreSQL instance
Separate schemas per service OR clear table prefixes
Use Flyway or Liquibase migrations
Core Concepts
Multi-tenancy via organization_id
Audit fields:
created_at
updated_at
Soft deletes
🧪 8. Testing
Requirements
Unit tests (services)
Integration tests (SpringBootTest)
Testcontainers for PostgreSQL
🖥️ 9. Frontend (No Deployment Required)
Stack
Angular (TypeScript)
RxJS
Angular Services for API calls
Features
Auth
Login / Register
Token storage
Auto refresh token
Dashboard
Organization switcher
User role display
Organization Management
Invite members
Change roles
Billing UI
View subscription
Change plan
View invoices
General UI
Protected routes
Role-based rendering
Global error handling
Loading indicators
🧠 10. Advanced (5+ Years Level Features)
Backend
Redis caching layer
Rate limiting (Bucket4j or similar)
Distributed logging (correlation IDs)
Centralized exception handling
OpenAPI (Swagger docs)
Event-driven communication (Kafka/RabbitMQ simulation)
Feature flags system
Audit logging (who did what)
Security
Refresh token rotation
Role + permission-based authorization
Input validation everywhere
DevOps
Docker Compose for all services
Environment-based configs
CI pipeline (GitHub Actions)
📊 11. Observability
Requirements
Structured logging (JSON logs)
Request tracing (traceId per request)
Metrics endpoint (Spring Actuator)
📁 12. Project Structure
Backend (per service)
controller/
service/
repository/
dto/
mapper/
config/
security/
exception/
Frontend
core/ (auth, interceptors)
shared/ (components)
features/
auth/
dashboard/
organization/
billing/
📌 13. Documentation
README must include:
Architecture diagram
Service responsibilities
Database schema (ERD)
Authentication flow
Subscription flow (simulated)
How to run locally (Docker)
🚀 14. Optional Enhancements
File upload (local storage)
Export invoices as PDF
Admin panel for system-wide monitoring
Internal notification system (in-app)