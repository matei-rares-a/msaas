#!/usr/bin/env node
/**
 * Proves the setup path on a bare Linux system: a container with nothing on it but the operating system receives a copy of the
 * repository as it is in the working tree, and the steps of TLDR.md are followed in it. Docker is the only thing it needs here.
 *
 *   node scripts/verify/clean-machine.mjs                              Ubuntu 24.04, as root: the installer, twice, and --check
 *   node scripts/verify/clean-machine.mjs --distro debian:12 --user    a person's account with sudo, on Debian 12 (no JDK 21 package)
 *   node scripts/verify/clean-machine.mjs --full                       also start a Docker daemon in the container and run
 *                                                                      setup.mjs: build, platform, demo data (about 20 minutes)
 *   --detect-ca        a network that inspects TLS (a company proxy): its CA is fetched and trusted in the container only
 *   --ca <file.pem>    the same, with a CA file of your own
 *   --keep             leave the temporary copy of the repository in place
 *
 * Distributions: ubuntu:22.04, ubuntu:24.04, debian:12, fedora:41. It does not test Windows (winget) or macOS (Homebrew).
 * A container has no systemd, so the Docker service is not started by the installer here; --full starts dockerd by hand.
 */
import { spawnSync } from 'node:child_process';
import { cpSync, existsSync, mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { argv, exit } from 'node:process';
import { pathToFileURL } from 'node:url';
import { repoRoot } from '../lib/env-file.mjs';

export const DISTROS = ['ubuntu:22.04', 'ubuntu:24.04', 'debian:12', 'fedora:41'];

export function parseArgs(args) {
  const value = (flag) => (args.includes(flag) ? args[args.indexOf(flag) + 1] : undefined);
  const options = {
    distro: value('--distro') ?? 'ubuntu:24.04',
    asUser: args.includes('--user'),
    full: args.includes('--full'),
    detectCa: args.includes('--detect-ca'),
    ca: value('--ca'),
    keep: args.includes('--keep'),
  };
  if (!DISTROS.includes(options.distro)) {
    throw new Error(`unknown distribution ${options.distro} (use one of ${DISTROS.join(', ')})`);
  }
  if (options.full && options.asUser) {
    throw new Error('--full runs a Docker daemon, which needs root: it cannot be combined with --user');
  }
  return options;
}

/** The arguments of `docker run`: what is mounted, and what the full run needs on top (a daemon of its own). */
export function dockerRunArguments(options, { source, harness, caFile }) {
  return [
    'run', '--rm',
    ...(options.full ? ['--privileged', '-v', '/var/lib/docker'] : []),
    '-v', `${source}:/src:ro`,
    '-v', `${harness}:/harness:ro`,
    ...(caFile ? ['-v', `${caFile}:/corp-ca.crt:ro`] : []),
    '-e', `AS_USER=${options.asUser ? 1 : 0}`,
    '-e', `FULL=${options.full ? 1 : 0}`,
    options.distro,
    'bash', '/harness/entry.sh',
  ];
}

// No dollar-brace anywhere in the bash below: it is a JavaScript template.

/** As root: the CA of a proxy that inspects TLS, then (for --user) a person's account with sudo, then the steps. */
export const ENTRY_SCRIPT = String.raw`#!/usr/bin/env bash
if [ -f /corp-ca.crt ]; then
  if command -v update-ca-trust >/dev/null 2>&1; then
    mkdir -p /etc/pki/ca-trust/source/anchors && cp /corp-ca.crt /etc/pki/ca-trust/source/anchors/ && update-ca-trust
  else
    mkdir -p /usr/local/share/ca-certificates && cp /corp-ca.crt /usr/local/share/ca-certificates/corp-ca.crt
  fi
fi
if [ "$AS_USER" = 1 ]; then
  if command -v apt-get >/dev/null 2>&1; then
    apt-get update -qq >/dev/null 2>&1; apt-get install -y -qq sudo ca-certificates >/dev/null 2>&1
    command -v update-ca-certificates >/dev/null 2>&1 && update-ca-certificates >/dev/null 2>&1
  else
    dnf install -y -q sudo util-linux >/dev/null 2>&1
  fi
  useradd -m -s /bin/bash dev
  echo 'dev ALL=(ALL) NOPASSWD:ALL' > /etc/sudoers.d/dev
  echo "$FULL" > /tmp/full
  exec su - dev -c "FULL=0 bash /harness/steps.sh"
fi
exec bash /harness/steps.sh
`;

/** What a person does on a bare system, with the result of each step checked. Exit code 0 only when all of them were as expected. */
export const STEPS_SCRIPT = String.raw`#!/usr/bin/env bash
failed=0
expect() { if [ "$1" != "$2" ]; then echo "UNEXPECTED: $3 (got $1, wanted $2)"; failed=1; fi; }

cp -r /src "$HOME/msaas" && cd "$HOME/msaas" || exit 99
echo "### system: $(. /etc/os-release && echo "$PRETTY_NAME"), user $(id -un), tools already there: $(for t in git curl sudo tar gzip java node docker; do command -v $t >/dev/null 2>&1 && printf '%s ' $t; done)"

echo; echo "### 1. --check on a bare system: not ready, and nothing is changed"
bash scripts/env/install-prerequisites.sh --check; expect $? 1 "--check"

echo; echo "### 2. the installer"
bash scripts/env/install-prerequisites.sh --yes; rc=$?
if [ "$(id -u)" = 0 ]; then expect $rc 0 "installer as root"; else expect $rc 10 "installer as a user (the docker group needs a new login)"; fi

echo; echo "### 3. in a new login shell"
[ -f "$HOME/.profile" ] && . "$HOME/.profile"
java -version 2>&1 | head -n 1; javac -version 2>&1; node -v; npm -v; docker --version
java -version 2>&1 | grep -q 'version "21\.'; expect $? 0 "java is 21"
command -v javac >/dev/null 2>&1; expect $? 0 "a JDK, not only a JRE (the build compiles)"
node -v | grep -qE '^v(2[2-9]|20\.(1[1-9]|[2-9][0-9]))'; expect $? 0 "node is 20.11 or newer"
command -v docker >/dev/null 2>&1; expect $? 0 "docker is installed"

echo; echo "### 4. the installer again: nothing left to install"
bash scripts/env/install-prerequisites.sh --yes >/tmp/second.log 2>&1; rc=$?
if [ "$(id -u)" = 0 ]; then expect $rc 0 "second run as root"; elif [ $rc != 0 ] && [ $rc != 10 ]; then expect $rc "0 or 10" "second run as a user"; fi
grep -q 'apt-get install\|dnf install\|get.docker.com' /tmp/second.log && { echo "UNEXPECTED: the second run installed something"; failed=1; }

echo; echo "### 5. --check: ready"
bash scripts/env/install-prerequisites.sh --check; expect $? 0 "--check after the install"

if [ "$FULL" = 1 ]; then
  echo; echo "### 6. a Docker daemon (a container has no systemd)"
  nohup dockerd > /tmp/dockerd.log 2>&1 &
  for i in $(seq 1 60); do docker info >/dev/null 2>&1 && break; sleep 2; done
  docker info --format 'daemon {{.ServerVersion}}, storage {{.Driver}}' || { echo "UNEXPECTED: the daemon did not start"; tail -n 20 /tmp/dockerd.log; exit 98; }

  echo; echo "### 7. the one command of the repository"
  node scripts/env/setup.mjs --no-ui; expect $? 0 "setup.mjs"

  echo; echo "### 8. what a person sees"
  healthy=$(docker ps --filter health=healthy --format '{{.Names}}' | wc -l)
  echo "healthy containers: $healthy"; expect "$healthy" 10 "healthy containers"
  for who in alice bob carol erin frank; do
    answer=$(curl -s -X POST http://localhost:8080/auth/login -H 'Content-Type: application/json' -d "{\"email\":\"$who@demo.test\",\"password\":\"Demo-Passphrase-2026\"}")
    echo "$who -> $(echo "$answer" | head -c 70)"
    case "$who" in
      alice) echo "$answer" | grep -q loginSessionId; expect $? 0 "alice chooses an organization" ;;
      frank) echo "$answer" | grep -q identityToken; expect $? 0 "frank has no organization" ;;
      *) echo "$answer" | grep -q accessToken; expect $? 0 "$who gets a token" ;;
    esac
  done
fi

echo
[ $failed = 0 ] && echo "RESULT: PASS" || echo "RESULT: FAIL"
exit $failed
`;

/** The working tree as a person would have it after cloning, plus what is new and not ignored: no build output, no dependencies. */
function snapshot(directory) {
  const listing = spawnSync('git', ['ls-files', '-z', '--cached', '--others', '--exclude-standard'], { cwd: repoRoot, encoding: 'buffer', maxBuffer: 64 * 1024 * 1024 });
  if (listing.status !== 0) {
    throw new Error('git ls-files failed: is this a git repository?');
  }
  let copied = 0;
  for (const path of listing.stdout.toString('utf8').split('\0').filter(Boolean)) {
    const from = join(repoRoot, path);
    if (existsSync(from)) {
      mkdirSync(dirname(join(directory, path)), { recursive: true });
      cpSync(from, join(directory, path));
      copied++;
    }
  }
  return copied;
}

/** The CA a TLS-inspecting proxy signs with, taken from the certificate chain a container sees; nothing when the chain is the public one. */
function detectProxyCa() {
  const probe = [
    'apt-get update -qq >/dev/null 2>&1; apt-get install -y -qq curl openssl ca-certificates >/dev/null 2>&1',
    'if curl -fsS -o /dev/null https://get.docker.com 2>/dev/null; then echo CLEAN; else',
    "echo | openssl s_client -showcerts -connect get.docker.com:443 -servername get.docker.com 2>/dev/null | awk '/BEGIN CERT/{n++} n>=2'; fi",
  ].join('\n');
  const result = spawnSync('docker', ['run', '--rm', 'ubuntu:24.04', 'bash', '-c', probe], { encoding: 'utf8' });
  const output = (result.stdout ?? '').trim();
  return output.startsWith('-----BEGIN CERTIFICATE-----') ? `${output}\n` : null;
}

function main() {
  const options = parseArgs(argv.slice(2));
  if (spawnSync('docker', ['info'], { encoding: 'utf8' }).status !== 0) {
    console.error('Docker is not running: node scripts/env/ensure-dev-env.mjs starts it.');
    return 2;
  }

  const work = mkdtempSync(join(tmpdir(), 'msaas-clean-'));
  try {
    const source = join(work, 'src');
    const harness = join(work, 'harness');
    mkdirSync(source);
    mkdirSync(harness);
    console.log(`Copy of the working tree: ${snapshot(source)} files`);
    writeFileSync(join(harness, 'entry.sh'), ENTRY_SCRIPT);
    writeFileSync(join(harness, 'steps.sh'), STEPS_SCRIPT);

    let caFile;
    if (options.ca) {
      caFile = options.ca;
    } else if (options.detectCa) {
      const pem = detectProxyCa();
      console.log(pem ? 'A proxy that inspects TLS was found: its CA is trusted in the container.' : 'No TLS inspection found: nothing extra to trust.');
      if (pem) {
        caFile = join(work, 'corp-ca.crt');
        writeFileSync(caFile, pem);
      }
    }

    const args = dockerRunArguments(options, { source, harness, caFile });
    console.log(`> docker ${args.join(' ')}\n`);
    return spawnSync('docker', args, { stdio: 'inherit' }).status ?? 1;
  } finally {
    if (options.keep) {
      console.log(`Kept: ${work}`);
    } else {
      rmSync(work, { recursive: true, force: true });
    }
  }
}

if (import.meta.url === pathToFileURL(argv[1] ?? '').href) {
  try {
    exit(main());
  } catch (error) {
    console.error(error.message);
    exit(2);
  }
}
