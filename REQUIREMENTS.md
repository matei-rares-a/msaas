Plan de cerinte pentru un multi-tenant application

Description:
Acest document detaliaza cerintele pentru dezvoltarea unei aplicatii multi-tenant, unde fiecare client sau organizatie utilizeaza aceeasi instanta a aplicatiei, inclusiv functionalitatile necesare (nice-to-have), constrangerile si specificatiile tehnice.


Functionalitati necesare:


Un sistem de autentificare si autorizare pentru fiecare tenant, astfel incat utilizatorii sa poata accesa doar datele si functionalitatile specifice organizatiei lor.
Un user poate face parte din mai multe tenants cu setari specifice pentru fiecare tenant. La autentificare (email+parola): daca userul are un singur tenant, raspunsul contine direct tokenul per-tenant. Daca are mai multe tenanturi, raspunsul contine lista de tenanturi plus un loginSessionId opac, temporar si de unica folosinta; un al doilea apel (loginSessionId + tenantId ales) emite tokenul per-tenant real. Daca nu are niciun tenant, raspunsul contine o lista goala si userul poate crea o organizatie noua (devenind ADMIN al ei) sau poate astepta o invitatie.
Token-ul de autentificare trebuie sa fie specific pentru fiecare tenant, astfel incat accesul sa fie restrictionat la datele si functionalitatile corespunzatoare. Se emite cate un token separat per tenant, generat doar in momentul in care userul selecteaza explicit acel tenant; clientul retine si trimite tokenul corespunzator tenantului activ la fiecare request (nu exista un token unic multi-tenant cu tenant activ comutabil).
Userii sunt de mai multe tipuri:
- ADMIN:
  - Are toate privilegiile pentru gestionarea organizatiei/billings si utilizatorilor.
  - Poate gestiona privilegiile unui utilizator.
  - Poate invita utilizatori noi in organizatie cu orice rol (ADMIN, MANAGER sau USER); este singurul rol care poate invita alti ADMIN sau MANAGER (MANAGER poate invita doar cu rolul USER, vezi sectiunea MANAGER).
  - In organizatie trebuie sa existe cel putin un ADMIN.
  - Inainte de a ramane un singur Admin, celui care i se retrag drepturile trebuie sa accepte schimbarea. Daca nu raspunde intr-un termen prestabilit, cererea se escaladeaza catre suportul tehnic pentru aprobare manuala.
- USER:
  - Are acces la functionalitatile de baza ale aplicatiei, dar nu poate gestiona alti utilizatori sau setarile organizatiei.
  - Nu poate invita alti utilizatori.
  - Nivelul de acces la date (scriere/doar-citire) este un flag separat pe membership, configurabil independent de rol, nu un rol distinct.
<!-- - TEMP:
  - Are acces temporar la functionalitatile aplicatiei, de obicei pentru o perioada limitata sau cu privilegii restranse.(3-rd party collabs)
  - Ramane doar conceptual, neimplementat in aceasta versiune. -->

Identificarea user-ului se face pe baza tokenului per-tenant, care contine id-ul user-ului, id-ul tenant-ului, rolul userului in acel tenant si alte informatii necesare interfetei web (ex: numele tenantului, numele/afisajul userului). Tokenul unui tenant nu contine date despre celelalte tenanturi din care userul mai face parte.
Invitatiile vor expira in termene prestabilite, configurabile per tenant, dupa care nu vor mai fi valide. Un ADMIN poate revoca manual o invitatie inainte de expirare.


