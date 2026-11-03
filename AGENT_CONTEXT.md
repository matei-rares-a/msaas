# Context pentru agenti (handoff)

Document de predare pentru orice agent care preia acest workspace. Nu inlocuieste sursele de adevar
(`REQUIREMENTS.md`, `IMPLEMENTATION_PLAN.md`, `.github/copilot-instructions.md`) - le rezuma si adauga
contextul practic (decizii, conventii de cod, capcane) acumulat in sesiunile anterioare.

## 1. Surse de adevar si ordinea lor

1. `.github/copilot-instructions.md` - regulile de proces, obligatorii la fiecare prompt (rezumate mai jos).
2. `REQUIREMENTS.md` - CE se construieste (comportament, reguli de business).
3. `IMPLEMENTATION_PLAN.md` - CUM si IN CE ORDINE: sectiunea 0 (reguli), sectiunea 1 (catalog REQ -> pas),
   sectiunea 4 (parametri configurabili, NU cerinte), sectiunea 5 (jurnal de progres, un rand per pas, cu
   detalii tehnice suficiente cat sa reconstruiesti decizia fara sa recitesti codul).

## 2. Fluxul de lucru obligatoriu (din copilot-instructions.md)

La fiecare prompt:
1. Citeste planul, gaseste primul pas nebifat (`[ ]`) in ordinea fazelor - ala e pasul curent.
2. Clasifica cererea: tine de pasul curent -> lucreaza; tine de alt pas/e deviere -> spune explicit si
   cere confirmare; e schimbare de cerinte -> urmeaza procedura de schimbare (clarifica -> REQUIREMENTS.md
   -> verifica contradictii -> catalogul -> pasii afectati -> sectiunea 4 -> jurnal -> commit cu sufixul
   `(cerinte)`); e doar o intrebare -> raspunde fara sa modifici fisiere.
3. Inainte de orice test de integrare: `node scripts/env/ensure-dev-env.mjs --check` (sau fara `--check`
   ca sa porneasca Docker automat daca nu ruleaza).
4. Un pas e "terminat" doar daca: cod complet pentru toate REQ-urile pasului; fiecare REQ are minim un
   test `@Tag("REQ-...")`; `mvnw verify` trece pe modulele atinse (fara teste sarite/`@Disabled`);
   `node scripts/verify/check-traceability.mjs` trece; verificarea fata de plan e completa (vezi mai jos);
   pasul e bifat `[x]`, are rand nou in jurnal, commit local `P<faza>.<pas>: <rezumat>`.
5. Raporteaza mereu la final: `Pn.m: descriere OK / teste OK / fara extra OK` (sau ce lipseste) +
   REQ-urile verificate si nepotrivirile fata de REQUIREMENTS.md (sau "nicio nepotrivire").
6. Fara push, fara stergeri ireversibile, fara modificari in afara pasului curent fara confirmare
   explicita a utilizatorului.
7. Comenzile manuale repetate (sau care au avut nevoie de reincercare/corectura) devin scripturi in
   `scripts/` (`env/`, `infra/`, `verify/`), idempotente, Node `.mjs`, cu `--check` unde are sens.

## 3. Arhitectura (pe scurt)

- **Module Maven** (root `msaas-parent`, Boot 3.2.5, Java 21): `test-support`, `shared-lib`, `api-gateway`
  (reactiv, Spring Cloud Gateway), `auth-service`, `organization-service`, `billing-service`,
  `notification-service`. Build: `.\mvnw.cmd -B -ntp verify` din radacina (sau `-pl <modul> -am` pentru un
  singur modul + dependintele lui).
- **Baze de date**: PgBouncer (transaction pooling) in fata Postgres. `saas_global` (schema `auth` -
  identitati/refresh-reset tokens; schema `global` - catalog planuri `global.plans`, maparea
  `tenant_id -> shard_id`, viitor catalog palete P8.1). `saas_shard_1` (schema per serviciu: `org`,
  `billing`, `notif`). Un rol DB LOGIN per serviciu (`auth_svc`, `org_svc`, `billing_svc`, `notif_svc`),
  plus roluri BYPASSRLS punctuale pentru citiri cross-tenant (`outbox_relay`, `org_auth_reader`,
  `billing_job_reader`). Fiecare tabel tenant-scoped are `tenant_id`, RLS fortat, policy pe
  `app.tenant_id`, unique `(tenant_id, id)`.
- **Tenancy**: `shared-lib` - `TenantContext` (ThreadLocal), `TenantScopedEntity` (`@TenantId` Hibernate),
  `TenantAwareJpaTransactionManager` (`SET LOCAL app.tenant_id` la inceputul tranzactiei; necesita
  `TenantContext` deja setat). De aceea serviciile cu scriere (OrganizationService, SubscriptionService,
  PaymentService) folosesc **`TransactionTemplate` programatic, NU `@Transactional`**: proxy-ul AOP ar
  porni tranzactia inainte ca metoda sa apuce sa cheme `TenantContext.callWith(tenantId, ...)`.
