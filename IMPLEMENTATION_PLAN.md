# Plan de implementare MSaaS

Sursa de adevar pentru CE se construieste: [REQUIREMENTS.md](REQUIREMENTS.md).
Sursa de adevar pentru CUM si IN CE ORDINE: acest document.

---

## 0. Reguli de lucru (obligatorii la fiecare prompt)

1. Inainte de orice modificare de cod, se citeste acest plan si se identifica pasul curent: primul pas nebifat (`[ ]`) in ordinea fazelor.
2. Se lucreaza doar pe pasul curent. Daca o cerere a utilizatorului iese din pasul curent, se spune explicit carui pas ii corespunde (sau ca e o deviere de la plan) si se cere confirmare inainte de a continua.
3. Fiecare pas se considera terminat doar daca:
   - codul pentru toate REQ-urile pasului este implementat;
   - fiecare REQ al pasului are cel putin un test automat marcat cu `@Tag("REQ-...")` (sau `describe('REQ-...')` in frontend);
   - `mvnw verify` pentru modulele atinse trece (fara teste sarite/`@Disabled`); pentru frontend `npm run build` + `npm run test:ci`;
   - scriptul de trasabilitate (P0.3) trece;
   - verificarea fata de plan e completa: fiecare element din descrierea pasului e facut, fiecare punct din lista `Teste:` are un test automat, nu s-a modificat nimic in afara pasului fara confirmare;
   - pasul este bifat (`[x]`), are o linie in Jurnalul de progres (sectiunea 5) si un commit local `P<faza>.<pas>: <rezumat>`.
4. Schimbarile de cerinte (doar la cerere explicita) se fac in ordinea: clarificarea deciziilor deschise -> REQUIREMENTS.md (inclusiv sectiunile existente atinse) -> verificarea contradictiilor in tot documentul -> catalogul din sectiunea 1 (toti pasii care implementeaza ID-ul, inclusiv frontend) -> pasii afectati si testele lor -> sectiunea 4 -> Jurnalul de progres -> commit `P<pas curent> (cerinte): ...`.
5. Parametrii din sectiunea 4 sunt valori implicite configurabile, nu cerinte; se schimba doar in configurare.
6. Nu se sterg/rescriu teste existente ale unui pas deja terminat fara a explica de ce si fara a pastra acoperirea REQ-urilor lui.
7. Nu se fac push-uri, stergeri de branch-uri sau alte actiuni ireversibile fara confirmare.
8. La fiecare prompt care modifica cod sau cerinte se verifica conformitatea cu REQUIREMENTS.md (REQ-urile pasului curent + orice REQ afectat); nepotrivirile se raporteaza explicit in raspuns. Inainte de bifarea unui pas se raporteaza si verificarea fata de plan: `Pn.m: descriere OK / teste OK / fara extra OK` (sau ce lipseste).
9. Mesajele de commit incep cu ID-ul pasului (`P0.2: ...`); modificarile din afara pasului folosesc pasul curent + sufix (`P0.3 (cerinte): ...`, `P0.3 (deviere): ...`).

---

## 1. Catalog de cerinte (trasabilitate)

Fiecare ID trebuie sa apara in cel putin un test (tag) dupa ce pasul lui e bifat.