Gestionarea separata a datelor pentru fiecare tenant, pentru a asigura izolarea acestora.
- Scara tinta: cca. 1000 de tenanti, intre 100 si 1000 de useri per tenant.
- Toate datele sunt stocate intr-o singura regiune (EU) in aceasta versiune; nu exista cerinta de rezidenta multi-regiune per tenant.
- Un ADMIN trebuie sa poata sterge datele unui user specific din tenantul sau (offboarding/drept la stergere la nivel de user, nu doar la nivel de tenant intreg). In cadrul acelui tenant, referintele la numele si email-ul userului se anonimizeaza (placeholder), pastrand integritatea referentiala pentru facturi/audit, iar restul datelor personale tenant-scoped (telefon, alte date personale) se sterg definitiv. Identitatea globala a userului (email, parola) nu este afectata, deoarece poate fi folosita in alte tenanturi; ea se sterge/anonimizeaza doar la cererea userului sau cand acesta nu mai apartine niciunui tenant.
- Arhitectura trebuie sa permita, conceptual, repartizarea ulterioara a tenantilor pe servere/instante diferite (sharding / load-balancing per tenant), chiar daca in aceasta versiune totul ruleaza pe un singur server/instanta partajata.
- Strategia de izolare: datele tenant-scoped stau intr-o baza de date partajata intre tenanti (un shard), cu coloana discriminator `tenant_id` pe fiecare tabel tenant-scoped, plus un tabel de mapare `tenant_id -> shard_id` (toti tenantii indica azi acelasi shard unic); repartizarea pe shard-uri/servere multiple se face ulterior doar prin actualizarea maparii, fara schimbari in modelul de date sau in query-urile aplicatiei. Organizarea pe servicii si datele globale sunt descrise in sectiunea de aliniere a bazelor de date.
- Enforcement pe doua straturi: filtrare dupa `tenant_id` in codul aplicatiei, plus Postgres Row-Level Security (RLS) ca al doilea strat independent, care blocheaza accesul cross-tenant chiar daca un query din aplicatie omite filtrul.

Capacitatea de a personaliza functionalitatile si interfata aplicatiei pentru fiecare tenant.
- Personalizare vizuala pentru v1: culori (paleta/gradient) + logo, ambele configurabile doar de ADMIN si salvate per tenant.
- Culorile/gradientele se aleg dintr-un catalog fix, definit de platforma (acelasi pentru toti tenantii); ADMIN selecteaza doar un `paletteId`, nu introduce valori CSS/hex brute (elimina riscul de injectie).
- Logo: doar PNG/JPG (fara SVG, pentru a elimina riscul de injectie prin script embedat), cu limita de marime si dimensiuni; stocare printr-un layer abstractizat de object storage (implementare locala initial, inlocuibila ulterior cu un provider extern precum S3, fara schimbari in restul aplicatiei).
- Setarile de personalizare (`paletteId`, `logoUrl`) se stocheaza intr-un JSON flexibil per tenant (`tenant_settings.theme_json`), fara schema fixa, pentru a permite adaugarea de noi optiuni fara migrari viitoare.
- Fiecare tenant nou primeste automat o tema implicita la creare.
- Schimbarile de tema se aplica la urmatorul reload al aplicatiei (login/refresh), preluate printr-un endpoint dedicat, separat de tokenul de autentificare.
- Personalizarea nu se limiteaza la tema vizuala: pot exista ulterior si optiuni de personalizare functionala (feature flags per tenant) nelegate de aspectul vizual, adaugate in acelasi mod flexibil.(De-a lungul vietii aplicatiei, va exista posibilitatea de a adauga mai multe optiuni de personalizare.)

Monitorizarea si raportarea activitatilor la nivel de tenant.
- Se jurnalizeaza doar actiuni sensibile/administrative (login, schimbari de rol, invitatii, retragere drepturi admin, modificari de subscriptie/billing, schimbari de tema) si livrarea email-urilor legate de tenant (`EMAIL_SENT` / `EMAIL_FAILED`, vezi sectiunea de notificari), nu fiecare operatie CRUD.
- ADMIN si MANAGER pot vedea jurnalul de activitate al tenantului lor (vezi sectiunea MANAGER); ceilalti useri nu au acces.
- Retentie: 6 luni in productie, configurabila (in medii de testare se foloseste o valoare de ordinul minutelor/orelor).
- Raportul se obtine la cerere, paginat si filtrabil (dupa data, tip eveniment, actor); nu exista feed live in timp real.
- ADMIN poate exporta jurnalul (CSV) la cerere.
- Fiecare intrare de jurnal e generica: `event_type`, `actor_user_id`, `tenant_id`, `metadata` (JSONB), `created_at` — permite adaugarea de noi tipuri de evenimente fara migrari.
- Tabelul de jurnal este partitionat pe interval de timp (o partitie per luna), cu index pe `tenant_id` in fiecare partitie; stergerea datelor mai vechi de perioada de retentie se face prin eliminarea partitiilor vechi (`DROP PARTITION`), nu prin DELETE in masa. In medii de testare (retentie de minute/ore) se foloseste in schimb un job simplu de stergere, fara partitionare.
- Monitorizarea la nivel de platforma (cross-tenant, pentru echipa care opereaza sistemul) ramane in afara scopului acestei versiuni; poate fi adaugata ulterior.

