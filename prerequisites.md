# Pregatirea mediului local

Totul se face prin scripturile din `scripts/`, grupate dupa scop. Sunt scrise in Node, deci ruleaza identic pe Windows, Linux si macOS. Secretele se citesc din `.env`. Nu sunt necesare servicii platite sau chei de API.

| Folder | Scop |
|---|---|
| `scripts/env/` | pregateste masina de dezvoltare |
| `scripts/infra/` | porneste/opreste containerele locale pentru verificari manuale |
| `scripts/verify/` | verificari care esueaza build-ul si CI-ul |
| `scripts/lib/` | cod comun (citirea `.env`) |

## 1. O singura data, pe o masina noua

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\env\install-prerequisites.ps1   # Windows (Windows PowerShell 5.1 sau PowerShell 7)
```

```bash
bash scripts/env/install-prerequisites.sh    # Linux, macOS
```

Instaleaza Docker (Desktop pe Windows/macOS, Engine pe Linux), JDK 21 si Node.js 22 (Maven nu: `mvnw` si-l aduce singur). Cu `-Setup` (Windows) / `--setup` (Linux, macOS) continua cu `node scripts/env/setup.mjs`: preflight, mediu, build, platforma, date demo, interfata. Daca tocmai a instalat Docker, iese cu codul 10 (restart / prima pornire / logout), apoi reiei aceeasi comanda. Ghid complet pentru o masina goala: [TLDR.md](TLDR.md). Sunt singurele scripturi dependente de platforma, pentru
ca instaleaza chiar Node, pe care ruleaza restul. Dupa instalare, deschide un terminal nou.

## 2. La inceputul fiecarei sesiuni de lucru (si dupa fiecare repornire)

```bash
node scripts/env/ensure-dev-env.mjs            # configureaza si porneste ce lipseste
node scripts/env/ensure-dev-env.mjs --check    # doar raporteaza, nu modifica nimic
```

Scriptul: seteaza `JAVA_HOME` catre JDK 21, porneste Docker si asteapta daemonul, descarca imaginile
folosite de testele de integrare (cu reincercari), seteaza `CHROME_BIN` pentru testele Angular,
ruleaza `npm ci` daca lipseste `node_modules` si verifica cheile din `.env`.

## 3. Build si teste

```powershell
.\mvnw.cmd verify                 # tot proiectul, inclusiv testele de integrare (cere Docker)
.\mvnw.cmd verify -DskipITs       # doar testele unit (fara Docker)
.\mvnw.cmd -pl <modul> -am verify # un singur modul

cd angular_base; npm run build; npm run test:ci

node scripts/verify/check-traceability.mjs   # fiecare cerinta are test
```

## 4. Containere locale pentru verificari manuale

```bash
node scripts/infra/local-stack.mjs --up     # fara porturi publicate (nu intra in conflict cu Postgres-ul local)
node scripts/infra/local-stack.mjs --down   # opreste si sterge volumele
```

Testele de integrare **nu** au nevoie de acest pas: isi pornesc singure containerele prin Testcontainers.

## 5. Suita E2E (toata platforma pe Docker Compose)

```bash
node scripts/infra/e2e-stack.mjs --all    # construieste jar-urile, ridica stiva, ruleaza testele, o opreste
node scripts/infra/e2e-stack.mjs --up --skip-build   # doar ridica stiva (gateway pe 127.0.0.1:18080, Postgres pe 127.0.0.1:15432)
node scripts/infra/e2e-stack.mjs --test    # ruleaza testele pe stiva pornita
node scripts/infra/e2e-stack.mjs --down
```

Un test de lunch dureaza ~2,5 minute; intreaga suita, cateva minute dupa build.

## Cand e nevoie de Docker

| Operatie | Docker |
|---|---|
| teste de integrare (`*IT`), `mvnw verify` | da |
| `mvnw verify -DskipITs`, compilare | nu |
| build si teste Angular | nu |
| scriptul de trasabilitate | nu |
| `scripts/infra/local-stack.mjs`, `scripts/infra/e2e-stack.mjs`, `scripts/infra/demo-stack.mjs` | da |

## 6. Platforma cu date demo, de incercat manual

```bash
node scripts/infra/demo-stack.mjs --up       # construieste jar-urile si ridica stiva (proiectul msaas-demo; doar gateway pe 127.0.0.1:8080)
node scripts/infra/demo-stack.mjs --seed     # creeaza conturile, organizatiile si datele demo; se poate repeta fara duplicate
cd angular_base && npm start                 # interfata, http://localhost:4200
node scripts/infra/demo-stack.mjs --down     # opreste stiva, datele raman; --down --purge le sterge
```

Stiva E2E si `local-stack.mjs` trebuie oprite (aceleasi nume de containere). Parola tuturor conturilor: `Demo-Passphrase-2026`.

| Cont | Organizatie | Rol | Ce se vede |
|---|---|---|---|
| `alice@demo.test` | Acme Corp, Globex | ADMIN in Acme, USER in Globex | login cu alegerea organizatiei; billing, invitatii, jurnal, module, tema |
| `bob@demo.test` | Acme Corp | MANAGER | billing doar-citire, invitatii (doar USER), jurnal, aprobarea concediilor |
| `carol@demo.test`, `dave@demo.test` | Acme Corp | USER | concediu, lunch roulette, notificari; fara billing si fara jurnal |
| `erin@demo.test` | Globex | ADMIN | o a doua organizatie (plan Starter, tema Sunset): datele nu se amesteca cu Acme |
| `frank@demo.test` | - | fara organizatie | pagina "fara organizatie": poate crea una sau poate introduce codul invitatiei in Acme (scriptul il afiseaza; sta si in `org.invitations.token`) |

Datele din Acme: modulele concediu si lunch pornite, o cerere de concediu in asteptare (carol) si una aprobata (bob), runda de lunch cu trei inscrisi, tema Forest, jurnalul cu evenimentele de mai sus. Planurile din catalog: Starter (5 useri), Business (50), Enterprise (500); nu exista ecran de alegere a planului, deci se schimba din DB.

Ce poti incerca: autentificare cu mai multe organizatii (alice) si cu niciuna (frank); izolarea (alice vede in Globex doar Globex); rolurile (acelasi ecran, trei conturi); cerere de concediu (carol) -> aprobare (alice sau bob) -> notificarea ajunge la carol; invitatie (alice invita un email nou, codul se citeste din DB pentru ca emailul nu se trimite, doar se inregistreaza); jurnalul in `/journal`.

Pentru API direct, prin gateway: `POST http://localhost:8080/auth/login` cu `{"email","password"}`, apoi `Authorization: Bearer <accessToken>` pe celelalte cereri; lista endpoint-urilor e in `README.md` si, la zi, in `/swagger-ui.html` al fiecarui serviciu (porturile 8081-8084 nu sunt publicate in stiva demo).