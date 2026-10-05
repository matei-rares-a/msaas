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
pwsh scripts/env/install-prerequisites.ps1   # Windows
```

```bash
bash scripts/env/install-prerequisites.sh    # Linux, macOS
```

Instaleaza Docker, JDK 21, Maven si Node.js. Sunt singurele scripturi dependente de platforma, pentru
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

## Cand e nevoie de Docker

| Operatie | Docker |
|---|---|
| teste de integrare (`*IT`), `mvnw verify` | da |
| `mvnw verify -DskipITs`, compilare | nu |
| build si teste Angular | nu |
| scriptul de trasabilitate | nu |
| `scripts/infra/local-stack.mjs` | da |