Scalabilitate pentru a suporta cresterea numarului de tenanti si utilizatori.
- Caching: datele citite frecvent si rar schimbate (setari de tema, status subscriptie/billing, roluri membership) sunt cache-uite in Redis, pentru a reduce presiunea pe baza de date pe masura ce creste numarul de tenanti/useri. Cache-ul se invalideaza la evenimentele de schimbare corespunzatoare; emiterea tokenului la login nu foloseste cache-ul de roluri, ci citeste direct datele de membership.
- Rate limiting pe doua niveluri: o limita stricta per user (request-uri/secunda) care previne ca un singur cont sa suprasolicite API-ul, plus o limita agregata per tenant care se scaleaza proportional cu numarul de useri activi din acel tenant (un tenant cu mai multi useri primeste un plafon agregat mai mare).
- Procesarea asincrona a operatiilor costisitoare (ex. exportul jurnalului de activitate) se decide dinamic, pe baza metricilor de timp de executie (istoric/estimare), nu printr-o lista fixa de operatii; operatiile care depasesc un prag de durata ruleaza ca job in fundal, restul raman sincrone.
- Replicile de citire (read replicas) pentru separarea traficului de citire intensiva raman in afara scopului acestei versiuni; pot fi adaugate ulterior fara schimbari de model de date.
- Capacitate fixa/manuala pentru serviciile aplicatiei in aceasta versiune; introducerea unui load balancer cu scalare automata bazata pe utilizarea CPU-ului per instanta ramane un pas conceptual pentru viitor.
- Se introduce un pooler de conexiuni dedicat (PgBouncer, in modul transaction pooling) in fata bazei de date inca din aceasta versiune, anticipand nevoia de shard-uri multiple per tenant descrisa in strategia de izolare a datelor. 

Arhitectura aplicatiei trebuie sa permita adaugarea usoara a noi functionalitati fara a afecta performanta sau securitatea.
- Modularitate: functionalitatile noi se adauga implicit ca module in serviciul existent cel mai potrivit (auth/org/billing/notification); extragerea intr-un serviciu nou se face doar cand exista un motiv clar (scalare independenta, echipa separata), nu ca regula implicita.
- Orice tabel nou cu date per-tenant trebuie sa foloseasca o clasa de baza (repository/DAO) care aplica automat filtrarea dupa `tenant_id`, astfel incat izolarea sa fie automata, nu optionala, consistent cu enforcement-ul pe doua straturi deja decis.
- Feature flags la nivel de platforma: se reutilizeaza modelul JSON flexibil deja decis la personalizare (`tenant_settings`), extins cu flag-uri boolean per functionalitate, pentru activare graduala (beta/canary) pe subseturi de tenanti.
- Compatibilitate API: regula implicita e doar-adaugare (niciun camp existent nu se elimina/schimba, se adauga doar campuri noi optionale); versionarea explicita (`/v1/`, `/v2/`) se foloseste exclusiv cand o schimbare este incompatibila cu clientii existenti.
- Orice functionalitate noua necesita teste automate de integrare care trec in CI inainte de deploy, ca prag minim impotriva regresiilor.


Gestionarea planurilor de billing, a abonamentelor, facturilor si platilor pentru fiecare tenant.
- Plata ramane complet simulata, dar printr-un layer abstract de "payment gateway" (interfata), inlocuibil ulterior cu un provider real (tip Stripe), fara schimbari in restul aplicatiei — acelasi pattern ca la object storage.
- Schimbarea planului unei subscriptii active se face prin cancel + resubscribe simplu, fara proratare.
- Limita `maxUsers` a planului este impusa efectiv: invitarea unui membru nou peste prag este respinsa pana la un upgrade de plan.
- Starea PAST_DUE poate fi declansata explicit printr-un mecanism de simulare a esecului de plata (util pentru testare); tenantul intra intr-o perioada de gratie de 7 zile in regim read-only, urmata de suspendare completa daca nu se rezolva.
- Fiecare incercare de plata (succes sau esec) se persista ca inregistrare de audit financiar, spre deosebire de implementarea actuala unde entitatea Payment e construita dar nu salvata.
- Perioada de trial (TRIALING) dureaza implicit 14 zile; la expirare, subscriptia trece automat la ACTIVE (declansand o plata simulata), daca adminul nu a anulat explicit inainte.
- Moneda este unica (EUR) pentru aceasta versiune, consistent cu decizia de regiune unica (EU).

