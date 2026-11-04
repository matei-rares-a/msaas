#!/usr/bin/env node
/**
 * From a fresh clone to a running platform with demo data and the UI open: the one command for someone who has just cloned the
 * repository and has the tools installed (scripts/env/install-prerequisites.* installs them, and can call this at the end).
 *
 *   node scripts/env/setup.mjs               check the machine, prepare it, build, start the platform, seed it, start the UI
 *   node scripts/env/setup.mjs --check       only report whether the machine is ready; changes nothing, exit 1 when it is not
 *   node scripts/env/setup.mjs --no-ui       leave out the last step (the Angular dev server stays yours to start)
 *   node scripts/env/setup.mjs --skip-build  reuse the jars that are already built (they are built anyway when one is missing)
 *   node scripts/env/setup.mjs --dev         also prepare for running the tests: the container images of the integration tests
 *                                            and a browser for the Angular tests
 *
 * It is safe to run again at any time: every step does nothing when it is already done.
 */
import { spawn, spawnSync } from 'node:child_process';
import { existsSync, statfsSync } from 'node:fs';
import { createConnection } from 'node:net';
import { totalmem } from 'node:os';
import { join } from 'node:path';
import { argv, env, exit, platform, version } from 'node:process';
import { pathToFileURL } from 'node:url';
import { repoRoot } from '../lib/env-file.mjs';
import { findJdk21 } from '../lib/jdk.mjs';

export const DEMO_PROJECT = 'msaas-demo';
export const GATEWAY_PORT = 8080;
export const UI_PORT = 4200;
const GIB = 1024 ** 3;

// ------------------------------------------------------------------------------------------------------------------ the checks

/** Angular 19 runs on Node 20.11+ and 22+ (CI uses 22). */
export function checkNode(nodeVersion) {
  const [major, minor] = nodeVersion.replace(/^v/, '').split('.').map(Number);
  const supported = major >= 22 || (major === 20 && minor >= 11);
  return supported
    ? { status: 'ok', name: 'Node.js', detail: nodeVersion }
    : { status: 'fail', name: 'Node.js', detail: `${nodeVersion} is too old (needs 20.11 or newer, 22 recommended)`, fix: 'install Node.js 22 (scripts/env/install-prerequisites.*)' };
}

/** The ten containers use about 3 GB when idle, and Docker Desktop on Windows and macOS gets only part of the machine. */
export function checkMemory(bytes) {
  const gib = Math.round((bytes / GIB) * 10) / 10;
  if (bytes < 6 * GIB) {
    return { status: 'fail', name: 'Memory', detail: `${gib} GB`, fix: 'the platform needs at least 8 GB of RAM (16 GB recommended)' };
  }
  if (bytes < 12 * GIB) {
    return { status: 'warn', name: 'Memory', detail: `${gib} GB`, fix: 'enough to run, but close other heavy applications; in Docker Desktop give it at least 5 GB' };
  }
  return { status: 'ok', name: 'Memory', detail: `${gib} GB` };
}

/** Container images, jars, node_modules and the Maven repository add up to about 12 GB. */
export function checkDisk(bytes) {
  const gib = Math.round((bytes / GIB) * 10) / 10;
  if (bytes < 8 * GIB) {
    return { status: 'fail', name: 'Free disk space', detail: `${gib} GB`, fix: 'free at least 15 GB on the drive of the repository and of Docker' };
  }
  if (bytes < 15 * GIB) {
    return { status: 'warn', name: 'Free disk space', detail: `${gib} GB`, fix: 'about 15 GB are used in total; it may just fit' };
  }
  return { status: 'ok', name: 'Free disk space', detail: `${gib} GB` };
}

/** Containers with a fixed name of another compose project would make `up` fail with "name already in use". */
export function foreignContainers(listing, project = DEMO_PROJECT) {
  return listing
    .split(/\r?\n/)
    .map((line) => line.trim().split(/\s+/))
    .filter(([name, owner]) => name?.startsWith('saas-') && owner !== project)
    .map(([name, owner]) => ({ name, owner: owner || 'no compose project' }));
}