- **JWT (P5.3)**: o singura implementare in `shared-lib` (`com.saas.shared.security.JwtService` +
  `AccessTokenClaims` + `JwtAutoConfiguration`, bean creat doar daca `jwt.secret` e configurat). Claims:
  `sub`=userId, `tenantId`, `role`, `readOnly`, `tenantName`, `displayName`, `iat`/`exp`. Acces 15 min.
  Token de identitate pentru useri fara tenant: vezi sectiunea 5.
  Gateway-ul valideaza tokenul si transmite context prin headere: `X-User-Id`, `X-Tenant-Id`, `X-Role`.
  `TenantRequestGuard.requireMatch(pathTenantId, headerTenantId)` (shared-lib) respinge cu 403 cand un
  token al tenantului A ar atinge resursele tenantului B, chiar daca userul e membru si acolo.
- **Evenimente**: outbox transactional (`shared-lib`): `OutboxWriter.writeEvent(envelope, destination,
  channel)` scrie in tranzactia apelantului; `OutboxRelay` (rol BYPASSRLS, publica separat). Kafka =
  **doar identificatori**, fara PII (REQ-EVT-02). RabbitMQ = comenzi care pot purta PII (ex. email pentru
  retrimitere) - vezi `SendPasswordResetEmailCommand`/`SendPasswordChangedConfirmationCommand` in
  auth-service. `EventEnvelope.forTenant(...)`/`forIdentity(...)`; evenimentele de identitate (fara
  tenant) trebuie sa implementeze `IdentityEventPayload` (subjectUserId pentru routing key).
- **Cross-service, sincron**: cand un serviciu are nevoie de o informatie live din alt serviciu (nu merita
  o proiectie locala), se foloseste un `RestClient` simplu, cu base-url din proprietate +
  variabila de mediu (`*_SERVICE_BASE_URL`), vezi `OrgServiceClient` (auth -> org, P5.2) si
  `TenantAccessStatusClient` (org -> billing, P6.4, **fail-open** la "ACTIVE" daca serviciul tinta nu
  raspunde, ca o cadere sa nu blocheze restul platformei).
- **Citiri cross-DB fara serviciu HTTP**: cand un serviciu trebuie sa citeasca o tabela a altui serviciu
  fara sa treaca prin HTTP (ex. catalogul global de planuri din billing-service), se construieste un
  `DataSource` simplu, **ca field, NU ca bean Spring** (vezi `PlanCatalogReader`, `MembershipLookupService`)
  - un al doilea bean `DataSource` neconditionat strica auto-configurarea Flyway/JPA (eroare criptica
    "dataSource ... is required").
- **Cititori cross-tenant (BYPASSRLS)**: pentru job-uri/finder-e care trebuie sa vada randuri din toti
  tenantii (ex. `PendingAdminDemotionFinder`, `ExpiredTrialFinder`), se foloseste un rol DB dedicat,
  BYPASSRLS, SELECT-only pe EXACT tabelele necesare (niciodata acces larg). Adaugarea unui rol nou atinge:
  `db/init/01-topology.sh` (rol + grant CONNECT) + migrarea proprie a serviciului (grant pe tabela, pazit
  cu `IF EXISTS (SELECT 1 FROM pg_roles ...)` pentru containerele de test fara topologia completa) +
  `.env` + `docker-compose.yml` (env pe serviciul postgres, pe PgBouncer `DATABASE_URLS`, pe serviciul
  care-l foloseste) + `scripts/env/ensure-dev-env.mjs` (`requiredEnvKeys`) +
  `test-support` (`DatabaseTopologyIT`, `SchemaConventionsIT` - adauga noua parola in containerele lor).

## 4. Conventii de cod stabilite

- Servicii cu scriere tenant-scoped: constructor cu `PlatformTransactionManager` -> `new
  TransactionTemplate(...)` in camp; orice metoda publica = `TenantContext.callWith(tenantId, () ->
  transactionTemplate.execute(status -> {...}))`.
- `Clock` injectat peste tot (niciodata `Instant.now()`/`LocalDateTime.now()` direct in cod nou) - fiecare
  serviciu are un `XyzClockConfig` cu `@Bean @ConditionalOnMissingBean(Clock.class)` ca fallback
  `Clock.systemUTC()`; testele suprascriu cu `MutableClock` + `@TestConfiguration` **`@Primary`**
  obligatoriu (altfel Spring poate rezolva non-determinist beanul gresit cand ambele exista).
- Job-uri programate (scheduler): clasa simpla (finder/job) fara adnotari, instantiata explicit intr-un
  `@Configuration` prin `@Bean`; un wrapper static nested class cu `@Scheduled(cron = "${prop:default}")`
  care cheama job-ul (vezi `AdminDemotionEscalationConfig`, `BillingSchedulingConfig`). Job-ul e apelabil
  direct din teste (fara sa astepti cron-ul).