Un rol suplimentar MANAGER, cu privilegii intre ADMIN si USER, existent deja in cod dar neacoperit de sectiunea de roluri.
- MANAGER poate invita membri noi, dar doar cu rolul USER (nu poate crea alti ADMIN sau MANAGER) — o relaxare controlata a regulii "doar ADMIN invita", fara risc de escaladare a privilegiilor.
- MANAGER poate vizualiza subscriptia si facturile tenantului, dar nu poate schimba planul, anula subscriptia sau efectua alte actiuni cu impact financiar — acestea raman exclusiv ADMIN.
- Managementul rolurilor (promovare/retrogradare a altor useri) ramane exclusiv ADMIN; MANAGER nu poate schimba rolul niciunui user.
- MANAGER poate vedea jurnalul de activitate al tenantului, alaturi de ADMIN.
- Nu exista o limita minima sau maxima de MANAGER per organizatie (poate fi si zero), spre deosebire de regula "cel putin un ADMIN".

Un flux de resetare a parolei pentru useri care si-au uitat credentialele.
- Parola este globala, per identitate (email), nu per tenant; resetarea se aplica accesului la toate tenanturile userului, consistent cu fluxul de login (email+parola, apoi selectie tenant).
- Tokenul de resetare este valabil 1 ora si de unica folosinta; o noua cerere de resetare invalideaza tokenurile de resetare emise anterior pentru acelasi user.
- Anti-enumerare: cererea de resetare returneaza acelasi raspuns (si un timp de raspuns similar) indiferent daca emailul exista sau nu.
- Access token-urile au durata scurta de viata (15 minute), reinnoite prin refresh token; la resetarea parolei se invalideaza toate refresh token-urile userului, astfel incat orice sesiune activa se pierde in cel mult 15 minute.
- Rate limiting specific pentru cererea de resetare: maxim 3 cereri/ora per email si maxim 10 cereri/ora per IP, suplimentar fata de regulile generale de rate limiting.
- Politica de parola (aplicata la resetare si la inregistrare): minim 12 caractere, verificare fata de o lista de parole comune/compromise, fara reguli fortate de complexitate si fara istoric de parole anterioare in aceasta versiune.
- Dupa schimbarea parolei, userul primeste un email de confirmare, cu indicatii de contact daca schimbarea nu a fost facuta de el.
- Resetarea parolei nu apare in jurnalul de activitate per tenant (este un eveniment al identitatii globale, nu al unui tenant); notificarea userului se face prin emailul de confirmare.
- Resetarea parolei poate fi initiata exclusiv de user; niciun ADMIN de tenant nu poate forta sau initia resetarea parolei altui user, deoarece credentialele sunt folosite si in alte tenanturi.