export function parseFlags(args) {
  const has = (flag) => args.includes(flag);
  return { check: has('--check'), noUi: has('--no-ui'), skipBuild: has('--skip-build'), dev: has('--dev') };
}

// ------------------------------------------------------------------------------------------------------------------ what it does

const flags = parseFlags(argv.slice(2));
const nodeBinary = process.execPath;
const problems = [];

function isPortInUse(port) {
  return new Promise((resolve) => {
    const socket = createConnection({ port, host: '127.0.0.1' });
    const done = (inUse) => {
      socket.destroy();
      resolve(inUse);
    };
    socket.setTimeout(700);
    socket.on('connect', () => done(true));
    socket.on('timeout', () => done(false));
    socket.on('error', () => done(false));
  });
}

/** A registry that does not answer is a warning, not a failure: behind a proxy this check cannot see what Maven and npm can. */
async function reachable(url) {
  try {
    await fetch(url, { method: 'HEAD', signal: AbortSignal.timeout(8000) });
    return true;
  } catch {
    return false;
  }
}

function report(result) {
  const mark = { ok: 'OK     ', warn: 'WARNING', fail: 'FAILED ' }[result.status];
  console.log(`  ${mark} ${result.name}: ${result.detail}${result.fix && result.status !== 'ok' ? `\n          -> ${result.fix}` : ''}`);
  if (result.status === 'fail') {
    problems.push(`${result.name}: ${result.detail}`);
  }
}

function step(message) {
  console.log(`\n== ${message}`);
}

/** The environment the child processes get: the JDK 21 that was found, because JAVA_HOME may have been set only just now. */
function childEnvironment() {
  const jdk = findJdk21(env, platform);
  return jdk ? { ...env, JAVA_HOME: jdk, PATH: `${join(jdk, 'bin')}${platform === 'win32' ? ';' : ':'}${env.PATH ?? env.Path ?? ''}` } : { ...env };
}

function runNode(script, args = []) {
  console.log(`> node ${script} ${args.join(' ')}`.trim());
  return spawnSync(nodeBinary, [join(repoRoot, script), ...args], { stdio: 'inherit', cwd: repoRoot, env: childEnvironment() }).status ?? 1;
}

function dockerOutput(args) {
  const result = spawnSync('docker', args, { encoding: 'utf8', timeout: 30_000 });
  return result.status === 0 ? result.stdout : '';
}

async function preflight() {
  step('Checking the machine');
  report(checkNode(version));
  report(checkMemory(totalmem()));
  let free = Number.POSITIVE_INFINITY;
  try {
    const stats = statfsSync(repoRoot);
    free = stats.bavail * stats.bsize;
  } catch {
    // an old Node or an odd file system: the check is skipped rather than guessed
  }
  report(Number.isFinite(free) ? checkDisk(free) : { status: 'ok', name: 'Free disk space', detail: 'not measured' });

  if (existsSync(join(repoRoot, '.env'))) {
    report({ status: 'ok', name: '.env', detail: 'present' });
  } else {
    report({ status: 'fail', name: '.env', detail: 'missing', fix: 'it is part of the repository: git checkout -- .env' });
  }

  for (const [port, what] of [[GATEWAY_PORT, 'the gateway'], [UI_PORT, 'the web interface']]) {
    const inUse = await isPortInUse(port);
    // after a first run the platform itself is on 8080, which is fine
    const ours = inUse && port === GATEWAY_PORT && dockerOutput(['ps', '--filter', 'name=saas-api-gateway', '--format', '{{.Names}}']).trim() !== '';
    if (inUse && !ours && !(port === UI_PORT && flags.noUi)) {
      report({ status: 'fail', name: `Port ${port}`, detail: `in use, but ${what} needs it`, fix: `stop what listens on ${port} (the application has this address built in)` });
    } else {
      report({ status: 'ok', name: `Port ${port}`, detail: ours ? 'taken by this platform' : 'free' });
    }
  }

  const registries = [['Maven Central', 'https://repo.maven.apache.org/maven2/'], ['npm registry', 'https://registry.npmjs.org/'], ['Docker Hub', 'https://registry-1.docker.io/v2/']];
  const answers = await Promise.all(registries.map(async ([name, url]) => [name, await reachable(url)]));
  const down = answers.filter(([, up]) => !up).map(([name]) => name);
  report(down.length === 0
    ? { status: 'ok', name: 'Internet', detail: 'Maven Central, npm and Docker Hub answer' }
    : { status: 'warn', name: 'Internet', detail: `no answer from ${down.join(', ')}`, fix: 'the first build downloads about 1 GB; behind a proxy, configure it for Maven (.mvn), npm and Docker Desktop' });
}

