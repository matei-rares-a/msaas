# TLDR - de la repo clonat la aplicatia pornita (masina fara nimic instalat)

Platforma SaaS multi-tenant: fiecare organizatie isi vede doar datele ei. Backend Spring Boot (gateway + 4 servicii), frontend Angular.
Detalii: [README.md](README.md) (arhitectura), [prerequisites.md](prerequisites.md) (comenzi de dezvoltare), [REQUIREMENTS.md](REQUIREMENTS.md) (cerinte).

## 0. Ce iti trebuie

| | |
|---|---|
| Sistem | Windows 10 (22H2) / 11, Linux (Ubuntu/Debian/Fedora) sau macOS |
| Memorie | minim 8 GB RAM, recomandat 16 GB (cele 10 containere folosesc ~3 GB; Docker Desktop are nevoie de cel putin 5 GB) |
| Disc | ~15 GB liberi (imaginile Docker, jar-urile, `node_modules`, depozitul Maven) |
| Internet | da, la prima rulare descarca ~3-4 GB (Maven, npm, imaginile Docker); in spatele unui proxy, configureaza-l pentru Maven, npm si Docker Desktop |
| Drepturi | un cont care poate instala programe (Windows: o fereastra UAC pentru Docker Desktop; Linux: `sudo`) |
| Virtualizare | Windows: Intel VT-x / AMD-V activat in BIOS/UEFI (Docker Desktop nu porneste fara); intr-o masina virtuala, virtualizare imbricata |
| Deja instalat | doar **git**, cu care ai clonat |
| Porturi libere | **8080** (gateway) si **4200** (interfata); nimic altceva nu e publicat, deci un Postgres local pe 5432 nu deranjeaza |

Se instaleaza (scriptul o face): **Docker** (Desktop pe Windows si macOS, Engine pe Linux), **JDK 21** (alta versiune nu merge: build-ul o refuza), **Node.js 22**. Maven nu se instaleaza: `mvnw` din repo isi aduce singur Maven. Fisierul `.env` (secretele de dezvoltare, publice intentionat) vine cu clona.

## 1. O singura comanda

Din radacina repo-ului clonat:

**Windows** (PowerShell; nu e nevoie de "Run as administrator"):
```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\env\install-prerequisites.ps1 -Setup
```
(`-ExecutionPolicy Bypass` e nevoie pentru ca Windows blocheaza implicit scripturile; valabil doar pentru aceasta rulare.)

**Linux / macOS**:
```bash
bash scripts/env/install-prerequisites.sh --setup
```
(`--yes` il lasa sa instaleze fara sa intrebe, util fara terminal interactiv; `--dry-run` doar arata ce ar rula.)

### Ce se intampla, in ordine

1. Verifica ce ai deja (Docker, JDK 21, Node 22) si instaleaza ce lipseste.
2. **Daca a instalat Docker, se opreste cu codul 10** si iti spune ce urmeaza; asa trebuie:
   - Windows: restart daca cere installerul (activeaza WSL 2), apoi deschide **Docker Desktop** o data, accepta termenii si asteapta pana scrie ca ruleaza; Docker Desktop e gratuit pentru uz personal, educational si firme mici, iar pentru firme mari cere licenta platita.
   - Linux: iesi din sesiune si intra la loc (intri in grupul `docker`).
   - macOS: `open -a Docker` o data, accepta termenii.
   Apoi rulezi **aceeasi comanda** din nou; de data asta continua.
3. `scripts/env/setup.mjs`: verifica masina (memorie, disc, porturi, internet, containere in conflict), pregateste mediul (`JAVA_HOME`, `npm ci`), construieste jar-urile, ridica platforma, creeaza conturile demo si porneste interfata.
4. La sfarsit scrie `READY. Platform: http://localhost:8080  Interface: http://localhost:4200` si lasa interfata pornita in terminal (Ctrl+C o opreste, platforma ramane).

Prima rulare completa dureaza ~15-30 de minute (descarcari + build ~3-5 min + pornire ~1 min + date demo ~1-2 min). Apoi deschizi **http://localhost:4200** si te loghezi (sectiunea 3).

