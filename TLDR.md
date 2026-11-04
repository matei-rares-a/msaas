# TLDR - cum folosesti aplicatia

Platforma SaaS multi-tenant: fiecare organizatie isi vede doar datele ei. Backend Spring Boot (gateway + 4 servicii), frontend Angular.
Detalii: [README.md](README.md) (arhitectura), [prerequisites.md](prerequisites.md) (mediu, comenzi), [REQUIREMENTS.md](REQUIREMENTS.md) (cerinte).

## 1. Pornire (de fiecare data cand repornesti calculatorul)

```powershell
cd C:\local_store\files\my_workspace\msaas
node scripts/env/ensure-dev-env.mjs               # porneste Docker Desktop si asteapta (JDK 21, imagini, npm ci)
node scripts/infra/demo-stack.mjs --up --skip-build   # ridica platforma; datele demo raman de data trecuta
cd angular_base
npm start                                         # interfata: http://localhost:4200
```

- Prima data pe o masina noua (sau dupa schimbari de cod): fara `--skip-build`, ca sa construiasca jar-urile.
- Datele nu exista inca: `node scripts/infra/demo-stack.mjs --seed` (repetabil, fara duplicate; reafiseaza si codul de invitatie).
- API-ul (gateway) e pe `http://localhost:8080`; nimic altceva nu e publicat.

## 2. Conturi demo

Parola tuturor: **`Demo-Passphrase-2026`**

| Cont | Organizatie | Rol | Ce faci cu el |
|---|---|---|---|
| `alice@demo.test` | Acme Corp + Globex | ADMIN in Acme, USER in Globex | la login alegi organizatia; vezi tot: billing, invitatii, jurnal, module, tema, politica de concediu |
| `bob@demo.test` | Acme Corp | MANAGER | billing doar citire, invita doar USER, jurnal, aproba concedii, configureaza lunch |
| `carol@demo.test`, `dave@demo.test` | Acme Corp | USER | cere concediu, se inscrie la lunch, notificari; fara billing si jurnal |
| `erin@demo.test` | Globex | ADMIN | a doua organizatie (tema Sunset, plan Starter); datele nu se amesteca cu Acme |
| `frank@demo.test` | - | fara organizatie | ecran "fara organizatie": creeaza una sau accepta o invitatie cu codul din `--seed` |

Un cont nou: `/register` (parola de minim 12 caractere), apoi creezi o organizatie (devii ADMIN) sau accepti o invitatie.

## 3. Ecrane (dupa login, in bara de sus)

`/dashboard` (acasa) | `/notifications` | `/billing` (ADMIN scrie, MANAGER citeste) | `/invitations` | `/journal` (ADMIN, MANAGER) |
`/leave` (cererea mea, sold, calendar) | `/leave/approvals` (MANAGER, ADMIN) | `/leave/policy` (ADMIN) | `/lunch` | `/modules` (ADMIN) | `/theme` (ADMIN)

Ce nu vezi cu rolul tau nu apare in meniu, iar serverul refuza oricum cererea (403).

## 4. Ce merita incercat

1. **Concediu**: carol -> `/leave` cere zile; alice sau bob -> `/leave/approvals` aproba; carol primeste notificare in `/notifications`.
2. **Mai multe organizatii**: alice se logheaza si alege Acme sau Globex; in fiecare vede doar datele organizatiei alese.
3. **Invitatie**: alice -> `/invitations` invita un email nou cu un rol; emailul nu se trimite, doar se inregistreaza, deci codul se citeste din DB:
   `docker exec saas-postgres psql -U <POSTGRES_SUPERUSER din .env> -d saas_shard_1 -c "select email, token from org.invitations"`. Un cont nou (sau frank) il introduce pe ecranul de invitatie.
4. **Roluri**: acelasi ecran cu alice, bob, carol - compara ce poti face.
5. **Tema**: alice -> `/theme` alege o paleta sau incarca un logo; se aplica doar organizatiei ei.
6. **Lunch roulette**: `/lunch` - inscriere in runda saptamanii; grupurile se formeaza la ora limita (MANAGER/ADMIN o configureaza).
7. **Jurnal**: `/journal` arata evenimentele organizatiei (login-uri, invitatii, schimbari de modul).

## 5. Oprire

```powershell
node scripts/infra/demo-stack.mjs --down            # opreste, datele raman
node scripts/infra/demo-stack.mjs --down --purge    # opreste si sterge datele (apoi --up si --seed)
```

Opreste `npm start` cu Ctrl+C.

## 6. Daca ceva nu merge

| Simptom | Cauza / remediu |
|---|---|
| `failed to connect to the docker API` | Docker Desktop nu ruleaza: `node scripts/env/ensure-dev-env.mjs` |
| login-ul da eroare de retea in browser | stiva nu e pornita (`docker ps` trebuie sa arate 10 containere sanatoase) sau `--up` nu a terminat |
| `port 8080 already in use` | alta aplicatie pe 8080; frontend-ul are adresa fixata, deci o opresti pe cealalta |
| numele de containere `saas-*` exista deja | alta stiva ruleaza (E2E, `local-stack.mjs`): `--down` pe ea; nu merg doua deodata |
| 429 Too Many Requests | limita de cereri (10/s per user); asteapta o secunda |
| lipseste `.env` | contine secretele de dezvoltare, publice intentionat; fara el scripturile se opresc |

## 7. Teste si verificari (pentru dezvoltare)

```powershell
./mvnw -B -ntp clean verify                       # tot backend-ul, cere Docker (~10 minute)
cd angular_base; npm run build; npm run test:ci; npm run e2e
node scripts/verify/check-traceability.mjs        # fiecare cerinta are test
node scripts/infra/e2e-stack.mjs --all            # 21 de teste pe toata platforma, pe o stiva separata
```
