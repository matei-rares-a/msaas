# Prompt: TLDR + script de setup pentru cineva care tocmai a clonat repo-ul

Foloseste-l cand vrei ca un agent (Copilot, Claude etc.) sa faca pentru **orice repo** ce s-a facut aici: un `TLDR.md` si un script de setup pentru un om care a clonat repo-ul pe o masina fara nicio dependinta. Copiaza blocul de mai jos in chatul agentului, in radacina repo-ului, si completeaza `[...]`. Sectiunile de dupa bloc sunt pentru tine (verificarea rezultatului).

## Promptul (de copiat)

```text
Contextul: cineva tocmai a clonat acest repo pe o masina curata: are doar git si sistemul de operare, nimic din ce cere
proiectul (niciun runtime, SDK, Docker, manager de pachete configurat). Nu cunoaste proiectul. Sisteme tinta: [Windows 10/11,
Ubuntu/Debian/Fedora, macOS - sterge ce nu e cazul]. Scopul: [sa porneasca si sa incerce aplicatia manual / sa poata dezvolta si
rula testele / ambele].

Livrabile:
1. TLDR.md in radacina - ghidul complet de la "repo clonat" la "aplicatia ruleaza si stiu cum o folosesc".
2. Un script de setup care face automat tot ce se poate automatiza, rulabil dintr-o singura comanda per sistem.
3. Verificarea lor, descrisa onest in raspunsul final.

Mod de lucru:

A. Inventar, fara presupuneri. Citeste manifestele si configurarile reale (pom.xml / build.gradle, package.json si lockfile,
   pyproject / requirements, go.mod, Cargo.toml, Dockerfile-uri, docker-compose, workflow-urile CI, .tool-versions, .nvmrc,
   README, fisierele .env*). Scoate de acolo: ce tool-uri si ce VERSIUNI EXACTE cere proiectul (CI-ul si regulile de build, gen
   maven-enforcer sau "engines", bat orice presupunere), pe ce porturi asculta, cate resurse cere (RAM, disc), ce descarca la
   prima rulare, ce fisiere de configurare/secrete trebuie sa existe, ce comenzi pornesc aplicatia. Separa "ce trebuie ca sa
   ruleze" de "ce trebuie ca sa dezvolt/testez". Daca ceva nu reiese din repo, spune-o in loc sa ghicesti.

B. Gandeste ca pe o masina goala. Pasul de pornire nu poate fi scris intr-un limbaj care cere un runtime inca neinstalat. Foloseste
   cate un script subtire specific platformei (PowerShell / bash) DOAR ca sa instaleze runtime-ul scriptului principal, iar restul
   logicii pune-o intr-un singur script cross-platform (in limbajul proiectului sau Node/Python daca proiectul il are deja). Daca
   repo-ul are deja scripturi sau un folder de scripturi, extinde-le, nu duplica.

C. Cerintele scripturilor:
   - Idempotente: a doua rulare nu schimba nimic; fiecare pas verifica daca e deja facut.
   - Moduri: --check (raporteaza, nu modifica, exit != 0 daca nu e gata), --dry-run unde instaleaza ceva (afiseaza comenzile),
     --yes (fara intrebari), optiuni pentru a sari peste pasii grei.
   - Coduri de iesire clare; un cod DISTINCT pentru "trebuie restart / logout / prima pornire a unui program, apoi reia aceeasi
     comanda" (nu e eroare, spune-i omului exact ce are de facut).
   - Preflight inainte de orice instalare: RAM, spatiu pe disc, porturi libere, acces la internet/registre (avertisment, nu blocaj:
     poate fi proxy), virtualizare daca foloseste containere, procese/containere care intra in conflict. Fiecare esec spune cum se
     repara.
   - Dupa instalare, actualizeaza PATH-ul si variabilele (JAVA_HOME etc.) in aceeasi sesiune; nu cere "deschide alt terminal" decat
     daca e inevitabil.
   - Nu presupune drepturi de administrator; cere-le doar unde e necesar si spune-o dinainte.
   - Foloseste managerul de pachete al sistemului (winget, apt, dnf, brew) cu pachetele care EXISTA pe distributiile curente
     (verifica, nu presupune: unele nu au pachetele pe care le-ai astepta). Cand nu exista, ofera varianta manuala cu link oficial.
   - Nu rula cod descarcat fara sa-l poti inspecta: salveaza-l intr-un fisier si cere confirmare inainte sa-l rulezi ca root, iar
     binarele descarcate verifica-le cu suma de control publicata.
   - Nimic hardcodat: secretele si setarile se citesc din fisierul de configurare al proiectului. Nu sterge nimic ireversibil fara
     confirmare.
   - Asteptarile (pornirea unui daemon, a unui serviciu) au timeout; scriptul nu se blocheaza niciodata fara sa spuna de ce.
   - Detalii de platforma: PowerShell 5.1 citeste un .ps1 fara BOM ca ANSI, deci fisierul sa fie doar ASCII; in PowerShell 5.1,
     stderr-ul unui program nativ devine eroare cu $ErrorActionPreference='Stop' (nu rula "program -version" asa); politica de
     executie blocheaza scripturile implicit, deci comanda din TLDR foloseste -ExecutionPolicy Bypass; scripturile bash au LF, nu CRLF.

D. TLDR.md trebuie sa contina, in ordinea asta: cerinte (sistem, RAM, disc, internet, drepturi, virtualizare, ce trebuie sa fie deja
   instalat, porturi); O singura comanda per sistem; ce se intampla pas cu pas, inclusiv momentele in care scriptul se opreste
   intentionat si ce face omul; cat dureaza prima rulare; instalarea manuala cu linkuri si comenzi de verificare, pentru cand
   scriptul nu merge; cum verifici ca a mers; cum folosesti aplicatia (conturi/date demo, ecrane, 5-7 lucruri de incercat); pornire si
   oprire de zi cu zi; tabel de probleme cu mesajele EXACTE de eroare si remedii; sectiune separata pentru dezvoltare/teste. Fara
   jargon nedefinit; comenzile sa fie copiabile; nimic scris acolo nu e neverificat.

E. Verifica, onest:
   - Ruleaza tot ce se poate pe masina ta: --check, --dry-run, rularea repetata (idempotenta), pornirea reala cap-coada, iar unde
     se poate, pornirea "la rece" (sterge artefactele de build si datele, apoi reia).
   - Scrie teste pentru logica pura (verificarile de preflight, parsarea) si pentru ce poate deriva: versiunile din scripturi sa fie
     cele din CI si din build, comenzile din TLDR sa existe, fisierul PowerShell sa fie ASCII. Verifica testele si pe o mutatie
     (stric ceva si vezi ca pica).
   - Un OS curat se poate simula pentru Linux: containere Docker goale (ubuntu, debian, fedora), cu sursa montata read-only si un
     utilizator normal cu sudo. Rulezi installerul cu adevarat (apt, dnf, scriptul oficial Docker), apoi a doua oara (idempotenta), apoi
     --check. Pentru drumul complet porneste in container un daemon Docker propriu (--privileged) si ruleaza comanda unica. Pune
     harness-ul ca script in repo (aici: scripts/verify/clean-machine.mjs) si ruleaza-l in CI cu o matrice de distributii. Pe o retea
     de firma care inspecteaza TLS, containerul are nevoie de CA-ul ei (aici: --detect-ca); un esec de certificat nu e un defect al
     scriptului. Citeste logurile: au gasit defecte reale (pachet JDK inexistent pe Debian 12, systemctl absent care oprea tot scriptul,
     pachete GUI inutile). Windows si macOS nu se pot simula asa: spune explicit ce ai testat si ce nu; nu scrie niciodata "testat
     pe masina noua" daca nu e adevarat. Pune in script si in TLDR ce ramane netestat.

F. Respecta regulile repo-ului (AGENTS.md, copilot-instructions, conventii de commit, limba documentelor, indexul/jurnalul de
   progres). Fa commit local cu mesajul potrivit; nu face push si nu sterge nimic fara confirmare. Nu modifica cod de aplicatie.

Raspunsul final: ce ai creat si unde; comanda unica per sistem; cum ai verificat; ce NU ai putut verifica si riscul aferent;
presupunerile facute; ce trebuie sa stie omul care il foloseste primul.
```