Se poate relua oricand, fara efecte rele: fiecare pas nu face nimic daca e deja facut. Doar verificare, fara sa schimbe nimic: `node scripts/env/setup.mjs --check`. Optiuni: `--no-ui` (nu porneste interfata), `--skip-build` (refoloseste jar-urile), `--dev` (pregateste si pentru teste: imaginile testelor de integrare si un browser pentru testele Angular).

## 2. Instalare manuala (daca scriptul nu merge pe masina ta)

| Ce | De unde | Verificare |
|---|---|---|
| Docker | Windows/macOS: https://www.docker.com/products/docker-desktop/ ; Linux: https://docs.docker.com/engine/install/ | `docker info` fara eroare |
| JDK 21 | https://adoptium.net/temurin/releases/?version=21 (Windows: MSI, bifeaza "Set JAVA_HOME"; Linux/macOS: arhiva dezarhivata in `~/.local/jdks`) | `java -version` arata `21.x` |
| Node.js 22 | https://nodejs.org/en/download | `node -v` arata `v22.x` (20.11+ merge) |

Apoi, intr-un terminal nou, din radacina: `node scripts/env/setup.mjs`.

## 3. Conturi demo

Parola tuturor: **`Demo-Passphrase-2026`**

| Cont | Organizatie | Rol | Ce faci cu el |
|---|---|---|---|
| `alice@demo.test` | Acme Corp + Globex | ADMIN in Acme, USER in Globex | la login alegi organizatia; vezi tot: billing, invitatii, jurnal, module, tema, politica de concediu |
| `bob@demo.test` | Acme Corp | MANAGER | billing doar citire, invita doar USER, jurnal, aproba concedii, configureaza lunch |
| `carol@demo.test`, `dave@demo.test` | Acme Corp | USER | cere concediu, se inscrie la lunch, notificari; fara billing si jurnal |
| `erin@demo.test` | Globex | ADMIN | a doua organizatie (tema Sunset, plan Starter); datele nu se amesteca cu Acme |
| `frank@demo.test` | - | fara organizatie | ecran "fara organizatie": creeaza una sau accepta o invitatie cu codul afisat de `--seed` |

Un cont nou: `/register` (parola de minim 12 caractere), apoi creezi o organizatie (devii ADMIN) sau accepti o invitatie.

## 4. Ecrane (dupa login, in bara de sus)

`/dashboard` (acasa) | `/notifications` | `/billing` (ADMIN scrie, MANAGER citeste) | `/invitations` | `/journal` (ADMIN, MANAGER) |
`/leave` (cererea mea, sold, calendar) | `/leave/approvals` (MANAGER, ADMIN) | `/leave/policy` (ADMIN) | `/lunch` | `/modules` (ADMIN) | `/theme` (ADMIN)

Ce nu vezi cu rolul tau nu apare in meniu, iar serverul refuza oricum cererea (403).

## 5. Ce merita incercat

1. **Concediu**: carol -> `/leave` cere zile; alice sau bob -> `/leave/approvals` aproba; carol primeste notificare in `/notifications`.
2. **Mai multe organizatii**: alice se logheaza si alege Acme sau Globex; in fiecare vede doar datele organizatiei alese.
3. **Invitatie**: alice -> `/invitations` invita un email nou cu un rol; emailul nu se trimite, doar se inregistreaza, deci codul se citeste din DB:
   `docker exec saas-postgres psql -U <POSTGRES_SUPERUSER din .env> -d saas_shard_1 -c "select email, token from org.invitations"`. Un cont nou (sau frank) il introduce pe ecranul de invitatie.
4. **Roluri**: acelasi ecran cu alice, bob, carol - compara ce poti face.
5. **Tema**: alice -> `/theme` alege o paleta sau incarca un logo; se aplica doar organizatiei ei.
6. **Lunch roulette**: `/lunch` - inscriere in runda saptamanii; grupurile se formeaza la ora limita (MANAGER/ADMIN o configureaza).
7. **Jurnal**: `/journal` arata evenimentele organizatiei (login-uri, invitatii, schimbari de modul).

## 6. Zilnic

```powershell
# repornire dupa ce ai oprit calculatorul (datele demo raman)
node scripts/env/ensure-dev-env.mjs                    # porneste Docker Desktop si asteapta
node scripts/infra/demo-stack.mjs --up --skip-build    # ridica platforma din jar-urile existente (construieste doar daca lipsesc)
cd angular_base; npm start                             # interfata, http://localhost:4200

# oprire
node scripts/infra/demo-stack.mjs --down               # opreste, datele raman (Ctrl+C opreste interfata)
node scripts/infra/demo-stack.mjs --down --purge       # opreste si sterge datele (apoi --up si --seed)
```

