/**
 * Self-test for clean-machine.mjs, the proof that the setup path works on a bare system: its options, what it asks Docker to run,
 * that the bash it hands to the container is valid, and that the distributions the CI tries are ones it knows.
 *
 *   node --test scripts/verify/clean-machine.test.mjs
 *
 * The run itself (a container per distribution) is not part of this test: it takes minutes and needs Docker. The CI does it.
 */
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { existsSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';
import { DISTROS, ENTRY_SCRIPT, STEPS_SCRIPT, dockerRunArguments, parseArgs } from './clean-machine.mjs';

const root = new URL('../../', import.meta.url);

test('defaults: Ubuntu 24.04, as root, the installer only', () => {
  assert.deepEqual(parseArgs([]), { distro: 'ubuntu:24.04', asUser: false, full: false, detectCa: false, ca: undefined, keep: false });
});

test('options', () => {
  const options = parseArgs(['--distro', 'debian:12', '--user', '--detect-ca', '--keep', '--ca', '/x/ca.pem']);
  assert.deepEqual(options, { distro: 'debian:12', asUser: true, full: false, detectCa: true, ca: '/x/ca.pem', keep: true });
});

test('an unknown distribution, and a full run as a user, are refused', () => {
  assert.throws(() => parseArgs(['--distro', 'arch:latest']), /unknown distribution/);
  assert.throws(() => parseArgs(['--full', '--user']), /needs root/);
});

test('the container: the source is read only, and a full run gets a daemon of its own', () => {
  const paths = { source: '/tmp/s', harness: '/tmp/h' };
  const plain = dockerRunArguments(parseArgs([]), paths);
  assert.ok(plain.includes('/tmp/s:/src:ro') && plain.includes('/tmp/h:/harness:ro'));
  assert.ok(!plain.includes('--privileged'), 'privileges only for the full run');
  assert.ok(!plain.some((argument) => argument.includes('docker.sock')), 'the host daemon is never mounted');
  assert.deepEqual(plain.slice(-3), ['ubuntu:24.04', 'bash', '/harness/entry.sh']);

  const full = dockerRunArguments(parseArgs(['--full']), { ...paths, caFile: '/tmp/ca.crt' });
  assert.ok(full.includes('--privileged') && full.includes('/var/lib/docker'));
  assert.ok(full.includes('/tmp/ca.crt:/corp-ca.crt:ro'));
  assert.ok(full.includes('FULL=1') && full.includes('AS_USER=0'));
});

test('the bash is valid, has no CR (the container would not run it) and no dollar-brace (it is a JavaScript template)', () => {
  const bash = ['C:\\Program Files\\Git\\bin\\bash.exe', 'bash'].find((candidate) => (candidate === 'bash' || existsSync(candidate))
    && spawnSync(candidate, ['-c', 'echo ok'], { encoding: 'utf8' }).stdout?.trim() === 'ok');
  const directory = mkdtempSync(join(tmpdir(), 'clean-machine-test-'));
  try {
    for (const [name, script] of [['entry.sh', ENTRY_SCRIPT], ['steps.sh', STEPS_SCRIPT]]) {
      assert.ok(!script.includes('\r'), `${name} has a CR`);
      assert.ok(!script.includes('${'), `${name} has a dollar-brace, which JavaScript would have interpolated`);
      if (bash) {
        writeFileSync(join(directory, name), script);
        assert.equal(spawnSync(bash, ['-n', join(directory, name)], { encoding: 'utf8' }).status, 0, `${name} is not valid bash`);
      }
    }
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});

test('the steps check what a person would rely on', () => {
  for (const expectation of ['--check', 'javac', 'second run', 'loginSessionId', 'identityToken', 'RESULT: PASS', 'RESULT: FAIL']) {
    assert.ok(STEPS_SCRIPT.includes(expectation), expectation);
  }
});

test('every distribution the CI tries is one the script knows, and the documents name the same ones', () => {
  const ci = readFileSync(fileURLToPath(new URL('.github/workflows/ci.yml', root)), 'utf8');
  const tried = [...ci.matchAll(/distro: '([^']+)'/g)].map((match) => match[1]);
  assert.ok(tried.length >= 4, 'the CI has an installer job with a matrix');
  for (const distro of tried) {
    assert.ok(DISTROS.includes(distro), distro);
  }
  const prompt = readFileSync(fileURLToPath(new URL('PROMPT_SETUP_TLDR.md', root)), 'utf8');
  assert.ok(prompt.includes('clean-machine.mjs'), 'the prompt points at the harness');
});