| ID | Rezumat | Pas |
|---|---|---|
| REQ-AUTH-01 | Login email+parola; 1 tenant -> token direct | P5.2 |
| REQ-AUTH-02 | >1 tenant -> lista + `loginSessionId` opac, temporar, single-use; al doilea apel emite tokenul | P5.2 |
| REQ-AUTH-03 | 0 tenanti -> lista goala; userul poate crea organizatie (devine ADMIN) | P4.1, P5.2 |
| REQ-AUTH-04 | Token separat per tenant; fara token multi-tenant comutabil | P5.3 |
| REQ-AUTH-05 | Claims: userId, tenantId, rol, info UI; fara date despre alte tenanturi | P5.3 |
| REQ-ROLE-01 | ADMIN: toate privilegiile org/billing/useri | P4.2, P6.5 |
| REQ-ROLE-02 | ADMIN invita cu orice rol; doar ADMIN invita ADMIN/MANAGER | P7.1 |
| REQ-ROLE-03 | Cel putin un ADMIN per organizatie | P4.3 |
| REQ-ROLE-04 | Retragerea care lasa un singur ADMIN cere acceptul celui vizat; timeout -> escaladare suport | P4.3 |
| REQ-ROLE-05 | USER: acces de baza, fara management, fara invitatii | P4.2, P7.1 |
| REQ-ROLE-06 | Flag scriere/doar-citire pe membership, independent de rol | P4.1 |
| REQ-INV-01 | Expirare invitatii configurabila per tenant | P7.1 |
| REQ-INV-02 | ADMIN poate revoca o invitatie | P7.1 |
| REQ-MGR-01 | MANAGER invita doar cu rolul USER | P7.1 |
| REQ-MGR-02 | MANAGER vede billing, fara actiuni financiare | P6.5 |
| REQ-MGR-03 | Managementul rolurilor exclusiv ADMIN | P4.2 |
| REQ-MGR-04 | MANAGER vede jurnalul de activitate | P9.2 |
| REQ-MGR-05 | Fara limita min/max de MANAGER | P4.2 |
| REQ-DATA-01 | Offboarding: anonimizare nume/email in tenant, stergere PII tenant; identitatea globala neatinsa | P7.2 |
| REQ-DATA-02 | `tenant_id` + mapare `tenant_id -> shard_id` (un shard azi) | P2.3, P2.4 |
| REQ-DATA-03 | Doua straturi: filtrare in aplicatie + RLS | P2.2, P2.3 |
| REQ-DATA-04 | Un DB per shard, o schema per serviciu, un rol DB per serviciu | P2.1 |
| REQ-DATA-05 | DB globala: identitati, cataloage, mapare shard | P2.1 |
| REQ-DATA-06 | Rezolvarea shard-ului prin maparea globala cache-uita | P2.4 |
| REQ-DATA-07 | Denumiri: `tenant_id`; roluri ADMIN/MANAGER/USER | P2.3 |
| REQ-DATA-08 | `SET LOCAL app.tenant_id` per tranzactie; RLS fail-closed | P2.2 |
| REQ-DATA-09 | Scheme recreate cu Flyway, fara migrare de date | P2.3 |
| REQ-DATA-10 | Fara FK intre servicii / intre shard si DB globala | P2.3, P2.5 |
| REQ-DATA-11 | FK compuse (`tenant_id`, `id`) in interiorul unui serviciu | P2.3 |
| REQ-DATA-12 | Intrarile din cataloagele globale se dezactiveaza, nu se sterg | P6.1 |
| REQ-DATA-13 | Date stocate intr-o singura regiune (EU) | P12.3 (verificare de deployment) |
| REQ-PERS-01 | Tema (paleta) + logo, doar ADMIN | P8.1, P8.2 |
| REQ-PERS-02 | Paleta din catalog fix (`paletteId`), fara CSS/hex brut | P8.1 |
| REQ-PERS-03 | Logo PNG/JPG, limite marime/dimensiuni, storage abstractizat (local) | P8.2 |
| REQ-PERS-04 | `tenant_settings.theme_json` flexibil | P8.1 |
| REQ-PERS-05 | Tema implicita la crearea tenantului | P8.1 |
| REQ-PERS-06 | Tema prin endpoint dedicat, aplicata la reload | P8.1, P11.2 |
| REQ-PERS-07 | Feature flags per tenant in `tenant_settings` | P8.3 |
| REQ-MON-01 | Se jurnalizeaza actiunile sensibile + livrarea email-urilor de tenant; nu CRUD | P9.1, P9.4, P9.6 |
| REQ-MON-02 | Jurnal vizibil ADMIN+MANAGER | P9.2 |
| REQ-MON-03 | Retentie 6 luni configurabila (minute/ore in test) | P3.5 |
| REQ-MON-04 | Raport la cerere, paginat, filtrabil | P9.2 |
| REQ-MON-05 | Export CSV (ADMIN) | P9.3 |
| REQ-MON-06 | Intrare generica (`event_type`, `actor_user_id`, `tenant_id`, `metadata`, `created_at`) | P3.5 |
| REQ-MON-07 | Partitii lunare, index `tenant_id`, `DROP PARTITION`; job de stergere in test | P3.5 |
| REQ-MON-08 | Citirea jurnalului prin GraphQL doar-citire; tenant din token; limite de adancime/complexitate; introspectie oprita in productie | P9.2, P10.3 |
| REQ-SCALE-01 | Cache Redis cu invalidare prin evenimente; emiterea tokenului fara cache | P5.2, P10.1 |
| REQ-SCALE-02 | Rate limiting per user + agregat per tenant proportional cu userii activi | P10.2 |
| REQ-SCALE-03 | Sync/async decis dinamic pe baza timpilor de executie | P9.3 |
| REQ-SCALE-04 | PgBouncer in transaction pooling | P2.1 |
| REQ-ARCH-01 | Repository de baza cu filtrare automata `tenant_id` | P2.2, P2.5 |
| REQ-ARCH-02 | Feature flags booleene in `tenant_settings` | P8.3 |
| REQ-ARCH-03 | API doar-adaugare; versionare doar la schimbari incompatibile (GraphQL: `@deprecated`, fara versionare) | P10.3 |
| REQ-ARCH-04 | Teste de integrare obligatorii in CI | P0.4 |
| REQ-BILL-01 | Payment gateway abstract + implementare simulata | P6.2 |
| REQ-BILL-02 | Schimbare plan = cancel + resubscribe, fara proratare | P6.3 |
| REQ-BILL-03 | `maxUsers` impus la invitare | P7.1 |
| REQ-BILL-04 | Esec simulat -> PAST_DUE, 7 zile read-only, apoi suspendare | P6.4 |
| REQ-BILL-05 | Fiecare incercare de plata persistata | P6.2 |
| REQ-BILL-06 | Trial 14 zile, conversie automata la ACTIVE | P6.3 |
| REQ-BILL-07 | Moneda unica EUR | P6.1 |
| REQ-PWD-01 | Parola globala per identitate | P5.1 |
| REQ-PWD-02 | Token reset 1h, single-use; o cerere noua le invalideaza pe cele vechi | P5.4 |
| REQ-PWD-03 | Anti-enumerare: raspuns si timp similar | P5.4 |
| REQ-PWD-04 | Access token 15 min; resetul revoca toate refresh token-urile | P5.3, P5.4 |
| REQ-PWD-05 | Rate limit reset: 3/ora per email, 10/ora per IP | P5.4 |
| REQ-PWD-06 | Politica parola: min 12, lista de parole compromise, fara complexitate/istoric | P5.1 |
| REQ-PWD-07 | Email de confirmare dupa schimbarea parolei | P5.4 |
| REQ-PWD-08 | Resetul nu apare in jurnalul de tenant | P5.4, P9.1 |
| REQ-PWD-09 | Resetul poate fi initiat doar de user | P5.4 |
| REQ-NOTIF-01 | Notificari in-app per tenant | P9.5 |
| REQ-NOTIF-02 | Generate doar de sistem; fara endpoint public de creare | P1.3, P9.5 |
| REQ-NOTIF-03 | Lista de evenimente v1 care genereaza notificari | P9.5, P9.6, P9.7 |
| REQ-NOTIF-04 | Incarcare la deschidere, numar necitite prin polling 60s | P9.5, P11.3 |
| REQ-NOTIF-05 | Retentie 90 zile; stergere la offboarding | P9.5, P7.2 |
| REQ-NOTIF-06 | Din email-uri se pastreaza doar metadate | P1.1, P9.4 |
| REQ-NOTIF-07 | Email-urile de tenant in jurnal (`EMAIL_SENT`/`EMAIL_FAILED`); fara endpoint de cautare | P1.1, P9.4 |
| REQ-NOTIF-08 | 3 reincercari cu backoff -> `FAILED` | P9.4 |
| REQ-NOTIF-09 | Fara dezactivare de notificari de catre user | P9.4 |
| REQ-UPL-01 | Fara upload generic; endpoint-ul existent se elimina | P1.2 |
| REQ-EVT-01 | Kafka = evenimente de domeniu (replay); RabbitMQ = comenzi/joburi | P3.2 |
| REQ-EVT-02 | Fara date personale in evenimentele Kafka | P3.1, P7.1 |
| REQ-EVT-03 | Retentie topic-uri Kafka = retentia jurnalului | P3.4 |
| REQ-EVT-04 | Transactional outbox; rol de relay doar pe `outbox`, exceptat de RLS | P3.2 |
| REQ-EVT-05 | At-least-once; consumatori idempotenti pe `eventId` | P3.3 |
| REQ-EVT-06 | Plic tipizat si versionat in `shared-lib`; `tenantId` obligatoriu; consumatorul seteaza contextul | P3.1, P3.3 |
| REQ-EVT-07 | 5 reincercari cu backoff -> DLQ, reprocesare manuala, logare | P3.3 |
| REQ-EVT-08 | Fara tenant automat la inregistrare | P4.1, P5.1 |
| REQ-EVT-09 | Autorizare sincrona la login; proiectii locale in rest | P5.2, P7.1 |
| REQ-EVT-10 | Ordine per tenant: cheie `tenantId` (`userId` pentru identitate) | P3.4 |
| REQ-MOD-01 | Module de business in org-service, tenant-scoped; activare per tenant doar de ADMIN (setare de organizatie, nu feature flag), implicit dezactivate, datele pastrate la dezactivare; doar-citire blocheaza orice scriere; gratie/suspendare aplicate si actiunilor automate; fus orar per tenant ales de ADMIN (implicit `Europe/Bucharest`) | P9.6, P9.7, P11.5 |
| REQ-LEAVE-01 | Cerere pentru sine: `ODIHNA`/`FARA_PLATA`, zile intregi in acelasi an, motiv vizibil doar aprobatorilor | P9.6 |
| REQ-LEAVE-02 | Se numara doar zilele lucratoare (fara weekend si zile libere definite de ADMIN) | P9.6 |
| REQ-LEAVE-03 | Sold `ODIHNA`: implicit per tenant + suprascriere per user (ADMIN); disponibil = alocat - aprobat - pending; depasire sau suprapunere respinsa | P9.6 |
| REQ-LEAVE-04 | Stari `PENDING`/`APPROVED`/`REJECTED`/`CANCELLED`; anulare doar inainte de inceput, zilele revin | P9.6 |
| REQ-LEAVE-05 | Aproba MANAGER/ADMIN fara flag doar-citire, niciodata propria cerere; solicitantul e singurul aprobator -> auto-aprobare jurnalizata | P9.6 |
| REQ-LEAVE-06 | Vizibilitate: proprii + sold; calendar echipa fara tip/motiv; aprobatorii vad tot | P9.6, P11.5 |
| REQ-LEAVE-07 | Notificari (cerere noua catre ceilalti aprobatori; aprobare/respingere/anulare de catre aprobator catre solicitant) + intrari in jurnal | P9.6 |
| REQ-LEAVE-08 | Offboarding: cererile userului din tenant se sterg | P9.6 |
| REQ-LUNCH-01 | Configurare (zi, ora limita, marime grup 2-4) de MANAGER/ADMIN | P9.7 |
| REQ-LUNCH-02 | O runda pe saptamana; inscriere per runda, retragere pana la ora limita; anulare de MANAGER/ADMIN | P9.7 |
| REQ-LUNCH-03 | Grupare aleatoare la ora limita, minim 2 per grup, evitarea perechilor recente; sub 2 participanti -> runda anulata | P9.7 |
| REQ-LUNCH-04 | Notificare cu grupul (sau anularea); participantii isi vad grupul, MANAGER/ADMIN toate grupurile | P9.7, P11.5 |
| REQ-LUNCH-05 | Retentie 90 zile; offboarding sterge participarile; nu apare in jurnal | P9.7 |