## Cum il folosesti

1. Deschide agentul in radacina repo-ului (**fara** modificari necomise, ca sa vezi curat ce adauga).
2. Lipeste blocul, completeaza sistemele tinta si scopul. In mod interactiv agentul poate pune intrebari (sisteme suportate, daca e acceptabil Docker Desktop pentru licentele firmei, unde sa puna fisierele); in mod autonom le decide si le declara.
3. Citeste raspunsul final **mai ales la "ce nu am putut verifica"**: acolo sunt riscurile reale.

## Lista de acceptare (pentru tine, dupa ce termina agentul)

- [ ] Versiunile instalate de scripturi coincid cu cele din CI / build (nu cu "ce era mai nou").
- [ ] Exista `--check` si nu modifica nimic; a doua rulare nu schimba nimic.
- [ ] Cazul "a trebuit sa instaleze ceva care cere restart / logout" are cod de iesire propriu si mesaj clar.
- [ ] TLDR-ul are o singura comanda per sistem, iar comenzile se copiaza fara editari.
- [ ] Mesajele de eroare din tabelul de probleme sunt cele pe care le scoate scriptul (cauta-le in cod).
- [ ] Nu exista `curl | sh` ca root fara confirmare, nicio parola hardcodata, nicio stergere fara confirmare.
- [ ] Raportul spune onest ce nu s-a testat pe o masina curata.
- [ ] Un om care nu cunoaste proiectul a urmat TLDR-ul pe o masina (sau VM) reala. **Acesta e testul care conteaza**; un agent il poate pregati, dar nu il poate inlocui. Daca poti, ruleaza-l intr-o masina virtuala noua si noteaza orice se rupe.