- Migrari Flyway: fiecare modul incepe cu `V1__baseline...sql`; migrarile NU muta date (doar structura);
  verificat automat de `SchemaConventionsIT`.
- Entitati globale (fara tenant) se marcheaza `@GlobalEntity` (shared-lib), nu extind
  `TenantScopedEntity` - verificat de `TenancyArchRules`/`TenancyArchTest` in fiecare serviciu.
- Exception handling per serviciu: `GlobalExceptionHandler` (`@RestControllerAdvice`) cu acelasi tipar in
  toate serviciile: `SecurityException`->403, `IllegalStateException`->409,
  `IllegalArgumentException`/`MethodArgumentNotValidException`->400, `Exception`->500.
- Teste de integrare (`*IT`, Failsafe): doua tipare de container Postgres:
  1. **Container generic** (`implements PostgresTestContainer` din test-support, `@ServiceConnection`) -
     cand nu conteaza rolul DB exact folosit (ex. `PaymentServiceIT`, `ActivityLogPartitionMaintenanceIT`).
  2. **Container dedicat cu topologia reala** (ruleaza `db/init/01-topology.sh` + migrarile reale, cu
     `System.setProperty(...)` intr-un initializator static eager, NU `@DynamicPropertySource` - s-a
     dovedit nesigur in acest proiect) - cand testul are nevoie de un rol DB specific (`org_svc`,
     `billing_job_reader` etc.) sau de comportament doar vizibil cu topologia completa. Vezi
     `OrganizationLifecycleIT`, `SubscriptionLifecycleIT`, `TenantAccessGuardIT`.
  - Cand doua clase de test din acelasi modul folosesc containerul dedicat, fiecare are nevoie de propriul
    `test.marker=<NumeClasa>` in `@SpringBootTest(properties=...)` ca Spring sa nu refoloseasca gresit
    contextul intre ele.
  - Pentru servicii care trebuie sa simuleze un alt serviciu HTTP in acelasi test (ex. auth -> org, org ->
    billing), se foloseste un `com.sun.net.httpserver.HttpServer` minimal pornit static, cu un camp
    `static volatile String xyzResponse` mutabil intre apeluri (vezi `LoginFlowIT`, `TenantAccessGuardIT`).
  - `MutableClock` si `SimulatedPaymentGateway.FailureMode` sunt beanuri singleton **per clasa de test**
    (contextul Spring e cache-uit): `clock.advance(...)` facut intr-un test ramane si in urmatoarele teste
    din aceeasi clasa. Nu scrie asertiuni pe numere agregate ("converted == 1") peste o clasa cu clock
    comun - verifica doar starea entitatii proprii testului. Reseteaza starea comuna mutabila
    (`FailureMode`) intr-un `@BeforeEach`.
  - Citiri directe din DB in teste (verificare stare, nu prin service): conexiune directa
    `DriverManager.getConnection(url, "admin", "admin")` (bypasseaza RLS), NU JdbcTemplate-ul aplicatiei
    (legat la alt rol/alta baza) si NU `set_config` pe un pool cu >1 conexiuni (sesiune-level, nu se leaga
    garantat de aceeasi conexiune fizica decat cu `SingleConnectionDataSource` sau
    `hikari.maximum-pool-size=1`).

## 5. Status la data acestui fisier (2026-11-03)

Fazele 0-10, **P11.1 (Login)** si **P11.2 (Tema si logo)** sunt facute si commise (`P7.1` a fost implementat de alt agent, Kiro, si corectat in
`P7.1 (deviere)`). Pasul curent (primul nebifat din plan) este **P11.3 Notificari** (`angular_base`). Citeste pasul si cerintele lui in plan inainte de a incepe. Frontend: `npm run build` + `npm run test:ci` + `npm run e2e` (Playwright, gateway simulat); pe aceasta masina `CHROME_BIN` vine din `node scripts/env/ensure-dev-env.mjs --check` (Edge) si se seteaza in sesiune inainte de `test:ci`/`e2e`. Backend-ul nu se mai atinge in Faza 11, deci nu e nevoie de `mvnw`.