Notificari in-app pentru useri, separate de email-uri, si un jurnal de email-uri trimise vizibil pentru audit.
- Notificarile in-app sunt per tenant (`tenant_id` + `user_id`): userul vede doar notificarile tenantului activ din token. Evenimentele identitatii globale (ex. schimbarea parolei) se comunica doar prin email.
- Notificarile in-app sunt generate exclusiv de sistem, pe baza evenimentelor interne; nu exista endpoint public prin care un user sa creeze notificari. Anunturile de tip broadcast trimise de ADMIN/MANAGER catre tot tenantul pot fi adaugate ulterior, prin feature flag.
- Evenimente care genereaza notificari in-app in v1: schimbarea rolului (catre userul afectat), cererea de acceptare a retrogradarii ultimului ADMIN (catre adminul vizat), invitatie acceptata (catre ADMIN/MANAGER), subscriptie trecuta in PAST_DUE sau trial care expira in 3 zile (catre ADMIN/MANAGER), export asincron gata de descarcare (catre userul care l-a cerut).
- Notificarile se incarca la deschiderea aplicatiei; numarul de notificari necitite se actualizeaza prin polling la 60 de secunde. Nu exista websocket/SSE in aceasta versiune.
- Retentia notificarilor in-app este de 90 de zile (citite sau necitite), dupa care se sterg automat. La offboarding-ul unui user dintr-un tenant, notificarile lui din acel tenant se sterg.
- Din email-urile trimise se pastreaza doar metadate (destinatar, template/tip eveniment, subiect, status, data), fara body, pentru a nu stoca secrete precum linkuri de resetare sau tokenuri de invitatie.
- Nu exista un jurnal de email-uri separat: email-urile legate de tenant (invitatii, billing) se inregistreaza in jurnalul de activitate al tenantului ca evenimente `EMAIL_SENT` / `EMAIL_FAILED`, cu metadatele in campul `metadata`, preluand automat regulile de retentie, acces (ADMIN+MANAGER) si export ale jurnalului. Email-urile identitatii globale (resetare/confirmare parola) nu apar in niciun jurnal de tenant. Endpoint-ul actual de cautare a email-urilor dupa adresa se elimina.
- Trimiterea unui email esuat se reincearca de maxim 3 ori, cu backoff exponential; dupa epuizarea reincercarilor, emailul primeste statusul `FAILED`.
- Userii nu pot dezactiva email-urile tranzactionale/de securitate sau notificarile in-app in aceasta versiune.

Incarcarea de fisiere generice de catre useri sau administratori, dincolo de logo-ul de personalizare deja definit.
- Nu exista upload generic de fisiere in aceasta versiune. Endpoint-ul existent de upload generic (fara scoping pe tenant, fara verificare de rol, fara restrictii de tip/marime) se elimina.
- Singurul upload din v1 ramane logo-ul tenantului, conform sectiunii de personalizare (PNG/JPG, doar ADMIN, prin layer-ul abstract de object storage).
- Upload-ul de fisiere se va adauga ulterior doar legat de o functionalitate concreta (ex. atasamente la o entitate), reutilizand layer-ul de object storage si regulile de izolare per tenant.

Comunicare asincrona intre servicii prin evenimente (ex. la crearea unui user, la o invitatie sau la crearea unei subscriptii).
- Se folosesc doua brokere, cu roluri distincte:
  - Kafka pentru evenimente de domeniu (fapte petrecute: user inregistrat, membership creat/modificat/eliminat, invitatie acceptata, subscriptie schimbata etc.), pastrate pe durata retentiei pentru replay istoric (reconstruirea proiectiilor, noi consumatori).
  - RabbitMQ pentru comenzi si joburi de lucru (trimitere email, exporturi asincrone), consumate o singura data si sterse dupa procesare.
