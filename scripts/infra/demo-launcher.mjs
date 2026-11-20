#!/usr/bin/env node
import { spawn, spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { createConnection, createServer } from 'node:net';
import { join } from 'node:path';
import { argv, platform } from 'node:process';
import { pathToFileURL } from 'node:url';
import { repoRoot } from '../lib/env-file.mjs';

const pipe = `\\\\.\\pipe\\msaas-demo-${createHash('sha256').update(repoRoot.toLowerCase()).digest('hex').slice(0, 24)}`;

export async function checkUiPort(port = 4200) {
  for (const host of ['127.0.0.1', '::1']) {
    const probe = createServer();
    try {
      await new Promise((resolve, reject) => {
        probe.once('error', reject);
        probe.listen({ host, port, exclusive: true }, resolve);
      });
      await new Promise((resolve, reject) => probe.close((error) => error ? reject(error) : resolve()));
    } catch (error) {
      if (host === '::1' && ['EAFNOSUPPORT', 'EADDRNOTAVAIL'].includes(error.code)) continue;
      if (error.code === 'EADDRINUSE') throw new Error(`Port ${port} is already in use on ${host}; stop its owner before starting Angular.`);
      throw error;
    }
  }
}

function runNode(script, args = [], cwd = repoRoot) {
  return spawn(process.execPath, [script, ...args], { cwd, stdio: 'inherit' });
}

function waitFor(child) {
  return new Promise((resolve, reject) => {
    child.once('error', reject);
    child.once('exit', (code, signal) => resolve({ code, signal }));
  });
}

function stopChild(child) {
  const result = spawnSync('powershell.exe', [
    '-NoProfile', '-NonInteractive', '-Command',
    `Stop-Process -Id ${child.pid} -ErrorAction Stop`
  ], { stdio: 'inherit' });
  if (result.error || result.status !== 0) {
    throw new Error(`Could not stop the Angular process ${child.pid}: ${result.error?.message ?? `exit ${result.status}`}`);
  }
}

export async function start(endpoint = pipe, setup = () => runNode(join(repoRoot, 'scripts', 'env', 'setup.mjs'), ['--no-ui']),
  ui = () => runNode(join(repoRoot, 'angular_base', 'node_modules', '@angular', 'cli', 'bin', 'ng.js'), ['serve'], join(repoRoot, 'angular_base'))) {
  let stopping = false;
  let uiChild;
  const clients = new Set();
  const server = createServer((socket) => {
    socket.setEncoding('utf8');
    let request = '';
    socket.on('data', (data) => {
      request += data;
      if (!request.includes('\n') && request.length <= 5) return;
      if (request !== 'stop\n') {
        socket.end('Invalid request\n');
        return;
      }
      clients.add(socket);
      if (stopping) return;
      stopping = true;
      if (uiChild && uiChild.exitCode === null && uiChild.signalCode === null) {
        try {
          stopChild(uiChild);
        } catch (error) {
          stopping = false;
          clients.delete(socket);
          socket.end(`${error.message}\n`);
        }
      }
    });
    socket.on('error', (error) => console.error(`Stop connection: ${error.message}`));
  });
  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(endpoint, resolve);
  });
  try {
    const result = await waitFor(setup());
    if (result.code !== 0 && !stopping) {
      throw new Error(`Setup failed (${result.signal ?? result.code})`);
    }
    if (!stopping) {
      console.log('\nStarting Angular. Closing this window stops Angular; the demo platform keeps running.');
      uiChild = ui();
      const uiResult = await waitFor(uiChild);
      if (!stopping && uiResult.code !== 0) {
        throw new Error(`Angular stopped (${uiResult.signal ?? uiResult.code})`);
      }
    }
    for (const socket of clients) socket.end('stopped\n');
  } finally {
    for (const socket of clients) {
      if (!socket.writableEnded) socket.end('Startup or Angular failed\n');
    }
    await new Promise((resolve) => server.close(resolve));
  }
}

export function requestUiStop(endpoint = pipe) {
  return new Promise((resolve, reject) => {
    const socket = createConnection(endpoint);
    let response = '';
    socket.setEncoding('utf8');
    socket.on('connect', () => socket.write('stop\n'));
    socket.on('data', (data) => { response += data; });
    socket.on('end', () => response === 'stopped\n' ? resolve(true) : reject(new Error(`Angular stop failed: ${response.trim()}`)));
    socket.on('error', (error) => {
      if (error.code === 'ENOENT' || error.code === 'ECONNREFUSED') resolve(false);
      else reject(error);
    });
  });
}

async function main() {
  if (platform !== 'win32') throw new Error('The batch launcher is for Windows only.');
  if (argv[2] === '--start') {
    await checkUiPort();
    await start();
  } else if (argv[2] === '--stop') {
    console.log('Stopping Angular (waiting for setup to finish if it is still running)...');
    if (!(await requestUiStop())) console.log('Angular was not started by start.bat.');
    const result = spawnSync(process.execPath, [join(repoRoot, 'scripts', 'infra', 'demo-stack.mjs'), '--down'], { cwd: repoRoot, stdio: 'inherit' });
    if (result.error || result.status !== 0) throw new Error(`Demo stack stop failed: ${result.error?.message ?? `exit ${result.status}`}`);
    console.log('Demo platform stopped; data was preserved.');
  } else {
    throw new Error('Use --start or --stop.');
  }
}

if (import.meta.url === pathToFileURL(argv[1] ?? '').href) {
  main().catch((error) => {
    console.error(error.message);
    process.exitCode = 1;
  });
}
