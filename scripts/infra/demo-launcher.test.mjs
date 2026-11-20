import assert from 'node:assert/strict';
import { spawn, spawnSync } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { createServer } from 'node:net';
import { test } from 'node:test';
import { checkUiPort, requestUiStop, start } from './demo-launcher.mjs';

const windows = { skip: process.platform !== 'win32' };
const endpoint = () => `\\\\.\\pipe\\msaas-launcher-test-${randomUUID()}`;
const child = (code) => spawn(process.execPath, ['-e', code], { stdio: 'ignore' });

test('a port already used by another process prevents startup', async () => {
  const server = createServer();
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  try {
    await assert.rejects(checkUiPort(server.address().port), /already in use/);
  } finally {
    await new Promise((resolve) => server.close(resolve));
  }
});

test('stopping Angular leaves an unrelated process running', windows, async () => {
  const pipe = endpoint();
  let ready;
  const uiReady = new Promise((resolve) => { ready = resolve; });
  const unrelated = child('setInterval(() => {}, 1000)');
  const running = start(pipe, () => child(''), () => {
    const ui = child('setInterval(() => {}, 1000)');
    ready(ui);
    return ui;
  });
  try {
    const ui = await uiReady;
    assert.equal(await requestUiStop(pipe), true);
    await running;
    assert.notEqual(ui.exitCode, null);
    assert.doesNotThrow(() => process.kill(unrelated.pid, 0));
    assert.equal(await requestUiStop(pipe), false);
  } finally {
    spawnSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', `Stop-Process -Id ${unrelated.pid} -ErrorAction Stop`]);
  }
});

test('a stop requested during setup waits and does not start Angular', windows, async () => {
  const pipe = endpoint();
  let ready;
  const setupReady = new Promise((resolve) => { ready = resolve; });
  let uiStarted = false;
  const running = start(pipe, () => {
    const setup = child('setTimeout(() => {}, 500)');
    ready();
    return setup;
  }, () => {
    uiStarted = true;
    return child('');
  });
  await setupReady;
  assert.equal(await requestUiStop(pipe), true);
  await running;
  assert.equal(uiStarted, false);
});

test('a failed setup releases the launcher without starting Angular', windows, async () => {
  const pipe = endpoint();
  let uiStarted = false;
  await assert.rejects(start(pipe, () => child('process.exit(7)'), () => {
    uiStarted = true;
    return child('');
  }), /Setup failed \(7\)/);
  assert.equal(uiStarted, false);
  assert.equal(await requestUiStop(pipe), false);
});
