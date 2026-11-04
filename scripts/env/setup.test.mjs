/**
 * Self-test for the scripts that set a machine up (setup.mjs, install-prerequisites.ps1 / .sh): the checks that decide whether a
 * machine can run the platform, what the Linux and macOS installer would run on each kind of system (through its --dry-run), and
 * that the versions the scripts install are the ones the build and the CI use.
 *
 *   node --test scripts/env/setup.test.mjs
 *
 * The installers themselves have not been run on a machine without the tools: what is covered here is their logic, not winget,
 * apt, dnf or brew.
 */
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { existsSync, readFileSync } from 'node:fs';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';
import { checkDisk, checkMemory, checkNode, foreignContainers, parseFlags } from './setup.mjs';

const root = new URL('../../', import.meta.url);
const read = (relative) => readFileSync(fileURLToPath(new URL(relative, root)), 'utf8');
const GIB = 1024 ** 3;

// ---------------------------------------------------------------------------------------------------------------- the checks

test('Node.js: 20.11 and newer pass, anything older fails', () => {
  for (const version of ['v22.18.0', 'v22.0.0', 'v24.1.0', 'v20.11.0', 'v20.19.5']) {
    assert.equal(checkNode(version).status, 'ok', version);
  }
  for (const version of ['v18.19.1', 'v20.10.0', 'v20.5.0', 'v16.20.2']) {
    assert.equal(checkNode(version).status, 'fail', version);
  }
});

test('memory: under 6 GB fails, under 12 GB warns', () => {
  assert.equal(checkMemory(4 * GIB).status, 'fail');
  assert.equal(checkMemory(8 * GIB).status, 'warn');
  assert.equal(checkMemory(16 * GIB).status, 'ok');
});

test('disk: under 8 GB fails, under 15 GB warns', () => {
  assert.equal(checkDisk(5 * GIB).status, 'fail');
  assert.equal(checkDisk(10 * GIB).status, 'warn');
  assert.equal(checkDisk(50 * GIB).status, 'ok');
});

test('only containers of another compose project are in the way', () => {
  const listing = ['saas-postgres msaas-demo', 'saas-kafka msaas-e2e', 'saas-redis', 'unrelated-container other', ''].join('\n');
  assert.deepEqual(foreignContainers(listing), [{ name: 'saas-kafka', owner: 'msaas-e2e' }, { name: 'saas-redis', owner: 'no compose project' }]);
  assert.deepEqual(foreignContainers('saas-postgres msaas-demo\nsaas-kafka msaas-demo'), []);
});

test('the options of setup.mjs', () => {
  assert.deepEqual(parseFlags([]), { check: false, noUi: false, skipBuild: false, dev: false });
  assert.deepEqual(parseFlags(['--check', '--no-ui', '--skip-build', '--dev']), { check: true, noUi: true, skipBuild: true, dev: true });
});

// ------------------------------------------------------------------------------------- the Linux / macOS installer, dry-run

function findBash() {
  const candidates = process.platform === 'win32' ? ['C:\\Program Files\\Git\\bin\\bash.exe', 'C:\\Program Files\\Git\\usr\\bin\\bash.exe'] : ['bash'];
  return candidates.find((candidate) => (candidate === 'bash' || existsSync(candidate)) && spawnSync(candidate, ['-c', 'echo ok'], { encoding: 'utf8' }).stdout?.trim() === 'ok');
}

const bash = findBash();

/** Runs the installer with a pretended system: its operating system, package manager and which tools are missing. */
function installer(args, { os = 'Linux', pm = 'apt', missing = 'docker,java,node' } = {}) {
  const result = spawnSync(bash, ['scripts/env/install-prerequisites.sh', ...args], {
    cwd: fileURLToPath(root),
    encoding: 'utf8',
    env: { ...process.env, MSAAS_TEST_OS: os, MSAAS_TEST_PM: pm, MSAAS_TEST_MISSING: missing },
  });
  return { status: result.status, output: `${result.stdout}${result.stderr}` };
}

const withBash = { skip: bash ? false : 'no bash on this machine' };

test('the installer is valid bash and has no carriage returns', withBash, () => {
  assert.equal(spawnSync(bash, ['-n', 'scripts/env/install-prerequisites.sh'], { cwd: fileURLToPath(root) }).status, 0);
  assert.ok(!read('scripts/env/install-prerequisites.sh').includes('\r'), 'a CR in a bash script breaks it on Linux');
});

