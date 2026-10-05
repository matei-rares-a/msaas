#!/usr/bin/env node
/**
 * Makes this machine ready to build and test MSaaS: JDK 21, a running Docker daemon, the container
 * images used by the integration tests, a Chromium binary for the Angular tests, installed frontend
 * dependencies and a complete .env.
 *
 * Idempotent; run it once per working session (Docker does not survive a reboot). It only configures
 * the machine - it never builds or tests. For installing the tools themselves see
 * scripts/env/install-prerequisites.{ps1,sh}.
 *
 *   node scripts/env/ensure-dev-env.mjs
 *   node scripts/env/ensure-dev-env.mjs --check    report only, exit 1 when not ready
 */
import { spawnSync } from 'node:child_process';
import { existsSync, readdirSync } from 'node:fs';
import { createConnection } from 'node:net';
import { join } from 'node:path';
import { argv, env, exit, platform } from 'node:process';
import { readEnvFile, repoRoot } from '../lib/env-file.mjs';

const options = {
  check: argv.includes('--check'),
  skipImages: argv.includes('--skip-images'),
  skipFrontend: argv.includes('--skip-frontend'),
  dockerTimeoutMs: 240_000,
};

// Must stay in sync with SharedContainers (test-support); guarded by DevEnvironmentScriptTest.
const requiredImages = [
  'postgres:15-alpine',
  'confluentinc/cp-kafka:7.6.1',
  'rabbitmq:3.13-management-alpine',
  'redis:7-alpine',
  'edoburu/pgbouncer:v1.24.1-p1',
];

const requiredEnvKeys = [
  'POSTGRES_SUPERUSER',
  'POSTGRES_SUPERUSER_PASSWORD',
  'AUTH_DB_PASSWORD',
  'ORG_DB_PASSWORD',
  'BILLING_DB_PASSWORD',
  'NOTIF_DB_PASSWORD',
  'OUTBOX_RELAY_DB_PASSWORD',
  'RABBITMQ_USER',
  'RABBITMQ_PASSWORD',
  'JWT_SECRET',
];

const problems = [];
const step = (message) => console.log(`\n== ${message}`);
const ok = (message) => console.log(`  OK: ${message}`);
const missing = (message) => {
  problems.push(message);
  console.log(`  MISSING: ${message}`);
};
const note = (message) => console.log(`  ${message}`);

