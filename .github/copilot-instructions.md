# Instructiuni pentru agent - proiectul MSaaS

Acest workspace urmeaza un plan de implementare obligatoriu. La fiecare prompt:

1. Citeste [IMPLEMENTATION_PLAN.md](../IMPLEMENTATION_PLAN.md) inainte de orice modificare de cod si respecta regulile din sectiunea 0.
2. Lucreaza doar pe pasul curent (primul pas nebifat, in ordinea fazelor). Daca cererea utilizatorului iese din pasul curent, spune carui pas ii corespunde sau ca e o deviere si cere confirmare.
3. Cerintele sunt in [REQUIREMENTS.md](../REQUIREMENTS.md). Fiecare REQ implementat are cel putin un test marcat `@Tag("REQ-...")`; ID-urile sunt in catalogul din sectiunea 1 a planului.
4. Un pas e terminat doar cand testele modulelor atinse trec (`mvn -pl <modul> -am verify`), scriptul de trasabilitate trece, pasul e bifat si are o linie in Jurnalul de progres.
5. Foloseste utilitarele din modulul `test-support` (containere singleton, `MutableClock`, fixture-uri) si injecteaza `Clock` in loc de a apela direct `now()`.
6. Nu modifica REQUIREMENTS.md fara cerere explicita; parametrii din sectiunea 4 a planului sunt configurabili, nu cerinte.