Ce exista deja si e relevant mai departe:
- **Tema (P11.2)**: `ThemeService` aplica paleta tenantului activ ca variabile CSS (doar `#RRGGBB`) si tine logo-ul ca URL `blob:` adus cu tokenul, doar de la `/organizations/{tenant}/theme/logo`; `AppComponent` il leaga de `AuthService.user$`. Pagini doar pentru ADMIN folosesc `adminGuard` (`core/guards/role.guards.ts`; ascunde, nu securizeaza). Un test Playwright cu sesiune existenta foloseste `signedInAs(page, role, readOnly)` din `e2e/theme.spec.ts` (JWT nesemnat in `localStorage` prin `addInitScript`); `stubGateway` accepta `{bytes, contentType}` pentru raspunsuri binare si inregistreaza `parts` ale cererilor multipart.
- **Frontend (P11.1)**: `AuthService` (`core/services`) tine sesiunile per tenant in `localStorage` (`msaas.sessions` + `msaas.activeTenant`), tokenul de identitate si alegerea de tenant doar in memorie; `AuthInterceptor` trimite tokenul tenantului activ la fiecare cerere si tokenul de identitate DOAR la `POST /organizations` si `POST /invitations/accept`. `app.config.ts` trebuie sa pastreze `provideHttpClient(withInterceptorsFromDi())`, altfel interceptorul clasa nu ruleaza. Un test Angular care randeaza o pagina cu `routerLink` foloseste `provideRouter([])` si `spyOn(router, 'navigate')`, nu un Router fals. Helpere de test: `src/testing/sessions.ts` (JWT-uri nesemnate). Teste de browser: `e2e/*.spec.ts` cu `e2e/gateway-stub.ts` (`stubGateway(page, handler)` raspunde apelurilor catre `http://localhost:8080` si le inregistreaza, cu antetul `Authorization`); `test.describe('REQ-...')` conteaza la trasabilitate. Notificarile si tema (P11.2/11.3) se leaga de `AuthService.user$` (tenantId, role, readOnly din token).
- **Compatibilitatea API (P10.3)**: `ApiSnapshotIT` in auth/organization/billing/notification compara `/v3/api-docs` (si schema GraphQL a jurnalului) cu `src/test/resources/api-snapshots/` al modulului. Orice diferenta structurala esueaza: ce a disparut/s-a schimbat = "contract rupt" (NU se rescrie snapshot-ul; se pastreaza vechiul comportament si se adauga o cale `/v2/...` sau `@deprecated` + camp nou), ce s-a adaugat = "snapshot desactualizat" (se regenereaza: `./mvnw -B -ntp -pl <modul> -am verify -Dtest=NoSuch -Dit.test=ApiSnapshotIT -Dapi.snapshot.update=true -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false`, apoi se ruleaza din nou fara `-D...update` si se commite snapshot-ul). Orice pas nou care adauga un endpoint, un camp de raspuns sau un camp GraphQL trebuie sa regenereze snapshot-ul modulului. Logica de comparatie e in `test-support` (`com.saas.testsupport.api`).
- **Rate limiting (P10.2)**: `com.saas.gateway.ratelimit` in api-gateway. Filtrul (ordinea 0) vine dupa `GatewayJwtFilter` (-1) si citeste doar `X-User-Id`/`X-Tenant-Id` puse de acesta; caile publice (`GatewayJwtFilter.isPublicPath`) nu sunt limitate. Decizia e un script Lua atomic in Redis (`scripts/rate-limit.lua`), cu ferestre de o secunda si timpul din `Clock`. 10 req/s per user, 5 req/s per user activ pentru tenant (activ = request in ultimele 5 min, set Redis per tenant); 429 + `Retry-After` + `X-RateLimit-Scope`; fail-open daca Redis nu raspunde. Un test nou de gateway cu context complet defineste TOATE rutele din `spring.cloud.gateway.routes[i]` ca proprietati de sistem (listele nu se combina sigur cu YAML-ul), vezi `RateLimitWiringIT`.
- **Cache Redis (P10.1)**: pachetul `com.saas.org.cache` (`TenantCache`, `MembershipAccessCache`, `CacheRegions`), doar in org-service. Cheia contine mereu tenantul (`org:cache:<regiune>:<tenant>[:<user>]`). Se pune in cache: tema (`ThemeService.getTheme`), rolul/flag-ul doar-citire al unui membru (doar pentru citiri de tip "e membru?", acum `ThemeService`) si raspunsul billing despre accesul la scriere (`TenantAccessStatusClient`, TTL 1 min). Stergerea: tema la `updatePalette` si schimbarea logoului, rolul la schimbare de rol / acceptarea retrogradarii / acceptarea invitatiei / offboarding (dupa commit, in afara tranzactiei), statusul la `SUBSCRIPTION_CHANGED` (`SubscriptionChangedListener`, inainte de proiectia `max_users`). Un rezultat null (nu e membru, billing inaccesibil -> fail-open ACTIVE) nu se pune niciodata in cache; Redis cazut = se citeste sursa, nu esueaza cererea. Emiterea tokenului (`MembershipLookupService`) NU foloseste cache-ul. `saas.cache.enabled=false` in `organization-service/src/test/resources/application.properties`: ITs existente ruleaza fara cache; `RedisCacheIT` il porneste cu Redis real. Un loc nou care schimba un membership trebuie sa cheme `MembershipAccessCache.evict`; un loc nou care schimba `theme_json` trebuie sa cheme `ThemeService.evictTheme`.
- **Lunch roulette (P9.7)**: pachetul `com.saas.org.lunch` (`LunchService`, `LunchController`, `LunchJobs`, `LunchGrouper`, `LunchSchedule`), migrarea `V12`. O runda per saptamana (luni in fusul tenantului), creata la prima inscriere/anulare (`insertIfAbsent` + blocare de rand); citirile nu scriu. Jobul `LunchJobs.formDueRounds` gaseste tenantii prin rolul `org_auth_reader` (`LunchRoundFinder`) si inchide fiecare runda intr-o tranzactie de tenant; intreaba `TenantAccessStatusClient` o data per tenant (gratie/suspendare -> `TENANT_RESTRICTED`, deci regimul de gratie e aplicat si actiunilor automate). Retentia (90 zile) ruleaza tot per tenant, sub RLS. Evenimentele `LUNCH_*` poarta doar id-uri, nu sunt in jurnal. Un IT nou foloseste sablonul `LunchRouletteIT` (container cu topologie, `MutableClock`, `Random` `@Primary` cu seed, cron-uri dezactivate cu `-`).
- **Modulul de concediu (P9.6)**: pachetul `com.saas.org.leave` (+ `ModuleService`, `ModuleController`, `TenantWriteGuard`, evenimentele `Leave*Payload`/`ModuleChangedPayload`
  in `com.saas.org.event`). Un modul nou = o valoare in `ModuleService.MODULES` + verificarea `modules.requireEnabled(NUME)` la inceputul fiecarei metode de
  serviciu (dupa `requireMember`, inainte de verificarea rolului: modulul oprit e 404, nu 403). Scrierile cheama `TenantWriteGuard.requireWritable` in controller.
  "Astazi" se citeste intotdeauna din `Clock` in fusul tenantului (`LeaveService.today`). Soldul nu se stocheaza: se calculeaza din cereri. Blocarea
  `findByUserIdForUpdate` pe randul de membership serializeaza cererile aceluiasi membru (testat cu 4 fire). Un IT nou pentru org cu RLS real foloseste
  containerul cu topologie (`LeaveModuleIT` e sablonul: stub billing, `MutableClock` pe luni 2026-03-02, helperul `inTenantSession` care reseteaza
  `app.tenant_id` pe conexiunea din pool - altfel `RlsAssertions` vede randurile ramase pe conexiune). Datele din JSON-ul de cerere sunt stricte
  (`@JsonFormat(pattern="yyyy-MM-dd")`): altfel Jackson taie ora si accepta "jumatati de zi". Evenimentele de concediu tin datele ca siruri ISO; pentru
  `Instant` din outbox vezi nota de la P9.5. Notificarile de concediu nu cer lista de membri: `LEAVE_REQUESTED` poarta id-urile aprobatorilor.- **Notificari in-app (P9.5)**: pachetul `inapp` din notification-service. Regulile (eveniment -> destinatar -> text) sunt toate in
  `InAppNotificationRules`; destinatarii "ADMIN/MANAGER" se cer la momentul evenimentului de la org-service
  (`GET /internal/tenants/{id}/members?roles=`, `MemberDirectory`), nu dintr-o copie locala a rolurilor. Evenimente fara rol in payload
  (`TRIAL_EXPIRING`, `SUBSCRIPTION_CHANGED`) vin de la billing; `SubscriptionChangedPayload` are `previousStatus` ca sa se vada
  tranzitia in PAST_DUE. Retentia (90 zile) sterge peste tenanti FARA rol BYPASSRLS: politicile `retention_purge_*` din `V7` deschid
  doar randurile mai vechi decat `app.purge_before`, setat de job in propria tranzactie; acelasi tipar rezolva orice curatare
  cross-tenant viitoare (curatarea exporturilor expirate din P9.3 inca se face per tenant, la urmatorul export). Testele RLS
  folosesc `SET LOCAL ROLE` pe un rol non-superuser creat in tranzactie (vezi `InAppRetentionIT.asTester`), singura cale de a proba RLS
  cand containerul de test ruleaza ca superuser. Timpurile Instant din payload-urile outbox sunt numere zecimale (secunde epoch):
  parseaza-le cu `ObjectMapper.treeToValue(node, Instant.class)`, nu cu `Instant.parse`. `spring.kafka.listener.auto-startup=false` e
  respectat si de fabrica custom (inainte fiecare context de test cu `@KafkaListener` consuma, indiferent de proprietate). Un test Kafka
  cap-coada pentru un listener nou cere un grup propriu sau un context propriu: consumatorii din contextele Spring cache-uite
  disputa partitiile (un singur partition per topic in teste), de aceea in-app are doar un test structural de legatura.- **Email-uri (P9.4)**: pachetul `email`. `EmailCommandListener` consuma `notif.email-commands` (exchange-uri fanout `org|auth|billing.commands`,
  declarate de notification-service la pornire - releul unui producator nu poate publica pana exista exchange-ul). Un tip nou de email =
  un `case` in listener + un sablon in `EmailTemplates` + un test de contract in `EmailDeliveryIT` (JSON scris de mana, nu din clasele
  serviciului). Email de tenant = inregistrat (`email_logs` cu id = id-ul comenzii, + `EMAIL_SENT`/`EMAIL_FAILED` in jurnal, fara adresa);
  email de identitate (comenzile de parola) = niciodata inregistrat, chiar daca plicul are tenantId. Reincercarile (3, 1 s/2 s/4 s)
  sunt in `EmailSender`, nu in containerul Rabbit; containerul retrimite doar esecurile de procesare (DB, JSON invalid) si apoi
  DLQ. `EmailProvider` real (SMTP) nu exista in v1: `SimulatedEmailProvider`. Un IT inlocuieste furnizorul si `EmailSender.Sleeper`
  cu bean-uri `@Primary` (vezi `EmailDeliveryIT`). Pentru "nu s-a intamplat nimic" publica o comanda ulterioara si asteapta-o: un singur
  consumator le trateaza in ordine. Un tenant diferit pentru comanda-santinela, altfel ea apare in jurnalul verificat.
  Cozile RabbitMQ sunt ale brokerului partajat, nu ale contextului: orice context Spring cache-uit cu listener pornit le consuma. Un IT care foloseste o coada seteaza pe celelalte saas.email.listener-auto-startup=false / saas.export.listener-auto-startup=false (vezi ActivityLogExportIT si EmailDeliveryIT); un consumator RabbitMQ nou are nevoie de acelasi comutator.- **Export CSV (P9.3)**: `POST /notifications/activity-log/exports` (doar ADMIN) + `GET .../{id}` si `/{id}/download`, in pachetul `export/`.
  `ExportTimings` decide inline/fundal din media masurata ms/intrare x numarul de intrari fata de `saas.export.async-threshold`; un IT
  isi fixeaza decizia inregistrand 20 de masuratori (`measuredCost` in `ActivityLogExportIT`), altfel rularile inline anterioare o schimba.
  Jobul de fundal = rand in `notif.export_jobs` + comanda in outbox spre exchange-ul fanout `notif.export-commands`; fisierul sta in
  `content` (BYTEA) pana la `expires_at` si se sterge la urmatorul export al aceluiasi tenant (o curatare intre tenanti nu se poate sub RLS;
  un job de platforma ar cere un rol BYPASSRLS ca `outbox_relay`). Un job care esueaza de 5 ori ajunge in `notif.export-jobs.dlq` si ramane
  PENDING (nu exista stare FAILED). Releul outbox e oprit in teste: `ActivityLogExportIT.drainOutbox()` il inlocuieste. `ActivityLogFilterParser`
  si `security/Caller` sunt comune cu GraphQL; un IT care foloseste `OutboxWriter` trebuie sa seteze `spring.datasource.hikari.connection-init-sql=SET search_path TO notif,public`.- **Citirea jurnalului (P9.2)**: GraphQL pe `/notifications/graphql` (`graphql/` + `activity-log.graphqls`), doar `Query`; identitatea vine
  din antetele gateway-ului prin `CallerContextInterceptor`, niciodata din argumente. `QueryOnlyInterceptor` refuza mutatii/subscriptii
  inainte de executie. Limite in `ActivityLogGraphQlConfig` (`saas.graphql.*`). Un IT GraphQL cu MockMvc cere `asyncDispatch`;
  cel de productie foloseste `@ActiveProfiles("prod")`. Testele cu Postgres de test nu probeaza RLS (superuser), doar filtrul explicit.