- Evenimentele din Kafka nu contin date personale (nume, email, telefon), ci doar identificatori; astfel replay-ul istoric nu contrazice anonimizarea/stergerea la offboarding. Datele personale necesare (ex. adresa de email pentru o invitatie) circula doar prin comenzile RabbitMQ.
- Retentia topic-urilor Kafka este aliniata cu cea a jurnalului de activitate (6 luni in productie, configurabila).
- Publicarea se face prin transactional outbox in fiecare serviciu producator: evenimentul/comanda se scrie in tabela `outbox` in aceeasi tranzactie cu datele, iar un proces separat il publica in broker. Procesul de publicare foloseste un rol de baza de date dedicat, cu acces doar la tabela `outbox`, exceptat de la RLS.
- Livrare at-least-once; consumatorii sunt idempotenti, pe baza unui `eventId` unic si a evidentei evenimentelor deja procesate.
- Evenimentele sunt tipizate si versionate intr-o biblioteca comuna (`shared-lib`), cu un plic comun: `eventId`, `eventType`, `version`, `tenantId`, `occurredAt`, `payload`. `tenantId` este obligatoriu pentru evenimentele legate de un tenant; consumatorul seteaza contextul de tenant din el inainte de orice scriere, astfel incat RLS sa se aplice. Evenimentele identitatii globale (ex. user inregistrat, parola schimbata) nu au `tenantId`. Evolutia contractului respecta regula de compatibilitate API: doar adaugare de campuri, versiune noua doar la schimbari incompatibile.
- Procesarea esuata a unui eveniment/comenzi se reincearca de maxim 5 ori cu backoff exponential, apoi mesajul ajunge intr-o dead-letter queue, de unde se reproceseaza manual dupa remediere; mesajele din DLQ se logheaza vizibil in logurile de operare. (Reincercarile de trimitere a unui email, 3, din sectiunea de notificari, sunt la nivelul furnizorului de email, in interiorul procesarii comenzii.)
- La inregistrare nu se creeaza automat niciun tenant. Un user fara tenanturi poate crea o organizatie (devenind ADMIN al ei) sau poate astepta o invitatie; acceptarea unei invitatii creeaza membership-ul.
- Apelurile care tin de autorizare sunt sincrone: la login/emiterea tokenului, auth interogheaza direct org pentru tenanturile si rolurile userului, astfel incat un user eliminat dintr-un tenant sa nu mai primeasca token pentru el. Restul datelor necesare altor servicii se tin in proiectii locale actualizate prin evenimente (ex. org pastreaza limita `maxUsers` a planului curent, actualizata prin evenimentul de schimbare a subscriptiei; o intarziere de cateva secunde este acceptabila).
- Ordinea este garantata doar per tenant: cheia de partitionare in Kafka este `tenantId` (sau `userId` pentru evenimentele identitatii globale). Nu exista ordine globala intre tenanti. Pentru comenzile din RabbitMQ nu se garanteaza ordinea.

Alinierea strategiei curente de baze de date (o schema separata per serviciu) cu strategia de izolare per-tenant descrisa mai sus (schema partajata + `tenant_id` + RLS).
- "Partajat" inseamna partajat intre tenanti, nu intre servicii. Fiecare shard este o singura baza de date, cu cate o schema per serviciu (`org`, `billing`, `notif` etc.) si un rol DB dedicat per serviciu, cu acces doar la schema lui. Fiecare tabel tenant-scoped din fiecare schema are `tenant_id` + RLS.
- Datele care nu apartin niciunui tenant stau intr-o baza de date globala separata, nesharduita: identitatile (email, parola, refresh/reset tokens), catalogul de planuri, catalogul de palete si maparea `tenant_id -> shard_id`. `auth-service` lucreaza pe baza globala. RLS nu se aplica aici; accesul e controlat prin rolurile DB ale serviciilor. Datele tenant-scoped (membership-uri, setari tenant, subscriptii, facturi, jurnal, notificari etc.) stau exclusiv in shard-uri.
- Serviciile determina shard-ul unui tenant pe baza maparii din baza globala (cache-uita), atat pentru request-uri, cat si pentru consumul de evenimente.
- Denumiri unificate: coloana se numeste `tenant_id` peste tot (inlocuind `organization_id`), iar rolurile sunt `ADMIN` / `MANAGER` / `USER` (inlocuind `MEMBER`). "Organizatie" ramane termenul folosit in interfata; in model, organizatia este tenantul.
- Contextul de tenant pentru RLS se seteaza prin `SET LOCAL app.tenant_id` in fiecare tranzactie, automat, de catre repository-ul de baza (compatibil cu PgBouncer in transaction pooling). Politicile RLS sunt fail-closed: o tranzactie fara tenant setat nu vede niciun rand.
- Nu se migreaza date existente: schemele se recreeaza de la zero cu migrari Flyway noi, conforme cu acest model.
- Nu exista chei straine intre schemele unor servicii diferite si nici intre shard-uri si baza globala: referintele intre servicii se fac doar prin ID, iar consistenta se mentine prin evenimente.
- In interiorul unui serviciu, cheile straine intre tabele tenant-scoped sunt compuse (`tenant_id`, `id`), iar tabelele referite au o cheie unica pe (`tenant_id`, `id`); astfel baza de date respinge orice referinta catre un rand al altui tenant, chiar si in cazul unui bug in aplicatie.
- Datele din cataloagele globale referite din shard-uri (ex. planurile) nu se sterg niciodata, ci doar se dezactiveaza, pentru ca subscriptiile existente sa nu ramana cu referinte catre inregistrari inexistente.