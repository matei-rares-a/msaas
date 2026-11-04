# Platforma SaaS multi-tenant

Aplicatie multi-tenant: fiecare organizatie (tenant) foloseste aceeasi instanta, dar vede doar datele si functiile ei. Backend in Spring Boot 3.2 / Java 21 (microservicii), frontend in Angular 19.

Sursele de adevar:
- [REQUIREMENTS.md](REQUIREMENTS.md) - ce se construieste;
- [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md) - cum si in ce ordine (catalogul REQ, pasii, jurnalul de progres);
- [AGENT_CONTEXT.md](AGENT_CONTEXT.md) - rezumat pentru preluarea lucrului;
- [prerequisites.md](prerequisites.md) - pregatirea mediului local si comenzile de build/test;
- [TLDR.md](TLDR.md) - de la repo clonat pe o masina goala la aplicatia pornita, cu conturi demo;
- [PROMPT_SETUP_TLDR.md](PROMPT_SETUP_TLDR.md) - promptul reutilizabil prin care un agent face un TLDR si un script de setup pentru orice repo.

Acest fisier e doar o privire de ansamblu; la orice diferenta, cerintele si planul au prioritate.

## Servicii

| Serviciu | Port | Rol | Date |
|---|---|---|---|
| `api-gateway` | 8080 | valideaza JWT-ul per-tenant, seteaza `X-User-Id`, `X-Tenant-Id`, `X-Role`, limiteaza cererile (Redis) | - |
| `auth-service` | 8081 | inregistrare, login, refresh, resetare parola, stergerea contului | baza globala `saas_global` (identitati, tokenuri, planuri, palete) |
| `organization-service` | 8082 | organizatii, membri si roluri, invitatii, tema/logo, module, concedii, lunch roulette | shard `saas_shard_1`, schema `org` |
| `billing-service` | 8083 | abonamente, facturi, plati simulate, perioada de gratie | shard `saas_shard_1`, schema `billing` |
| `notification-service` | 8084 | notificari in aplicatie, jurnalul de activitate (GraphQL + export), email-uri | shard `saas_shard_1`, schema `notif` |
| `angular_base` | 4200 (dev) | interfata web | - |

Infrastructura: PostgreSQL (prin PgBouncer, rol DB dedicat per serviciu), Kafka (evenimente de domeniu, doar identificatori), RabbitMQ (comenzi, ex. email-uri, cu date personale), Redis (cache si limitare de cereri).
Module Maven: `shared-lib` (cod comun), `test-support` (containere si utilitare de test), `e2e-tests` (doar cu profilul `e2e`).

## Securitate si multi-tenancy

- **Login**: un tenant -> tokenul per-tenant direct; mai multe -> lista + `loginSessionId` opac, de unica folosinta, iar al doilea apel emite tokenul; niciunul -> lista goala + token de identitate, valabil doar pe `POST /organizations` si `POST /invitations/accept`.
- **Tokenuri**: acces 15 min, refresh 7 zile, unul per tenant (un token pentru tenantul A e respins pe resursele tenantului B); clientul tine tokenul tenantului activ.
- **Roluri**: `ADMIN` (organizatie, billing, membri, invita cu orice rol), `MANAGER` (invita doar `USER`, vede jurnalul, aproba concedii), `USER`. Accesul de scriere/doar-citire e un flag separat pe membership, nu un rol. Intr-o organizatie ramane mereu cel putin un `ADMIN`.
- **Identitatea** vine mereu din token (`X-User-Id`, `X-Tenant-Id`), niciodata din corpul cererii.
- **Izolare**: filtrare pe `tenant_id` in aplicatie + RLS in baza de date; `tenant_id -> shard` permite repartizarea ulterioara pe mai multe servere.
- **Billing**: `TRIALING` -> `ACTIVE`; plata esuata -> `PAST_DUE` (7 zile de gratie, tenantul e doar-citire) -> `SUSPENDED`.

## Evenimente

Fiecare serviciu scrie evenimentele intr-un outbox in aceeasi tranzactie cu datele; un relay le publica la-cel-putin-o-data (Kafka pentru fapte, RabbitMQ pentru comenzi). Consumatorii sunt idempotenti; dupa 5 reincercari mesajul ajunge in dead-letter (`<topic>.DLT` la Kafka). Jurnalul de activitate al tenantului se alimenteaza din aceste evenimente.

## Endpoint-uri (prin gateway, `http://localhost:8080`)

Lista completa si la zi sta in snapshot-urile OpenAPI, `*/src/test/resources/api-snapshots/openapi.json` (verificate de testele de compatibilitate), si in `/swagger-ui.html` al fiecarui serviciu.

| Zona | Exemple |
|---|---|
| Autentificare | `POST /auth/register`, `/auth/login`, `/auth/login/tenant`, `/auth/refresh`, `/auth/forgot-password`, `/auth/reset-password`, `DELETE /auth/account` |
| Organizatie | `POST /organizations`, `PATCH /organizations/{tenantId}`, `.../members/{userId}/role`, `.../invitations`, `POST /invitations/accept`, `.../theme`, `.../modules/{module}` |
| Concedii | `.../leave/requests` (creare, aprobare, respingere, anulare), `.../leave/balance`, `.../leave/calendar`, `.../leave/policy` |
| Lunch roulette | `.../lunch/config`, `.../lunch/rounds`, `.../lunch/rounds/current/enrollment` |
| Billing | `GET /billing/subscription`, `POST /billing/subscriptions`, `.../cancel`, `.../change-plan`, `GET /billing/invoices` |
| Notificari | `GET /notifications/in-app`, `PATCH /notifications/in-app/read`, `POST /notifications/activity-log/exports`, GraphQL pe `/notifications/graphql` |

Cererile de pe rutele `/internal/**` nu ajung prin gateway.

## Rulare

Totul se pregateste cu scripturi (vezi [prerequisites.md](prerequisites.md)); JDK 21 e obligatoriu.

```bash
node scripts/env/setup.mjs                 # de la clona la platforma pornita cu date demo (masina cu uneltele instalate; vezi TLDR.md)
node scripts/env/ensure-dev-env.mjs        # doar mediul local (Docker, JDK 21, npm ci)
./mvnw -B -ntp clean verify                # build + toate testele (cere Docker)
cd angular_base && npm run build && npm run test:ci && npm run e2e
node scripts/verify/check-traceability.mjs # fiecare cerinta datorata are test
node scripts/infra/e2e-stack.mjs --all     # suita E2E pe Docker Compose
```

Pentru a rula platforma local: `docker compose up -d --build` dupa ce jar-urile sunt construite (`./mvnw -Dmaven.test.skip=true clean package`); secretele de dezvoltare sunt in `.env` (publice intentionat, de inlocuit inainte de orice deploy). Daca pe masina ruleaza deja un Postgres pe 5432, foloseste `node scripts/infra/local-stack.mjs --up`, care nu publica porturi.

## Observabilitate

- `X-Correlation-Id` pus de gateway si prezent in toate logurile (JSON, pe stdout).
- `/actuator/health` pe fiecare serviciu.