Dupa `git pull` cu schimbari de cod: `node scripts/infra/demo-stack.mjs --up` (fara `--skip-build`) reconstruieste jar-urile. Datele demo se recreeaza cu `--seed` (repetabil, fara duplicate).

Gata de folosit = `docker ps` arata **10 containere** `(healthy)`. Dupa o pornire la rece primele login-uri pot dura cateva zeci de secunde (aplicatiile Java se incalzesc).

## 7. Daca ceva nu merge

| Simptom | Cauza / remediu |
|---|---|
| `running scripts is disabled on this system` | porneste scriptul cu `-ExecutionPolicy Bypass`, exact ca in sectiunea 1 |
| scriptul iese cu codul 10 | nu e eroare: Docker tocmai s-a instalat; fa pasii din sectiunea 1 (restart / Docker Desktop o data / logout), apoi aceeasi comanda |
| `winget is not available` | instaleaza "App Installer" din Microsoft Store, sau foloseste sectiunea 2 |
| `Virtualization: NOT enabled` | activeaza Intel VT-x / AMD-V (SVM) in BIOS/UEFI; Docker Desktop nu porneste fara |
| Docker Desktop nu porneste / `Docker did not start` | l-ai deschis si ai acceptat termenii? Asteapta pana scrie ca ruleaza; pe Windows verifica ca WSL 2 e activ (`wsl --status`) |
| `permission denied ... docker.sock` (Linux) | nu esti inca in grupul `docker`: iesi din sesiune si intri la loc |
| `failed to connect to the docker API` | Docker nu ruleaza: `node scripts/env/ensure-dev-env.mjs` |
| `NOT READY ... Port 8080/4200` | alta aplicatie le foloseste; adresa gateway-ului e fixata in interfata, deci opreste cealalta aplicatie |
| `containers with fixed names that belong to another stack` | ruleaza alta stiva (`e2e-stack.mjs`, `local-stack.mjs`): `--down` pe ea; nu merg doua deodata |
| build-ul refuza versiunea de Java / `no JDK 21 found` | numai Java 21 e acceptat; `node scripts/env/ensure-dev-env.mjs` seteaza `JAVA_HOME` (terminal nou dupa aceea) |
| `JDK 21 is not available from this system's packages` (Linux) | Temurin 21 de la adoptium.net, dezarhivat in `~/.local/jdks`, apoi reia scriptul |
| `Internet: no answer from ...` | e doar avertisment; in spatele unui proxy configureaza-l pentru Maven, npm, Docker Desktop |
| login-ul da eroare de retea in browser | platforma nu e pornita (`docker ps`: 10 containere sanatoase) sau `--up` nu a terminat |
| logheaza, dar nu exista conturile | `node scripts/infra/demo-stack.mjs --seed` |
| `There is no .env with the development secrets` | `git checkout -- .env` |
| `npm start` nu gaseste pachetele | `node scripts/env/ensure-dev-env.mjs` (face `npm ci`) |
| 429 Too Many Requests | limita de cereri (10/s per user); asteapta o secunda |

Daca nu iti dai seama: `node scripts/env/setup.mjs --check` spune exact ce lipseste, iar `docker compose -p msaas-demo logs <serviciu>` arata logul unui serviciu (`auth-service`, `organization-service`, `billing-service`, `notification-service`, `api-gateway`).

## 8. Teste si verificari (pentru dezvoltare)

```powershell
node scripts/env/setup.mjs --dev --no-ui               # pregateste si pentru teste (imaginile testelor, browser pentru Angular)
./mvnw -B -ntp clean verify                            # tot backend-ul, cere Docker (~10 minute)
cd angular_base; npm run build; npm run test:ci; npm run e2e
node scripts/verify/check-traceability.mjs             # fiecare cerinta are test
node scripts/infra/e2e-stack.mjs --all                 # 21 de teste pe toata platforma, pe o stiva separata
```

Nu uita: stiva demo, cea E2E si `local-stack.mjs` folosesc aceleasi nume de containere si nu ruleaza deodata.
