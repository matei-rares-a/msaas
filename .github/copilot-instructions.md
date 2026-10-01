# Instructiuni pentru agent - proiectul MSaaS

Acest workspace urmeaza un plan de implementare obligatoriu. La fiecare prompt:

1. Citeste [IMPLEMENTATION_PLAN.md](../IMPLEMENTATION_PLAN.md) inainte de orice modificare de cod si respecta regulile din sectiunea 0.
2. Lucreaza doar pe pasul curent (primul pas nebifat, in ordinea fazelor). Daca cererea utilizatorului iese din pasul curent, spune carui pas ii corespunde sau ca e o deviere si cere confirmare.
3. Cerintele sunt in [REQUIREMENTS.md](../REQUIREMENTS.md). Fiecare REQ implementat are cel putin un test marcat `@Tag("REQ-...")`; ID-urile sunt in catalogul din sectiunea 1 a planului.
4. Un pas e terminat doar cand testele modulelor atinse trec (`mvn -pl <modul> -am verify`), scriptul de trasabilitate trece, pasul e bifat si are o linie in Jurnalul de progres.
5. Foloseste utilitarele din modulul `test-support` (containere singleton, `MutableClock`, fixture-uri) si injecteaza `Clock` in loc de a apela direct `now()`.
6. Nu modifica REQUIREMENTS.md fara cerere explicita; parametrii din sectiunea 4 a planului sunt configurabili, nu cerinte.
7. Verificare de conformitate la fiecare prompt care modifica cod: compara codul atins cu textul din REQUIREMENTS.md pentru REQ-urile pasului curent si pentru orice alt REQ afectat de fisierele modificate (catalogul din sectiunea 1 a planului). Orice nepotrivire (cod existent sau nou care contrazice o cerinta) se raporteaza explicit, nu se ignora si nu se "repara" in afara pasului fara confirmare. In raspunsul final mentioneaza pe scurt REQ-urile verificate si nepotrivirile gasite (sau ca nu au fost gasite).
8. Mesajul fiecarui commit incepe cu ID-ul pasului: `P<faza>.<pas>: <rezumat>` (ex. `P0.2: modulul test-support`). Modificarile din afara pasului curent folosesc pasul curent cu un sufix: `P0.3 (cerinte): ...`, `P0.3 (deviere): ...`. Fara push fara confirmare.