function run(command, args, { silent = true } = {}) {
  const result = spawnSync(command, args, { encoding: 'utf8', shell: false });
  if (result.error || result.status !== 0) {
    return { ok: false, output: `${result.stdout ?? ''}${result.stderr ?? ''}`.trim() };
  }
  if (!silent) {
    console.log(result.stdout);
  }
  return { ok: true, output: (result.stdout ?? '').trim() };
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

/** Persisting an environment variable is the one thing shells do differently. */
function persistEnvVariable(name, value) {
  env[name] = value;
  if (platform === 'win32') {
    const result = run('setx', [name, value]);
    return result.ok
      ? `${name} set for your user (new terminals pick it up)`
      : `could not set ${name}: ${result.output}`;
  }
  return `add to your shell profile: export ${name}="${value}"`;
}

/** A terminal opened before the variable was persisted still has the old environment. */
function persistedEnvVariable(name) {
  if (env[name]) {
    return env[name];
  }
  if (platform !== 'win32') {
    return undefined;
  }
  const result = run('reg', ['query', 'HKCU\\Environment', '/v', name]);
  return result.ok ? result.output.split(/\s{2,}/).pop()?.trim() : undefined;
}

function findJdk21() {
  const roots = [
    join(env.LOCALAPPDATA ?? '', 'jdks'),
    'C:\\Program Files\\Eclipse Adoptium',
    'C:\\Program Files\\Java',
    '/usr/lib/jvm',
    '/Library/Java/JavaVirtualMachines',
    join(env.HOME ?? '', '.sdkman/candidates/java'),
  ].filter((candidate) => candidate && existsSync(candidate));

  const candidates = [env.JAVA_HOME].filter(Boolean);
  for (const root of roots) {
    for (const entry of readdirSync(root, { withFileTypes: true })) {
      if (entry.isDirectory() || entry.isSymbolicLink()) {
        const home = join(root, entry.name);
        candidates.push(home, join(home, 'Contents/Home'));
      }
    }
  }

  for (const home of candidates) {
    const java = join(home, 'bin', platform === 'win32' ? 'java.exe' : 'java');
    if (!existsSync(java)) {
      continue;
    }
    const result = spawnSync(java, ['-version'], { encoding: 'utf8' });
    if (/version "21\./.test(`${result.stdout}${result.stderr}`)) {
      return home;
    }
  }
  return null;
}

function findBrowser() {
  const candidates = {
    win32: [
      'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
      'C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe',
      join(env.LOCALAPPDATA ?? '', 'Google/Chrome/Application/chrome.exe'),
      'C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe',
      'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
    ],
    darwin: [
      '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
      '/Applications/Chromium.app/Contents/MacOS/Chromium',
      '/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge',
    ],
    linux: [
      '/usr/bin/google-chrome',
      '/usr/bin/chromium',
      '/usr/bin/chromium-browser',
      '/usr/bin/microsoft-edge',
      '/snap/bin/chromium',
    ],
  };
  return (candidates[platform] ?? []).find((path) => existsSync(path)) ?? null;
}

function startDockerDesktop() {
  if (platform === 'win32') {
    return run('cmd', ['/c', 'start', '', 'C:\\Program Files\\Docker\\Docker\\Docker Desktop.exe']).ok;
  }
  if (platform === 'darwin') {
    return run('open', ['-a', 'Docker']).ok;
  }
  return run('systemctl', ['--user', 'start', 'docker-desktop']).ok || run('sudo', ['systemctl', 'start', 'docker']).ok;
}

function isPortInUse(port) {
  return new Promise((resolve) => {
    const socket = createConnection({ port, host: '127.0.0.1' });
    const finish = (inUse) => {
      socket.destroy();
      resolve(inUse);
    };
    socket.setTimeout(700);
    socket.on('connect', () => finish(true));
    socket.on('timeout', () => finish(false));
    socket.on('error', () => finish(false));
  });
}

step('JDK 21 (JAVA_HOME)');
const javaHome = persistedEnvVariable('JAVA_HOME');
const jdk21 = findJdk21();
if (!jdk21) {
  missing('no JDK 21 found - run scripts/env/install-prerequisites');
} else if (javaHome === jdk21) {
  ok(`JAVA_HOME = ${jdk21}`);
} else if (options.check) {
  missing(`JAVA_HOME is '${javaHome ?? 'unset'}', expected '${jdk21}'`);
} else {
  ok(persistEnvVariable('JAVA_HOME', jdk21));
}

step('Docker daemon');
let dockerVersion = run('docker', ['info', '--format', '{{.ServerVersion}}']);
if (!dockerVersion.ok && options.check) {
  missing('Docker daemon is not running (integration tests need it)');
} else if (!dockerVersion.ok) {
  console.log('  starting Docker...');
  if (startDockerDesktop()) {
    const deadline = Date.now() + options.dockerTimeoutMs;
    while (!dockerVersion.ok && Date.now() < deadline) {
      await sleep(5000);
      dockerVersion = run('docker', ['info', '--format', '{{.ServerVersion}}']);
    }
  }
  if (!dockerVersion.ok) {
    missing('Docker did not start - start it manually and re-run');
  }
}
if (dockerVersion.ok) {
  ok(`Docker server ${dockerVersion.output}`);
}

step('Container images for the integration tests');
if (options.skipImages) {
  note('skipped (--skip-images)');
} else if (!dockerVersion.ok) {
  note('skipped (Docker is not running)');
} else {
  const local = run('docker', ['images', '--format', '{{.Repository}}:{{.Tag}}']).output.split(/\r?\n/);
  for (const image of requiredImages) {
    if (local.includes(image)) {
      ok(image);
    } else if (options.check) {
      missing(`image not pulled: ${image}`);
    } else {
      let pulled = false;
      for (let attempt = 1; attempt <= 3 && !pulled; attempt++) {
        pulled = run('docker', ['pull', '-q', image]).ok;
        if (!pulled) {
          console.log(`  pull failed (attempt ${attempt}/3), retrying: ${image}`);
        }
      }
      pulled ? ok(`pulled ${image}`) : missing(`could not pull ${image}`);
    }
  }
}

step('Chromium for the Angular tests (CHROME_BIN)');
const browser = findBrowser();
const chromeBin = persistedEnvVariable('CHROME_BIN');
if (!browser) {
  missing('no Chrome, Chromium or Edge found - Angular tests cannot run headless');
} else if (chromeBin && existsSync(chromeBin)) {
  ok(`CHROME_BIN = ${chromeBin}`);
} else if (options.check) {
  missing('CHROME_BIN is not set');
} else {
  ok(persistEnvVariable('CHROME_BIN', browser));
}

step('Frontend dependencies (angular_base/node_modules)');
const frontend = join(repoRoot, 'angular_base');
if (options.skipFrontend) {
  note('skipped (--skip-frontend)');
} else if (existsSync(join(frontend, 'node_modules'))) {
  ok('installed');
} else if (options.check) {
  missing('node_modules missing - run npm ci in angular_base');
} else {
  const npm = platform === 'win32' ? 'npm.cmd' : 'npm';
  const result = spawnSync(npm, ['ci', '--no-audit', '--no-fund'], { cwd: frontend, stdio: 'inherit' });
  result.status === 0 ? ok('npm ci done') : missing('npm ci failed');
}

step('Local configuration (.env)');
const envFile = join(repoRoot, '.env');
if (!existsSync(envFile)) {
  missing('.env is missing - scripts and services read every secret from it');
} else {
  const values = readEnvFile(envFile);
  const absent = requiredEnvKeys.filter((key) => !values[key]);
  absent.length === 0 ? ok(`${requiredEnvKeys.length} keys present`) : missing(`.env is missing: ${absent.join(', ')}`);
}

step('Notes');
if (await isPortInUse(5432)) {
  note('port 5432 is already in use (local Postgres): start the stack with scripts/infra/local-stack.mjs, which publishes no ports');
}
note('integration tests (*IT) need Docker; `mvnw verify -DskipITs` does not');

if (problems.length > 0) {
  console.log(`\nNOT READY - ${problems.length} item(s) need attention`);
  exit(1);
}
console.log('\nREADY');