In afara scopului acestei versiuni (nu se testeaza, doar se verifica ca nu au fost introduse): read replicas, autoscaling, monitorizare cross-tenant, rolul TEMP, broadcast de anunturi, multi-moneda, multi-regiune, concediu medical, jumatati de zi si report de zile de concediu.

---

## 2. Strategia de testare (metode pentru integrare rapida)

| Nivel | Unelte | Ce acopera |
|---|---|---|
| Unit | JUnit 5, Mockito, AssertJ | Reguli de business pure (politica de parola, matrici de permisiuni, tranzitii de stare billing) |
| Integrare serviciu | `@SpringBootTest` + Testcontainers (`@ServiceConnection`) | Endpoint-uri, persistenta, RLS, outbox, consumatori |
| Contract evenimente | Teste de serializare round-trip in `shared-lib` | Plicul si versiunile evenimentelor |
| Arhitectura | ArchUnit | Reguli structurale (repository de baza, fara dependente intre servicii, fara PII in evenimente) |
| E2E | Docker Compose + REST Assured | Fluxuri intre servicii prin gateway |
| Frontend | Jasmine/Karma (unit), Playwright (fluxuri principale) | Login cu selectie tenant, tema, notificari, UI pe roluri |

Metode care accelereaza integrarea:
- **Containere singleton reutilizate** (Postgres, Kafka, RabbitMQ, Redis) intr-un modul comun `test-support`, pornite o singura data per rulare si partajate de toate testele; conexiunile se injecteaza automat prin `@ServiceConnection`.
- **`Clock` injectat peste tot** (niciodata `LocalDateTime.now()` direct) si un `MutableClock` in teste: expirari (reset 1h, invitatii, trial 14 zile, gratie 7 zile, retentii) se testeaza instant, fara `sleep`.
- **Fixture-uri comune** in `test-support`: `TenantFixtures` (creeaza tenant + membership-uri), `JwtTestTokens` (tokenuri per tenant/rol), `RlsAssertions` (verifica izolarea A/B), `EventAssertions` (asteapta mesaje in Kafka/RabbitMQ cu Awaitility).
- **Teste parametrizate pe matrici de permisiuni**: un singur test tabelar `(rol, endpoint, status asteptat)` per serviciu acopera REQ-ROLE/REQ-MGR fara teste duplicate.
- **Meta-teste pe schema**: un test interogheaza `pg_catalog` si verifica pentru toate tabelele tenant-scoped ca au `tenant_id`, RLS activat si fortat, si FK compuse; orice tabel nou e acoperit automat.
- **Trasabilitate automata**: tag-urile `REQ-...` sunt verificate de scriptul din P0.3 contra catalogului din sectiunea 1.
- **Ordinea fazelor** construieste intai fundatia (date, evenimente, fixture-uri), astfel incat fiecare functionalitate ulterioara are doar logica ei de scris si testat.

