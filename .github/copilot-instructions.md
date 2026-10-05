# Instructiuni pentru agent - proiectul MSaaS

Workspace-ul urmeaza un plan de implementare obligatoriu. Surse de adevar:
- CE se construieste: [REQUIREMENTS.md](../REQUIREMENTS.md)
- CUM si IN CE ORDINE: [IMPLEMENTATION_PLAN.md](../IMPLEMENTATION_PLAN.md) (regulile din sectiunea 0, catalogul REQ din sectiunea 1)

## La fiecare prompt
1. Citeste planul si identifica pasul curent: primul pas nebifat (`[ ]`), in ordinea fazelor.
2. Clasifica cererea:
   - face parte din pasul curent -> lucreaza pe ea;
   - tine de alt pas sau e o deviere -> spune carui pas ii corespunde si cere confirmare inainte de a modifica ceva;
   - e o schimbare de cerinte -> urmeaza procedura de mai jos;
   - e doar o intrebare -> raspunde fara sa modifici fisiere.
3. Inainte de a raspunde, fa verificarea de conformitate (mai jos) pentru tot ce ai modificat.

## Definitia lui "terminat" pentru un pas
- Codul acopera toate REQ-urile pasului; fiecare REQ are cel putin un test marcat `@Tag("REQ-...")` (frontend: `describe('REQ-...')`).
- `mvnw -pl <modul> -am verify` trece pentru modulele atinse (fara teste sarite sau `@Disabled`); pentru frontend `npm run build` + `npm run test:ci`.
- `node scripts/verify/check-traceability.mjs` trece.
- Verificarea fata de plan (mai jos) e completa.
- Pasul e bifat, are o linie in Jurnalul de progres si exista un commit local `P<faza>.<pas>: <rezumat>`.

## Schimbari de cerinte (doar la cerere explicita)
1. Clarifica deciziile deschise cu utilizatorul (optiuni + recomandare) inainte de a scrie.
2. Modifica REQUIREMENTS.md; apoi actualizeaza sectiunile existente pe care le atinge noua cerinta (roluri, jurnal, notificari, offboarding, evenimente etc.).
3. Verifica intregul REQUIREMENTS.md pentru contradictii si rezolva-le sau raporteaza-le.
4. Actualizeaza planul: catalogul (ID nou, rezumat, toti pasii care il implementeaza, inclusiv frontend), pasii si testele lor, sectiunea 4 pentru valorile implicite, lista "in afara scopului", linie in Jurnalul de progres.
5. Ruleaza scriptul de trasabilitate si fa commit `P<pas curent> (cerinte): <rezumat>`.

## Verificare de conformitate
- La fiecare prompt care modifica cod sau cerinte: compara ce ai modificat cu textul din REQUIREMENTS.md pentru REQ-urile pasului curent si pentru orice alt REQ afectat (vezi catalogul).
- Orice nepotrivire (cod existent sau nou care contrazice o cerinta, cerinte care se contrazic) se raporteaza explicit; nu se ignora si nu se repara in afara pasului fara confirmare.
- In raspunsul final: REQ-urile verificate si nepotrivirile gasite (sau "nicio nepotrivire").

## Verificare fata de plan (inainte de a bifa un pas)
- Descriere: fiecare element din textul pasului e facut (ex. "se elimina endpoint-ul **si** directorul"); ce nu se poate face se raporteaza, nu se sare.
- Teste: fiecare punct din lista `Teste:` a pasului are un test automat corespunzator.
- Fara extra: nu s-a modificat nimic din afara pasului; daca a fost nevoie, e raportat ca deviere si confirmat.
- In raspunsul final: `Pn.m: descriere OK / teste OK / fara extra OK` sau ce lipseste.

## Reguli de cod si teste
- Foloseste `test-support`: containere singleton (`*TestContainer`), `MutableClock`, `TenantFixtures`, `JwtTestTokens`, `RlsAssertions`, `EventAssertions`.
- Injecteaza `Clock`; nu apela direct `now()`.
- Testele de integrare se numesc `*IT` (failsafe); restul ruleaza in surefire.
- Teste de integrare in servicii: implementeaza doar interfetele `*TestContainer` necesare, opreste listenerii brokerilor nefolositi (`spring.rabbitmq.listener.simple.auto-startup=false`, `spring.kafka.listener.auto-startup=false`), pastreaza aceeasi configuratie `@SpringBootTest` in clasele unui serviciu (context refolosit).
- Ruleaza un modul mereu cu `-am` (`test-support` nu e publicat intr-un repository).
- Parametrii din sectiunea 4 a planului sunt valori implicite configurabile, nu cerinte.

## Commit-uri
- Mesajul incepe cu ID-ul pasului: `P0.2: modulul test-support`.
- Modificari din afara pasului curent: pasul curent + sufix, ex. `P1.1 (cerinte): ...`, `P1.1 (deviere): ...`, `P1.1 (proces): ...`.
- Fara push, force, reset sau stergeri ireversibile fara confirmare.

## Mediu local
- Pregatirea mediului se face cu scripturi, nu cu pasi manuali. `scripts/` e grupat dupa scop si scris in Node (`.mjs`), ca sa ruleze pe orice masina; secretele se citesc din `.env`, nu se scriu in scripturi.
  - `scripts/env/install-prerequisites.ps1` (Windows) / `.sh` (Linux, macOS) - o singura data, pe o masina noua (Docker, JDK 21, Maven, Node). Singurele scripturi dependente de platforma, pentru ca instaleaza chiar Node.
  - `node scripts/env/ensure-dev-env.mjs` - **inainte de orice test de integrare**: `JAVA_HOME` (JDK 21), porneste Docker si asteapta, descarca imaginile cu reincercari, `CHROME_BIN`, `npm ci`, verifica cheile din `.env`. `--check` doar raporteaza.
  - `node scripts/infra/local-stack.mjs --up|--down` - stiva din docker-compose pentru verificari manuale, in proiect separat si fara porturi publicate.
  - `node scripts/verify/check-traceability.mjs` (+ `--test ...test.mjs`) - verificari care esueaza build-ul/CI.
- **Comenzile manuale repetate devin scripturi**: a doua rulare a aceleiasi comenzi, sau orice comanda care a avut nevoie de reincercare/corectura, se muta in scriptul potrivit (`env` / `infra` / `verify`), se documenteaza in sectiunea 2 a planului si, daca poate devia fata de cod, primeste un test (model: `DevEnvironmentScriptTest`).
- Daca lipseste ceva din mediu, se repara `ensure-dev-env.mjs` si se ruleaza, nu se fac comenzi manuale ad-hoc.
- Build-ul cere JDK 21 (maven-enforcer il refuza pe altul); JDK-urile 25/26 din PATH nu sunt compatibile cu Lombok/Boot 3.2.
- Testele de integrare au nevoie de Docker Desktop pornit; `mvnw verify -DskipITs`, build-ul Angular si trasabilitatea nu.
- Pe masina locala ruleaza un Postgres nativ pe portul 5432: `docker compose up` cu portul `5432` publicat intra in conflict; foloseste `scripts/infra/local-stack.mjs`, care nu publica porturi.
- Raspunsurile catre utilizator sunt in romana.
- Foloseste skill-ul `/caveman` (intensitate `full`) in timpul implementarii unui pas; proza normala pentru decizii de cerinte, securitate si operatii ireversibile. Codul, testele si documentele nu se comprima.