function ensureMachine() {
  const options = flags.dev ? [] : ['--skip-images', '--skip-browser'];
  return runNode('scripts/env/ensure-dev-env.mjs', [...(flags.check ? ['--check'] : []), ...options]);
}

function checkForeignContainers() {
  const listing = dockerOutput(['ps', '-a', '--filter', 'name=saas-', '--format', '{{.Names}} {{.Label "com.docker.compose.project"}}']);
  const foreign = foreignContainers(listing);
  if (foreign.length > 0) {
    console.log(`  FAILED containers with fixed names that belong to another stack: ${foreign.map((c) => `${c.name} (${c.owner})`).join(', ')}`);
    console.log('          -> stop that stack first (scripts/infra/e2e-stack.mjs --down, or scripts/infra/local-stack.mjs --down); only one runs at a time');
    return false;
  }
  return true;
}

function startUi() {
  const npm = platform === 'win32' ? 'npm.cmd' : 'npm';
  console.log(`\n== The web interface (Ctrl+C stops it; the platform keeps running)\n> ${npm} start`);
  const child = spawn(npm, ['start'], { cwd: join(repoRoot, 'angular_base'), stdio: 'inherit', env: childEnvironment(), shell: platform === 'win32' });
  child.on('exit', (code) => exit(code ?? 0));
}

async function main() {
  console.log(`MSaaS setup${flags.check ? ' (check only)' : ''}`);
  await preflight();
  if (problems.length > 0) {
    console.log(`\nNOT READY - ${problems.length} item(s) to fix first (above)`);
    exit(1);
  }

  step('Tools, Docker and dependencies');
  if (ensureMachine() !== 0) {
    console.log('\nNOT READY - see the lines marked MISSING above. If Docker was only just installed, start Docker Desktop, accept its terms, wait until it says it is running, then run this again.');
    exit(1);
  }
  if (flags.check) {
    console.log('\nREADY to start the platform: node scripts/env/setup.mjs');
    return;
  }

  step('Other stacks');
  if (!checkForeignContainers()) {
    exit(1);
  }

  step('The platform: build, start, demo data');
  const up = runNode('scripts/infra/demo-stack.mjs', ['--up', ...(flags.skipBuild ? ['--skip-build'] : [])]);
  if (up !== 0) {
    console.log('\nThe platform did not come up. See the output above; `docker compose -p msaas-demo logs <service>` shows a service log.');
    exit(1);
  }
  if (runNode('scripts/infra/demo-stack.mjs', ['--seed']) !== 0) {
    console.log('\nThe demo data was not created. Running `node scripts/infra/demo-stack.mjs --seed` again is safe.');
    exit(1);
  }

  console.log(`\nREADY. Platform: http://localhost:${GATEWAY_PORT}   Interface: http://localhost:${UI_PORT}   Guide: TLDR.md`);
  if (flags.noUi) {
    console.log('Start the interface with: cd angular_base && npm start');
    return;
  }
  startUi();
}

if (import.meta.url === pathToFileURL(argv[1] ?? '').href) {
  await main();
}