Comenzi standard (`mvnw.cmd` pe Windows / `./mvnw` pe Linux; necesita JDK 21 in `JAVA_HOME`):
- Tot proiectul: `mvnw verify` (din radacina)
- Un modul: `mvnw -pl <modul> -am verify`
- Un REQ: `mvnw verify -Dgroups=REQ-XXX-NN`
- Doar teste unit (fara Docker): `mvnw verify -DskipITs`
- Conventie: testele de integrare se numesc `*IT` sau `*IntegrationTests` (ruleaza in failsafe), restul in surefire
- Teste de integrare in servicii: clasa implementeaza doar interfetele `*TestContainer` de care are nevoie; listenerii pentru brokerii nefolositi se opresc cu `spring.rabbitmq.listener.simple.auto-startup=false` / `spring.kafka.listener.auto-startup=false`; aceeasi configuratie `@SpringBootTest` in toate clasele unui serviciu ca sa se refoloseasca contextul; pentru rute se injecteaza `@Qualifier("requestMappingHandlerMapping")` (actuator adauga un al doilea bean)
- Trasabilitate: `pwsh scripts/check-traceability.ps1` (testul scriptului: `pwsh scripts/check-traceability.tests.ps1`)
- Frontend (din `angular_base`): `npm run build`, `npm run test:ci` (Karma headless; local fara Chrome: `$env:CHROME_BIN` catre `msedge.exe`)
- CI: `.github/workflows/ci.yml` (joburi backend, traceability, frontend); ruleaza la push pe `main` si la pull request
- Conventie tag-uri: Java `@Tag("REQ-XXX-NN")` pe clasa sau metoda de test (in `src/test`); frontend `describe('REQ-XXX-NN ...')` in `*.spec.ts`. Un REQ devine obligatoriu de acoperit cand oricare dintre pasii lui din catalog e bifat; tag-urile care nu exista in catalog fac verificarea sa esueze.

---

## 3. Faze si pasi

### Faza 0 - Fundatie de build si test
- [x] **P0.1 POM agregator la radacina**: `pom.xml` parinte cu Java 21, Spring Boot 3.2.x, BOM-uri (Testcontainers, Spring Cloud), surefire (unit) + failsafe (integrare); module: `shared-lib`, `test-support`, `api-gateway`, `auth-service`, `organization-service`, `billing-service`, `notification-service`.
  - Teste: build-ul complet `mvn verify` ruleaza din radacina.
- [x] **P0.2 Modulul `test-support`**: containere singleton + `@ServiceConnection`, `MutableClock`, `TenantFixtures`, `JwtTestTokens`, `RlsAssertions`, `EventAssertions`. Testele existente care nu mai corespund cerintelor se rescriu in pasul care atinge zona respectiva.
  - Teste: un test de fum care porneste toate containerele o singura data si se conecteaza la fiecare.
- [x] **P0.3 Trasabilitate**: conventia `@Tag("REQ-...")` + `scripts/check-traceability.ps1`, care citeste catalogul din sectiunea 1 si esueaza daca un REQ al unui pas bifat nu are niciun test.
  - Teste: scriptul raporteaza corect un REQ lipsa (test al scriptului pe un catalog de exemplu).
- [x] **P0.4 CI**: GitHub Actions cu `mvn verify` (unit + integrare), scriptul de trasabilitate, build + teste Angular. REQ: ARCH-04.
  - Teste: pipeline-ul esueaza la un test rosu si la un REQ fara test.

### Faza 1 - Inchiderea gaurilor de securitate existente
- [x] **P1.1 Jurnalul de email-uri**: se elimina `GET /notifications/emails-by/{email}`; se opreste salvarea body-ului email-urilor. REQ: NOTIF-06, NOTIF-07.
  - Teste: endpoint-ul raspunde 404; entitatea/tabela nu mai are body.
- [x] **P1.2 Upload generic**: se elimina `POST /notifications/uploads` si directorul `uploads/`. REQ: UPL-01.
  - Teste: endpoint-ul raspunde 404.
- [ ] **P1.3 Creare publica de notificari**: se elimina `POST /notifications/in-app`. REQ: NOTIF-02.
  - Teste: endpoint-ul raspunde 404.
- [ ] **P1.4 Feature flags globale neprotejate**: se elimina endpoint-urile globale din notification-service si `FeatureFlagService` (inclusiv flag-ul `FILE_UPLOADS`, ramas fara functionalitate dupa P1.2); vor fi inlocuite de flag-uri per tenant in P8.3.
  - Teste: endpoint-urile raspund 404.

### Faza 2 - Fundatia de date multi-tenant
- [ ] **P2.1 Topologia Postgres**: DB `saas_global` + DB `saas_shard_1` cu schemele `org`, `billing`, `notif`; un rol DB per serviciu cu acces doar la schema lui; rol separat pentru relay-ul de outbox; PgBouncer in transaction pooling in `docker-compose.yml`. REQ: DATA-04, DATA-05, SCALE-04.
  - Teste: fiecare rol se conecteaza la schema lui; accesul la schema altui serviciu esueaza cu "permission denied"; conexiunea prin PgBouncer functioneaza.
  - Reamintire: verifica daca imaginile `bitnami/kafka:latest` / `bitnami/zookeeper:latest` din `docker-compose.yml` mai pot fi descarcate (Bitnami a retras imaginile gratuite); daca nu, trecere la o imagine oficiala cu versiune fixata (ex. `apache/kafka`, KRaft, fara Zookeeper).
- [ ] **P2.2 Tenancy in `shared-lib`**: `TenantContext`, `TenantScopedEntity` (`tenant_id`), repository de baza care filtreaza automat si executa `SET LOCAL app.tenant_id` la inceputul fiecarei tranzactii; exceptie daca lipseste contextul. REQ: DATA-03, DATA-08, ARCH-01.
  - Teste: fara context -> zero randuri/exceptie; tenantul A nu vede datele lui B nici prin repository, nici prin SQL brut (RLS).
- [ ] **P2.3 Migrari Flyway noi**: scheme recreate de la zero; `tenant_id` peste tot; roluri `ADMIN/MANAGER/USER`; RLS activat si fortat, fail-closed; cheie unica (`tenant_id`, `id`) si FK compuse; fara FK intre scheme. REQ: DATA-02, DATA-03, DATA-07, DATA-09, DATA-10, DATA-11.
  - Teste: meta-test pe `pg_catalog` (toate tabelele tenant-scoped au `tenant_id`, RLS fortat, fara FK cross-schema); un insert cu FK catre randul altui tenant este respins.
- [ ] **P2.4 Maparea tenant -> shard**: tabela `tenant_shards` in DB globala, `ShardResolver` cu cache, `DataSource` de rutare (un singur shard azi). REQ: DATA-02, DATA-06.
  - Teste: tenant cunoscut -> shard corect; tenant necunoscut -> eroare; cache invalidat la schimbarea maparii.
- [ ] **P2.5 Reguli ArchUnit**: orice `@Entity` din schemele de tenant extinde `TenantScopedEntity`; niciun serviciu nu depinde de pachetele altui serviciu; fara relatii JPA catre entitati din alt serviciu. REQ: ARCH-01, DATA-10.
  - Teste: regulile ArchUnit ruleaza in fiecare serviciu.