## Exemplul din acest repo

Rezultatul aceluiasi prompt aplicat pe MSaaS (Java 21 + Docker Compose + Angular):

| Livrabil | Fisier |
|---|---|
| Ghid | [TLDR.md](TLDR.md) |
| Windows: instaleaza Docker Desktop, JDK 21, Node 22 si continua | [scripts/env/install-prerequisites.ps1](scripts/env/install-prerequisites.ps1) (`-Setup`, `-Check`) |
| Linux / macOS, la fel | [scripts/env/install-prerequisites.sh](scripts/env/install-prerequisites.sh) (`--setup`, `--check`, `--dry-run`, `--yes`) |
| Orchestratorul cross-platform (preflight, mediu, build, pornire, date demo, interfata) | [scripts/env/setup.mjs](scripts/env/setup.mjs) (`--check`, `--no-ui`, `--skip-build`, `--dev`) |
| Teste ale logicii si ale "drift"-ului de versiuni | [scripts/env/setup.test.mjs](scripts/env/setup.test.mjs) |
| Harness: containere Linux goale (Ubuntu, Debian, Fedora; root sau utilizator cu sudo; `--full` = tot drumul cu daemon Docker propriu), rulat si in CI | [scripts/verify/clean-machine.mjs](scripts/verify/clean-machine.mjs), teste in [clean-machine.test.mjs](scripts/verify/clean-machine.test.mjs) |

Ce a ramas netestat acolo: Windows (winget, Docker Desktop) si macOS (brew) pe un sistem curat; pe Linux instalarile reale au rulat in containere goale (Ubuntu 22.04/24.04, Debian 12, Fedora 41).