- **Jurnalul (P9.1)**: `ActivityEventCatalog` (shared-lib) e contractul dintre producatori si jurnal: tip -> camp actor +
  campuri de metadata. Un tip nou auditat = o linie in catalog + publicarea evenimentului (outbox, Kafka, doar ID-uri) + un
  test de contract in serviciul producator (model: `ActivityEventsPublishedIT`). `ActivityLogListener` are grup Kafka propriu
  (`notif-service-activity-log`): doua listenere in acelasi grup pe acelasi topic isi impart partitiile. Evenimentele
  neincluse in catalog (`MEMBER_OFFBOARDED`, parola, `USER_REGISTERED`) nu ajung in jurnal; offboarding-ul nu e in lista
  din cerinte, deci nu e jurnalizat (decizie de luat daca se doreste). Un IT in notification care porneste listenerii
  trebuie sa apeleze `KafkaTestTopics.ensureListenedTopicsExist()` intr-un bloc static.
- **Feature flags (P8.3)**: `org.tenant_settings.feature_flags_json` (JSONB, aceeasi linie ca tema). `FeatureFlagService.isEnabled(tenantId, flag)`
  e API-ul pentru cod; flag necunoscut/nesetat = dezactivat. Nume declarate in `saas.feature-flags.known` (env`r
  `FEATURE_FLAGS_KNOWN`). Scrierea e doar pe `/internal/**` (nerutat de gateway, pazit de `GatewayRoutesTest`); tenantul
  citeste prin `GET /organizations/{tenantId}/feature-flags`. Org `GlobalExceptionHandler` mapeaza acum `ErrorResponse`;
  auth/billing/notification au inca `handleGeneric` care transforma 404/405 in 500 (neremediat, in afara pasului).
- **Logo (P8.2)**: `ObjectStorage` (`com.saas.org.storage`) e singura cale spre fisiere; `LocalObjectStorage` e
  `@Component` si doar pachetul ei o cunoaste (ArchUnit). Cheie `tenants/<tenantId>/logo-<uuid>.<png|jpg>`.
  `LogoValidator` decide dupa bytes, nu dupa nume. Limite in `saas.logo.*` (2 MB, 1024x1024). Un nou furnizor
  (S3) inseamna o a doua implementare + configurare. In IT-uri, `saas.storage.local.root` arata spre un director temporar.
- **Tema (P8.1)**: `global.palettes` (auth-service `V9`, citit de org-service prin `PaletteCatalogReader`, un
  `DataSource` propriu, nu bean); `org.tenant_settings.theme_json` JSONB, o singura linie per tenant, creata la
  `createOrganization` (tema implicita `saas.theme.default-palette-id`); `ThemeService` + `ThemeController`
  (`/organizations/{tenantId}/theme`). `UpdateThemeRequest` respinge orice proprietate in afara `paletteId`
  (`ignoreUnknown=false` nu functioneaza cu mapper-ul Spring Boot: folosim `@JsonAnySetter` + `@AssertTrue`).
  P8.3 adauga flag-urile in aceeasi tabela (coloana noua), nu intr-una separata.
- **Consumatori Kafka** (model: `SubscriptionChangedListener` in org-service, `MemberOffboardedListener` in
  notification-service si auth-service): clasa fara `@Component`, bean-uri explicite intr-o `@Configuration`,
  payload `String` + `ObjectMapper`, idempotent prin `ProcessedEventStore`, `EventConsumerSupport`, retry +
  DLT prin `KafkaDeadLetterSupport`. Un `kafkaListenerContainerFactory` propriu **ignora**
  `spring.kafka.listener.auto-startup=false`: in teste listenerul porneste oricum.
- **REQ-EVT-08** (ArchUnit in org-service): doar `OrganizationService` construieste un `Tenant`, niciun
  listener nu depinde de el.
- **Token de identitate** (REQ-AUTH-03): la login cu 0 tenanti, `identityToken` (`JwtService.generateIdentityToken`,
  doar `sub`, `typ=identity`, 15 min); `JwtService.parse` il respinge; gateway-ul il accepta doar pe
  `POST /organizations`, `POST /invitations/accept`, `DELETE /auth/account`. Orice endpoint ia userul din
  `X-User-Id`, niciodata din body (REQ-AUTH-05).
- **Invitatii**: `tenants.max_users` ca proiectie locala (NULL = fail-closed); acceptarea e blocata cand
  tenantul e READ_ONLY/SUSPENDED; `org_auth_reader` citeste din `invitations` doar `tenant_id`, `token` (`V7`).
- **Offboarding (P7.2)**: `DELETE /organizations/{tenantId}/members/{userId}` publica `MEMBER_OFFBOARDED` pe
  `org.events` (doar ID-uri). Consumatori: notification (sterge notificarile, anonimizeaza
  `email_logs.to_email` dupa `recipient_user_id`), auth (revoca refresh tokenurile tenantului, anonimizeaza
  identitatea cand userul nu mai are tenanti). **P9.4** trebuie sa completeze `recipient_user_id`; **P9.6/P9.7**
  trebuie sa consume acelasi eveniment ca sa-si stearga datele userului. Coloanele cu date personale din shard
  sunt pazite de `SchemaConventionsIT` (`org.invitations.email`, `notif.email_logs.to_email`).

Reguli de proces (sectiunea 0, regulile 10-12 din plan): sufixele de commit sunt doar `(cerinte)`/`(deviere)`/
`(proces)`; linia din Jurnal ramane scurta; `.agents/` e ignorat de git; lucrul altui agent se verifica cu
`clean verify`, trasabilitate si verificarea fata de plan.
## 6. Note/lectii utile (nu repeta greselile)

- Un test de integrare nu poate presupune o tabela goala: containerul Postgres partajat (`PostgresTestContainer`)
  e comun tuturor IT-urilor din rulare (compara numarul inainte/dupa, nu cu zero). Cu superuser-ul containerului
  `search_path` al rolului serviciului lipseste: pentru cod cu tabele necalificate (`ProcessedEventStore`) seteaza
  `spring.datasource.hikari.connection-init-sql=SET search_path TO <schema>,public`.
- `SchemaConventionsIT` sorteaza migrarile dupa numarul versiunii (nu lexicografic: `V10` ar trece inaintea `V1__baseline`).
- Fiecare IT cu container propriu (`startTopology()`) are nevoie de `test.marker=<NumeClasa>` in `@SpringBootTest`:
  Spring refoloseste contextul intre clase cu aceeasi configuratie, deci o clasa ar primi DataSource-ul containerului
  deja oprit al alteia (`Connection is not available, request timed out`).
- Un listener Kafka care se aboneaza la un topic inexistent il observa abia dupa refresh-ul de metadate (minute):
  in teste creeaza topicul intai (`AdminClient`).
- `mvnw verify` fara `clean` poate esua cu "Unresolved compilation problem": serverul Java din editor
  (Eclipse JDT) scrie peste `target/classes`. Ruleaza `mvnw clean verify` atomic. Cu `-pl` pastreaza `-am`;
  `-rf` foloseste un `shared-lib` invechit din `~/.m2`.
- `ensure-dev-env.mjs` porneste Docker Desktop detasat (`detached`, `stdio: 'ignore'`) si toate comenzile
  au timeout; un `spawnSync` care mosteneste pipe-urile unui proces de lunga durata nu se termina niciodata
  (`DevEnvironmentScriptTest` pazeste asta).
- `edit` pe un fisier cu CRLF: potrivirile pe mai multe linii merg, dar un `old_str` identic in doua locuri
  e respins; verifica dupa fiecare editare ca nu a ramas o adnotare duplicata sau lipsa.
- La editarea YAML cu `replace_string_in_file`, citeste tot blocul inainte si dupa - un `oldString` scurt
  (ex. doar `relay:\n  enabled: false`) poate lasa liniile urmatoare (schema/datasource) orfane sub
  parintele gresit -> `DuplicateKeyException` la pornire. S-a intamplat o data in `billing-service`.
- Un al doilea bean `DataSource` neconditionat/necalificat sparge auto-configurarea Flyway/JPA - construieste
  mereu DataSource-urile suplimentare ca field simplu in constructorul clasei care le foloseste.
  `@AutoConfiguration` cu `@ConditionalOnBean(DataSource.class)` are nevoie de
  `@AutoConfigureAfter(DataSourceAutoConfiguration.class)` explicit, altfel conditia poate fi evaluata
  prea devreme.
  `spring.jpa.open-in-view=true` (implicit Boot) + multi-tenancy Hibernate e o capcana: sesiunea OSIV se
  deschide/fixeaza tenantul inainte ca request-ul sa apuce sa seteze `TenantContext` -> seteaza
  `open-in-view: false` in toate serviciile tenant-scoped.
- `@TestConfiguration` cu un `@Bean Clock` care suprascrie fallback-ul de productie trebuie sa fie
  `@Primary`, altfel Spring poate rezolva non-determinist beanul gresit (bug latent descoperit o data,
  retroactiv in alta clasa de test).
- Testcontainers + `@DynamicPropertySource` s-a dovedit nesigur in acest proiect: foloseste
  `System.setProperty(...)` intr-un initializator static eager in loc.
- `JdbcShardMappingRepository`/`ShardResolver` exista in shared-lib (P2.4) dar NU sunt cablate/folosite in
  productie inca - exista un singur shard azi, asa ca fiecare serviciu se conecteaza direct la
  `saas_shard_1` prin `spring.datasource.url`. Nu presupune ca rutarea pe shard-uri functioneaza end-to-end.
- `.env` contine parole de dezvoltare publice INTENTIONAT (decizie 2026-09-30, de revizuit inainte de
  orice deploy real) - citeste scripturile/aplicatia din `.env`, nu codifica parole in cod.
- Secretele niciodata prin `vscode_askQuestions`; raspunsurile catre utilizator sunt in romana (vezi
  `copilot-instructions.md`); skill-ul `/caveman` (intensitate `full`) se foloseste la implementare, proza
  normala pentru decizii de cerinte/securitate/operatii ireversibile.

## 7. Comenzi utile

```powershell
# Mediu (Docker, JDK, imagini) - inainte de orice test de integrare
node scripts/env/ensure-dev-env.mjs --check   # doar raporteaza
node scripts/env/ensure-dev-env.mjs           # porneste Docker daca lipseste

# Build/teste
.\mvnw.cmd -B -ntp verify                               # tot (8 module)
.\mvnw.cmd -B -ntp -pl <modul> -am verify                # un modul + dependintele
.\mvnw.cmd -B -ntp -pl <modul> -am verify "-Dit.test=NumeClasa" "-Dfailsafe.failIfNoSpecifiedTests=false"

# Trasabilitate REQ -> test (obligatoriu inainte de a bifa un pas)
node scripts/verify/check-traceability.mjs

# Stiva locala pentru verificari manuale (fara porturi publicate, nu intra in conflict cu Postgres nativ)
node scripts/infra/local-stack.mjs --up
node scripts/infra/local-stack.mjs --down
```

## 8. Ce sa faci cu acest fisier

Nu e sursa de adevar - daca intra in contradictie cu `IMPLEMENTATION_PLAN.md`/`REQUIREMENTS.md`, acelea
castiga. Actualizeaza-l (sau sterge-l) cand devine invechit; cel mai simplu semnal ca trebuie actualizat:
sectiunea 5 (status) nu mai corespunde cu primul pas nebifat din plan.