### Faza 3 - Infrastructura de evenimente si jurnal
- [ ] **P3.1 Contractul evenimentelor**: plic `eventId`, `eventType`, `version`, `tenantId`, `occurredAt`, `payload` + evenimente tipizate in `shared-lib`; se elimina clasele de eveniment nefolosite si payload-urile `Map`. REQ: EVT-06, EVT-02.
  - Teste: serializare round-trip; `tenantId` obligatoriu pentru evenimentele de tenant; regula ArchUnit/reflectie: payload-urile Kafka nu au campuri de tip email/nume/telefon.
- [ ] **P3.2 Outbox + relay**: tabela `outbox` per serviciu; relay care publica evenimentele in Kafka si comenzile in RabbitMQ; relay-ul foloseste rolul dedicat (doar `outbox`, exceptat de RLS). REQ: EVT-04, EVT-01.
  - Teste: tranzactie cu rollback -> niciun mesaj publicat; commit -> mesaj publicat exact in broker-ul corect.
- [ ] **P3.3 Infrastructura de consum**: tabela `processed_events` pentru idempotenta; setarea contextului de tenant din plic; 5 reincercari cu backoff -> DLQ (Kafka DLT / RabbitMQ DLX), cu logare. REQ: EVT-05, EVT-06, EVT-07.
  - Teste: mesaj livrat de doua ori -> procesat o singura data; mesaj care esueaza mereu -> ajunge in DLQ dupa 5 incercari.
- [ ] **P3.4 Configurarea topic-urilor Kafka**: cheie de partitionare `tenantId` / `userId`; retentie configurabila (6 luni productie). REQ: EVT-10, EVT-03.
  - Teste: cheia mesajului este `tenantId` (sau `userId` pentru identitate); retentia topic-ului corespunde configurarii.
- [ ] **P3.5 Nucleul jurnalului de activitate** (in notification-service, schema `notif`): tabela generica partitionata lunar, index `tenant_id`, job de mentenanta a partitiilor si de retentie (`DROP PARTITION` in productie, job de stergere in test). REQ: MON-03, MON-06, MON-07.
  - Teste: partitiile lunii curente si urmatoare exista; partitiile mai vechi decat retentia sunt eliminate (cu `MutableClock`); profilul de test sterge randurile mai vechi de N minute.

### Faza 4 - Organizatii si membership-uri
- [ ] **P4.1 Tenanti si membership-uri**: creare organizatie (creatorul devine ADMIN); membership cu rol + flag doar-citire; API intern pentru auth (tenanturile si rolurile unui user); se elimina crearea automata a organizatiei la inregistrare. REQ: AUTH-03, ROLE-06, EVT-08.
  - Teste: crearea face creatorul ADMIN; userul doar-citire primeste 403 la scriere; niciun tenant creat la evenimentul de inregistrare.
  - Extinde `TenantFixtures` din `test-support` ca sa persiste tenantul si membership-urile.
- [ ] **P4.2 Matricea de permisiuni in org**: ADMIN/MANAGER/USER pe baza claim-urilor din token; managementul rolurilor doar ADMIN; oricati MANAGER (inclusiv zero). REQ: ROLE-01, ROLE-05, MGR-03, MGR-05.
  - Teste: test parametrizat `(rol, endpoint, status)` pentru toate endpoint-urile org.
- [ ] **P4.3 Regulile pentru ultimul ADMIN**: nu se poate elimina/retrograda ultimul ADMIN; o retrogradare care lasa un singur ADMIN cere acceptul celui vizat; fara raspuns in termen -> status `ESCALATED` + eveniment catre suport. REQ: ROLE-03, ROLE-04.
  - Teste: blocarea retrogradarii ultimului ADMIN; accept -> aplicat; refuz -> anulat; timeout (cu `MutableClock`) -> escaladat.

### Faza 5 - Identitate si autentificare
- [ ] **P5.1 Inregistrare**: identitate globala in DB globala; politica de parola (min 12, lista de parole compromise, fara reguli de complexitate); eveniment de inregistrare fara PII; fara tenant creat. REQ: PWD-01, PWD-06, EVT-08.
  - Teste: parola scurta/compromisa respinsa; parola valida acceptata; evenimentul nu contine email.
- [ ] **P5.2 Fluxul de login**: 0 / 1 / N tenanti; `loginSessionId` opac, temporar, single-use; apel sincron catre org pentru membership-uri, fara cache. REQ: AUTH-01, AUTH-02, AUTH-03, EVT-09, SCALE-01.
  - Teste: cele trei cazuri; `loginSessionId` refolosit sau expirat -> respins; un user eliminat din tenant nu mai primeste token pentru el imediat dupa eliminare.
- [ ] **P5.3 Token per tenant**: o singura implementare JWT in `shared-lib` (se elimina copiile din gateway/auth/org); claims exact cele cerute; access 15 min, refresh cu rotatie; gateway-ul valideaza si transmite contextul. REQ: AUTH-04, AUTH-05, PWD-04.
  - Teste: tokenul contine doar claims-urile cerute; tokenul tenantului A e respins pe resursele tenantului B; access token expirat -> 401 (cu `MutableClock`).
  - Reamintire: secretele de dev sunt temporar publice in `.env` (radacina); decide daca raman asa sau se muta in afara repo-ului.
  - Numele claims-urilor trebuie sa coincida cu `JwtTestTokens` din `test-support` (sau se actualizeaza ambele).
- [ ] **P5.4 Resetarea parolei**: token 1h single-use, o cerere noua le invalideaza pe cele vechi; raspuns identic pentru email existent/inexistent si aceeasi munca de hashing pe ambele ramuri; revocarea tuturor refresh token-urilor; comanda de email de confirmare; fara intrare in jurnalul de tenant; fara endpoint de reset initiat de admin; rate limit 3/ora per email, 10/ora per IP. REQ: PWD-02, PWD-03, PWD-04, PWD-05, PWD-07, PWD-08, PWD-09.
  - Teste: fiecare regula de mai sus; pentru anti-enumerare se verifica raspunsul identic si ca ambele ramuri executa hashing-ul (nu masurare de timp).

### Faza 6 - Billing
- [ ] **P6.1 Catalogul de planuri** in DB globala: fara stergere, doar dezactivare; preturi in EUR. REQ: DATA-12, BILL-07.
  - Teste: stergerea unui plan e respinsa; un plan dezactivat nu mai poate fi ales, dar subscriptiile existente raman valide; moneda e mereu EUR.
- [ ] **P6.2 Payment gateway**: interfata `PaymentGateway` + `SimulatedPaymentGateway` cu mod de esec configurabil; fiecare incercare persistata. REQ: BILL-01, BILL-05.
  - Teste: succes si esec sunt ambele salvate; serviciul depinde doar de interfata (ArchUnit).
