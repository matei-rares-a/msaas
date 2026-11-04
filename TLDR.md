# TLDR - cum folosesti aplicatia

Platforma SaaS multi-tenant: fiecare organizatie isi vede doar datele ei. Backend Spring Boot (gateway + 4 servicii), frontend Angular.
Detalii: [README.md](README.md) (arhitectura), [prerequisites.md](prerequisites.md) (mediu, comenzi), [REQUIREMENTS.md](REQUIREMENTS.md) (cerinte).

## 1. Pornire

Ai nevoie de: Windows/Linux/macOS cu **Docker Desktop**, **JDK 21**, **Maven wrapper** (inclus), **Node 22** si fisierul **`.env`** din radacina (secretele de dezvoltare, vine cu clona; fara el scripturile se opresc). Pe o masina noua instalezi tot o singura data cu `pwsh scripts/env/install-prerequisites.ps1` (Windows) sau `bash scripts/env/install-prerequisites.sh` (Linux, macOS), apoi deschizi un terminal nou.

Toate comenzile se dau din radacina proiectului, in doua terminale: unul pentru platforma, unul pentru interfata.

### A. Prima data (sau dupa `--down --purge`, sau dupa ce schimbi cod)

```powershell
# terminal 1 - radacina proiectului
node scripts/env/ensure-dev-env.mjs                 # pregateste mediul: porneste Docker si asteapta, JDK 21, imagini, npm ci
node scripts/infra/demo-stack.mjs --up              # construieste jar-urile (~3-4 min) si ridica platforma (~1 min)
node scripts/infra/demo-stack.mjs --seed            # creeaza conturile si datele demo (~1-2 min); la sfarsit afiseaza conturile si codul de invitatie

# terminal 2
cd angular_base
npm start                                           # interfata: http://localhost:4200 (prima compilare ~30 s)
```

### B. De fiecare data cand repornesti calculatorul (datele demo raman)

```powershell
node scripts/env/ensure-dev-env.mjs                 # porneste Docker Desktop
node scripts/infra/demo-stack.mjs --up --skip-build # ridica platforma din jar-urile existente (construieste doar daca lipsesc)
cd angular_base; npm start
```

Nu mai rulezi `--seed`; il rulezi doar daca ai sters datele (`--purge`). Rularea lui in plus nu strica nimic.

### C. Cand esti gata de folosit

- `docker ps` arata **10 containere** `(healthy)`; `http://localhost:4200` raspunde; gateway-ul e pe `http://localhost:8080`.
- Dupa o pornire la rece primele login-uri pot dura cateva zeci de secunde (aplicatiile Java se incalzesc); apoi merg normal.
- Nimic altceva nu e publicat pe masina (nici Postgres, nici brokerii), deci un Postgres local pe 5432 nu intra in conflict. Portul **8080** si **4200** trebuie sa fie libere.
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
| login-ul da eroare de retea in browser | stiva nu e pornita (`docker ps` trebuie sa arate 10 containere sanatoase) sau `--up` nu a terminat; dupa pornire la rece mai asteapta putin |
| logheaza, dar nu exista conturile | datele lipsesc (ai facut `--purge` sau e prima pornire): `node scripts/infra/demo-stack.mjs --seed` |
| `There is no .env with the development secrets` | lipseste `.env` din radacina; e in git, deci `git checkout -- .env` il readuce |
| `npm start` nu gaseste pachetele | `node scripts/env/ensure-dev-env.mjs` (face `npm ci`) sau `cd angular_base; npm ci` |
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