test('Debian and Ubuntu: Docker from its official script, JDK 21 and Node from packages and the official build', withBash, () => {
  const { status, output } = installer(['--dry-run', '--yes', '--setup']);
  assert.match(output, /\+ curl -fsSL https:\/\/get\.docker\.com -o \/tmp\/get-docker\.sh/);
  assert.match(output, /\+ sudo apt-get update/);
  assert.match(output, /\+ sudo apt-get install -y openjdk-21-jdk/);
  assert.match(output, /SHASUMS256\.txt/);
  assert.doesNotMatch(output, /docker-compose-plugin|apt-get install -y .*nodejs/, 'those packages are missing or too old on current distributions');
  assert.equal(status, 10, 'a docker group change needs a new login before anything continues');
});

test('Fedora and the like', withBash, () => {
  const { output } = installer(['--dry-run', '--yes'], { pm: 'dnf' });
  assert.match(output, /\+ sudo dnf install -y java-21-openjdk-devel/);
  assert.doesNotMatch(output, /apt-get/);
});

test('macOS: Docker Desktop as a cask, no sudo, and a first start of Docker is asked for', withBash, () => {
  const { status, output } = installer(['--dry-run', '--yes', '--setup'], { os: 'Darwin', pm: 'brew' });
  assert.match(output, /\+ brew install --cask docker/);
  assert.match(output, /\+ brew install openjdk@21/);
  assert.match(output, /\+ brew install node/);
  assert.doesNotMatch(output, /sudo/);
  assert.match(output, /open -a Docker/);
  assert.equal(status, 10);
});

test('a system with no package manager gets a clear message, not a half install', withBash, () => {
  const { status, output } = installer(['--dry-run', '--yes'], { pm: 'none' });
  assert.equal(status, 1);
  assert.match(output, /no supported package manager/);
  assert.doesNotMatch(output, /^\+ /m);
});

test('Windows is sent to the PowerShell script', withBash, () => {
  const { status, output } = installer(['--dry-run'], { os: 'MINGW64_NT' });
  assert.equal(status, 1);
  assert.match(output, /install-prerequisites\.ps1/);
});

test('--check changes nothing and says what is missing', withBash, () => {
  const { status, output } = installer(['--check']);
  assert.equal(status, 1);
  assert.match(output, /Something is missing/);
  assert.doesNotMatch(output, /^\+ /m);
});

// ---------------------------------------------------------------------------------------- the versions, and the Windows script

test('the Windows script is ASCII only, because Windows PowerShell 5.1 reads a UTF-8 file without a BOM as ANSI', () => {
  const bytes = readFileSync(fileURLToPath(new URL('scripts/env/install-prerequisites.ps1', root)));
  assert.ok(bytes.every((byte) => byte < 128), 'a non-ASCII character in install-prerequisites.ps1');
});

test('the installers install the versions that the CI and the build use', () => {
  const ci = read('.github/workflows/ci.yml');
  const javaVersions = new Set([...ci.matchAll(/java-version: '(\d+)'/g)].map((match) => match[1]));
  const nodeVersions = new Set([...ci.matchAll(/node-version: '(\d+)'/g)].map((match) => match[1]));
  assert.deepEqual([...javaVersions], ['21']);
  assert.deepEqual([...nodeVersions], ['22']);
  assert.match(read('pom.xml'), /<version>\[21,22\)<\/version>/, 'the build accepts only Java 21');

  const windows = read('scripts/env/install-prerequisites.ps1');
  assert.ok(windows.includes('EclipseAdoptium.Temurin.21.JDK') && windows.includes('OpenJS.NodeJS.22') && windows.includes('Docker.DockerDesktop'));
  const unix = read('scripts/env/install-prerequisites.sh');
  assert.ok(unix.includes('NODE_MAJOR=22') && unix.includes('openjdk-21-jdk') && unix.includes('java-21-openjdk-devel') && unix.includes('openjdk@21'));
});

test('Maven is not installed by anyone: mvnw brings its own', () => {
  assert.ok(existsSync(fileURLToPath(new URL('mvnw', root))));
  assert.doesNotMatch(read('scripts/env/install-prerequisites.ps1'), /Apache\.Maven/);
  assert.doesNotMatch(read('scripts/env/install-prerequisites.sh'), /apt-get install -y [^\n]*maven|brew install [^\n]*maven/);
});

test('TLDR.md gives the one command for each system', () => {
  const tldr = read('TLDR.md');
  assert.ok(tldr.includes('install-prerequisites.ps1 -Setup'));
  assert.ok(tldr.includes('install-prerequisites.sh --setup'));
  assert.ok(tldr.includes('node scripts/env/setup.mjs'));
});