- [ ] **P6.3 Ciclul de viata al subscriptiei**: subscribe cu trial 14 zile; cancel; schimbare de plan = cancel + resubscribe; trial expirat -> ACTIVE cu plata simulata (scheduler cu `Clock`); eveniment de schimbare a subscriptiei cu `maxUsers`. REQ: BILL-02, BILL-06.
  - Teste: tranzitiile de stare; conversia automata dupa 14 zile; anularea in trial opreste conversia.
- [ ] **P6.4 PAST_DUE si perioada de gratie**: plata esuata -> PAST_DUE; tenantul e doar-citire 7 zile, apoi SUSPENDED; starea tenantului e propagata prin evenimente si verificata la scriere in toate serviciile. REQ: BILL-04.
  - Teste: scrierea blocata in gratie, citirea permisa; dupa 7 zile orice acces e blocat; plata reusita revine la ACTIVE.
- [ ] **P6.5 Permisiuni billing**: ADMIN tot; MANAGER doar vizualizare; USER fara acces. REQ: ROLE-01, MGR-02.
  - Teste: test parametrizat `(rol, endpoint, status)`.

### Faza 7 - Invitatii si offboarding
- [ ] **P7.1 Invitatii**: ADMIN invita cu orice rol, MANAGER doar USER, USER deloc; expirare configurabila per tenant; revocare de catre ADMIN; limita `maxUsers` din proiectia locala; email trimis prin comanda RabbitMQ (PII doar in comanda); acceptarea creeaza membership-ul. REQ: ROLE-02, ROLE-05, MGR-01, INV-01, INV-02, BILL-03, EVT-02, EVT-09.
  - Teste: matricea rol-invitator x rol-invitat; invitatie expirata/revocata respinsa; invitare peste `maxUsers` respinsa; evenimentul Kafka nu contine email.
- [ ] **P7.2 Offboarding**: anonimizarea numelui/email-ului in tenant, stergerea PII tenant-scoped, stergerea notificarilor userului din tenant; identitatea globala neatinsa; stergerea identitatii globale la cererea userului sau cand nu mai are tenanti; offboarding-ul publica un eveniment (doar ID-uri) pe care modulele adaugate ulterior (P9.6, P9.7) il consuma ca sa-si stearga datele userului. REQ: DATA-01, NOTIF-05.
  - Teste: dupa offboarding datele din tenant sunt anonimizate/sterse, facturile raman valide, userul se poate loga in celelalte tenanturi.

### Faza 8 - Personalizare
- [ ] **P8.1 Tema**: `tenant_settings.theme_json`; catalog de palete in DB globala; tema implicita la crearea tenantului; endpoint dedicat de citire; actualizare doar ADMIN, doar cu `paletteId` valid. REQ: PERS-01, PERS-02, PERS-04, PERS-05, PERS-06.
  - Teste: tenant nou are tema implicita; `paletteId` inexistent sau valori CSS brute respinse; non-ADMIN primeste 403.
- [ ] **P8.2 Logo**: interfata `ObjectStorage` + implementare locala; doar PNG/JPG, verificat dupa continut (magic bytes), limita de marime si dimensiuni; cheie de stocare per tenant. REQ: PERS-01, PERS-03.
  - Teste: SVG sau fisier cu extensie falsa respins; peste limita respins; PNG/JPG valid acceptat; serviciul depinde doar de interfata.
- [ ] **P8.3 Feature flags per tenant** in `tenant_settings`. REQ: PERS-07, ARCH-02.
  - Teste: flag activ pentru un tenant nu afecteaza alt tenant; valoarea implicita e dezactivat.

### Faza 9 - Jurnal de activitate, notificari si module de business
- [ ] **P9.1 Scrierea in jurnal**: consumatori de evenimente pentru lista de actiuni sensibile (login, roluri, invitatii, retrogradarea ultimului ADMIN, subscriptie, tema); evenimentele de parola sunt ignorate. REQ: MON-01, PWD-08.
  - Teste: fiecare tip de eveniment produce exact o intrare; evenimentele de parola nu produc intrari; operatiile CRUD obisnuite nu produc intrari.
- [ ] **P9.2 Citirea jurnalului prin GraphQL** (`spring-boot-starter-graphql` in notification-service, rutat prin gateway): query doar-citire, paginat, filtrabil (data, tip, actor); fara mutatii; tenantul din token, nu din argumente; doar ADMIN si MANAGER; limite de adancime/complexitate; introspectie dezactivata in profilul de productie. REQ: MON-02, MON-04, MON-08, MGR-04.
  - Teste: filtre si paginare; USER respins (eroare `FORBIDDEN`); tenantul A nu vede intrarile lui B; o mutatie sau un argument `tenantId` sunt respinse de schema; query peste limita de adancime/complexitate respins; introspectia raspunde doar in afara profilului de productie.
- [ ] **P9.3 Export CSV**: doar ADMIN; decizie sync/async pe baza timpului mediu masurat al operatiei; async ruleaza ca job RabbitMQ si genereaza notificarea "export gata". REQ: MON-05, SCALE-03.
  - Teste: export mic sincron; export peste prag asincron + notificare; MANAGER nu poate exporta.
- [ ] **P9.4 Trimiterea email-urilor**: consumator de comenzi RabbitMQ; 3 reincercari cu backoff -> `FAILED`; doar metadate; `EMAIL_SENT`/`EMAIL_FAILED` in jurnalul tenantului, email-urile de identitate nelogate; fara setari de dezactivare. REQ: MON-01, NOTIF-06, NOTIF-07, NOTIF-08, NOTIF-09.
  - Teste: furnizor care esueaza de 3 ori -> `FAILED`; email de invitatie -> intrare in jurnal; email de reset -> nicio intrare; nu exista endpoint de preferinte.
- [ ] **P9.5 Notificari in-app**: generate din lista de evenimente v1; per tenant; listare, numar necitite, marcare ca citit; retentie 90 zile. REQ: NOTIF-01, NOTIF-02, NOTIF-03, NOTIF-04, NOTIF-05.
  - Teste: fiecare eveniment din lista genereaza notificarea catre destinatarul corect; notificarile altui tenant nu sunt vizibile; stergerea dupa 90 zile (cu `MutableClock`).
