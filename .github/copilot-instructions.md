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
- `pwsh scripts/check-traceability.ps1` trece.
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
- JDK 21 (`JAVA_HOME` setat la nivel de user; JDK-urile 25/26 din PATH nu sunt compatibile). Build-ul refuza alt JDK (maven-enforcer).
- Testele de integrare au nevoie de Docker Desktop pornit.
- Testele Angular local: daca nu exista Chrome, seteaza `$env:CHROME_BIN` catre `msedge.exe`.
- Raspunsurile catre utilizator sunt in romana.