- [ ] **P9.6 Modulul cereri de concediu** (org-service, schema `org`): activarea modulelor per tenant din `tenant_settings` (doar ADMIN, implicit dezactivat) + fus orar al tenantului; cereri, calculul zilelor lucratoare, sold si suprascrieri, zile libere, tranzitii de stare, aprobare cu regula anti-auto-aprobare, calendarul echipei, evenimente Kafka doar cu ID-uri, notificari si intrari in jurnal, stergere la offboarding. REQ: MOD-01, LEAVE-01..08, MON-01, NOTIF-03.
  - Teste: modul dezactivat -> inaccesibil, activarea de non-ADMIN -> 403, activarea/dezactivarea -> intrare in jurnal, dezactivare + reactivare -> datele pastrate; fusul orar se schimba doar de ADMIN; membru doar-citire nu poate cere si nu poate aproba; calcul zile peste weekend si zile libere; depasire sold si suprapunere respinse; pending rezerva din sold; anulare dupa inceput respinsa (cu `MutableClock`); matrice `(rol, actiune, status)`; propria cerere nu se poate aproba; solicitantul e singurul aprobator (inclusiv cand ceilalti MANAGER/ADMIN sunt doar-citire) -> auto-aprobare + intrare in jurnal; anularea de catre aprobator -> notificare catre solicitant; calendarul echipei nu expune tipul/motivul; scrierea blocata in perioada de gratie; izolare A/B (`RlsAssertions`); evenimentul Kafka nu contine motivul sau nume; evenimentul de offboarding sterge cererile.
- [ ] **P9.7 Modulul lunch roulette** (org-service): configurare, runde saptamanale cu inscriere per runda, job la ora limita in fusul orar al tenantului (cu `Clock`), grupare aleatoare cu `Random` injectabil, evitarea perechilor recente, notificari, vizibilitate, retentie 90 zile, stergere la offboarding. Notificarea refera grupul; numele membrilor se citesc din org la afisare, nu se copiaza in evenimente. REQ: MOD-01, LUNCH-01..05, NOTIF-03.
  - Teste: inscriere/retragere inainte si dupa ora limita; marimi 2-4 si distribuirea celor ramasi (niciun grup sub 2); sub 2 participanti -> anulare + notificare; perechile din rundele recente evitate cand e posibil (seed fix); USER nu poate configura/anula; doar-citire nu se poate inscrie si nu poate configura; ora limita respecta fusul orar; in perioada de gratie/suspendare runda se anuleaza fara grupuri; stergere dupa 90 zile; evenimentul de offboarding sterge participarile; nicio intrare in jurnal.

### Faza 10 - Scalabilitate si gateway
- [ ] **P10.1 Cache Redis** pentru tema, status subscriptie, roluri membership, cu invalidare la evenimentele de schimbare. REQ: SCALE-01.
  - Teste: a doua citire vine din cache; dupa evenimentul de schimbare se citeste valoarea noua.
- [ ] **P10.2 Rate limiting** la gateway: limita per user + limita agregata per tenant proportionala cu userii activi, stocate in Redis. REQ: SCALE-02.
  - Teste: depasirea limitei per user -> 429; limita tenantului creste cu numarul de useri activi.
- [ ] **P10.3 Compatibilitatea API**: snapshot OpenAPI per serviciu + snapshot al schemei GraphQL a jurnalului, comparate in CI; o schimbare REST incompatibila fara versiune noua de path, sau eliminarea/schimbarea unui camp GraphQL (in loc de `@deprecated`), esueaza build-ul. REQ: ARCH-03, MON-08.
  - Teste: eliminarea unui camp din raspunsul REST sau din schema GraphQL face verificarea sa esueze.

### Faza 11 - Frontend Angular
- [ ] **P11.1 Login**: selectia tenantului, cazul 0 tenanti cu "creeaza organizatie", un token per tenant pastrat de client. REQ: AUTH-01, AUTH-02, AUTH-03, AUTH-04.
- [ ] **P11.2 Tema si logo**: aplicarea temei la incarcare; editor de tema pentru ADMIN cu selectie din paleta. REQ: PERS-01, PERS-06.
- [ ] **P11.3 Notificari**: incarcare la deschidere, numar necitite prin polling la 60s. REQ: NOTIF-04.
- [ ] **P11.4 UI pe roluri**: ADMIN/MANAGER/USER si flag doar-citire; billing vizibil doar-citire pentru MANAGER; pagina de jurnal pentru ADMIN/MANAGER (client GraphQL). REQ: ROLE-05, MGR-02, MGR-04, ROLE-06.
- [ ] **P11.5 UI module de business**: activarea modulelor (ADMIN); concediu: cerere, sold, calendarul echipei, ecran de aprobare (MANAGER/ADMIN), politica de concediu (ADMIN); lunch roulette: inscriere/retragere, grupul meu, configurare si toate grupurile (MANAGER/ADMIN). REQ: MOD-01, LEAVE-06, LUNCH-04.
  - Teste (toata faza): teste unit pe servicii/guard-uri + Playwright pe fluxurile de login, tema, notificari si cerere de concediu.

### Faza 12 - E2E si finalizare
- [ ] **P12.1 Suita E2E** pe Docker Compose: izolare intre tenanti, invitatie -> accept -> login, perioada de gratie billing, resetare parola, cerere de concediu -> aprobare -> notificare, runda de lunch roulette.
- [ ] **P12.2 Trasabilitate 100%**: toate REQ-urile din catalog au teste; documentele vechi care descriu alt comportament (ex. rezumate "production ready") sunt actualizate sau marcate ca depasite.
- [ ] **P12.3 Verificari non-cod**: configuratia de deployment foloseste doar regiunea EU (REQ-DATA-13); elementele din afara scopului nu au fost introduse.

---

## 4. Parametri configurabili (valori implicite, nu cerinte)

Valorile de mai jos nu sunt fixate in REQUIREMENTS.md; se folosesc ca implicite si se pot schimba doar prin configurare.

| Parametru | Implicit | Folosit in |
|---|---|---|
| Durata `loginSessionId` | 5 minute | P5.2 |
| Termen de raspuns la retrogradarea ultimului ADMIN, inainte de escaladare | 7 zile | P4.3 |
| Expirarea implicita a invitatiilor (suprascrisa per tenant) | 7 zile | P7.1 |
| Marime maxima logo / dimensiuni maxime | 2 MB / 1024x1024 px | P8.2 |
| Limita per user (request-uri/secunda) | 10 req/s | P10.2 |
| Limita agregata per tenant per user activ | 5 req/s | P10.2 |
| Pragul de timp pentru trecerea la async | 5 secunde | P9.3 |
| Retentia jurnalului / topic-urilor Kafka in test | 10 minute | P3.4, P3.5 |
| Sold anual implicit `ODIHNA` (suprascris de ADMIN per tenant/user) | 21 zile lucratoare | P9.6 |
| Lunch roulette: zi / ora limita / marime grup (suprascrise de MANAGER/ADMIN) | joi / 11:00 / 2 | P9.7 |
| Lunch roulette: runde recente luate in calcul la evitarea perechilor | 4 | P9.7 |

---

## 5. Jurnal de progres

| Pas | Data | Note |
|---|---|---|
| - | - | Plan creat; niciun pas inceput |
| - | 2026-09-30 | Curatare workspace: eliminate documentele depasite, prototipurile `spring_base` si `svelte_base`, folderele goale din `api-gateway`, output-urile de build, cache-ul Angular si paginile Angular nefolosite |
| P0.1 | 2026-09-30 | `pom.xml` parinte `msaas-parent` (Boot 3.2.5, Java 21, Spring Cloud 2023.0.1, versiuni jjwt/mapstruct/springdoc/pdfbox/archunit, surefire + failsafe) + Maven Wrapper 3.9.15; modul `test-support` schelet; toate modulele mostenesc parintele. Reparate erori preexistente de build: `flyway-database-postgresql` inexistent pentru Flyway 9, `CorrelationIdFilter` (gateway) si shadowing `log` in `NotificationService`. Sterse testele legacy necompilabile/stricate (auth, org, billing, notification). `mvnw verify` din radacina: BUILD SUCCESS (0 teste momentan) |
| - | 2026-09-30 | Cerinta noua: citirea jurnalului prin GraphQL doar-citire (REQ-MON-08); actualizate P9.2, P10.3, P11.4 si REQ-ARCH-03 (corectat si maparea P10.4 -> P10.3). Secretele de dev mutate temporar in `.env`; build-ul Angular reparat |
| P0.2 | 2026-09-30 | `test-support`: `SharedContainers` (Postgres 15-alpine, Kafka cp 7.6.1 KRaft, RabbitMQ 3.13, Redis 7; pornite lazy, o data per JVM) + interfete `*TestContainer` cu `@ServiceConnection`; `MutableClock`, `TenantFixtures` (in memorie), `JwtTestTokens`, `RlsAssertions` (JDBC, rol non-superuser), `EventAssertions` (Kafka/RabbitMQ cu Awaitility). Teste: 6 unit + 9 integrare (`SharedContainersIT` = testul de fum, `RlsAssertionsIT`). `mvnw verify` din radacina: BUILD SUCCESS |
| P0.3 | 2026-10-01 | `scripts/check-traceability.ps1`: citeste catalogul (90 REQ) si pasii bifati, cauta `@Tag("REQ-...")` in `src/test/**/*.java` si `describe('REQ-...')` in `*.spec.ts` (fara `target`/`node_modules`/`dist`); esueaza la REQ datorat fara test sau la tag necunoscut. `scripts/check-traceability.tests.ps1`: 5 cazuri pe un catalog de exemplu, toate trec. Pe planul real: OK (0 REQ datorate) |
| P0.4 | 2026-10-01 | `.github/workflows/ci.yml`: backend `./mvnw -B -ntp verify` (unit + integrare cu Testcontainers, rapoarte la esec), traceability (self-test + verificare), frontend (`npm ci`, build, `test:ci`). Angular: pachete aliniate la 19.2 (CLI era 21), Karma/Jasmine + `tsconfig.spec.json` + primul spec (`AuthGuard`, 3 teste); `package-lock.json` regenerat. `mvnw` marcat executabil. `CiPipelineTest` (`REQ-ARCH-04`) pazeste configurarea CI. Verificat local: test rosu -> `mvnw verify` exit 1; pas bifat cu REQ fara test -> trasabilitate exit 1. Rularea efectiva pe GitHub necesita push (neefectuat) |
| - | 2026-10-01 | Cerinte noi: module de business (REQ-MOD-01), cereri de concediu (REQ-LEAVE-01..08), lunch roulette (REQ-LUNCH-01..05). Decizii: auto-aprobare jurnalizata cand solicitantul e singurul aprobator; doar-citire blocheaza si actiunile proprii; offboarding sterge cererile; tipuri `ODIHNA` + `FARA_PLATA`; inscriere la lunch per runda. Pasi noi P9.6, P9.7, P11.5; actualizate REQ-MON-01, REQ-NOTIF-03, P12.1, sectiunea 4 |
| - | 2026-10-01 | Verificare de conflicte: aprobatorii de concediu exclud membrii doar-citire (altfel cererile se puteau bloca); dezactivarea unui modul pastreaza datele; activarea modulelor e setare ADMIN, distincta de feature flag-urile de platforma; fusul orar il seteaza ADMIN; in gratie/suspendare runda de lunch se anuleaza; notificarea de decizie include anularea de catre aprobator. Plan: P11.5 adaugat in catalog la MOD-01/LEAVE-06/LUNCH-04, teste noi in P9.6/P9.7, P7.2 publica eveniment de offboarding pentru module. Instructiunile agentului restructurate (pasi la fiecare prompt, definitia "terminat", procedura pentru cerinte, mediu local); sectiunea 0 aliniata |
| P1.1 | 2026-10-02 | Eliminat `GET /notifications/emails-by/{email}` si `EmailLogRepository.findByToEmail`; `EmailLog` fara `body`, `NotificationService` nu mai construieste/salveaza body-ul (contine tokenuri); migrare `V3__email_logs_metadata_only.sql` (drop `body` + indexul pe `to_email`). notification-service depinde de `test-support`. `EmailLogPrivacyIT`: endpoint 404 + nicio ruta `emails`, tabela fara `body`, tokenurile de reset/invitatie nu apar in randurile salvate. `mvnw -pl notification-service -am verify`: BUILD SUCCESS |
| P1.2 | 2026-10-02 | Eliminat `POST /notifications/uploads` (scria fisiere fara tenant/rol/limite in `uploads/`) si metoda nefolosita `BillingService.uploadAttachment` din Angular; directorul `uploads/` nu exista pe disc/in git. `GenericUploadRemovedIT` (`REQ-UPL-01`): multipart pe ruta veche -> 404 si nimic scris pe disc; niciun endpoint din notification-service nu accepta `MultipartFile`. Backend + build/teste Angular verzi. P1.4 extins cu eliminarea flag-ului `FILE_UPLOADS` |
| - | 2026-10-02 | Proces: verificare fata de plan inainte de bifarea unui pas (descriere / teste / fara extra), in sectiunea 0 si in instructiunile agentului. Aplicata retroactiv pe P1.1 si P1.2: OK |
