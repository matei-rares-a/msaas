#!/usr/bin/env node
/**
 * The full platform on Docker Compose for the end-to-end tests (P12.1): the five services are built into jars, the compose
 * stack is started in its own project (msaas-e2e) and the tests in e2e-tests drive it through the gateway, as a client would.
 * Secrets come from .env, which docker compose reads itself.
 *
 *   node scripts/infra/e2e-stack.mjs --up       build the jars and start the stack (waits until it is healthy)
 *   node scripts/infra/e2e-stack.mjs --test     run the end-to-end tests against a running stack
 *   node scripts/infra/e2e-stack.mjs --down     stop it and delete its volumes
 *   node scripts/infra/e2e-stack.mjs --all      up, test, down (down also when the tests fail)
 *   node scripts/infra/e2e-stack.mjs --up --skip-build   reuse the jars that are already built
 *
 * Only two ports are published, on the loopback interface and never on the usual ones, so a local Postgres on 5432 cannot
 * collide: the gateway (18080), which is what a client talks to, and Postgres (15432), which the tests read for what an
 * email would carry (an invitation code, a password reset code) and to put a plan in the catalog.
 */
import { spawnSync } from 'node:child_process';
import { existsSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { argv, exit, platform } from 'node:process';
import { pathToFileURL } from 'node:url';
import { readEnvFile, repoRoot } from '../lib/env-file.mjs';

export const PROJECT = 'msaas-e2e';
export const GATEWAY_PORT = 18080;
export const POSTGRES_PORT = 15432;

export const SERVICES = ['api-gateway', 'auth-service', 'organization-service', 'billing-service', 'notification-service'];
export const INFRASTRUCTURE = ['postgres', 'pgbouncer', 'kafka', 'rabbitmq', 'redis'];

/**
 * The compose override for the end-to-end run, as YAML text: what is published, and what differs from a deployment. The rate
 * limit is lifted because a test makes more calls per second than a person does; billing's simulated payment provider fails
 * every charge, which is the only way to force PAST_DUE (REQ-BILL-04), and its daily jobs run every 10 seconds. None of the other
 * flows charges.
 */
export function buildOverride() {
  const blocks = new Map([...INFRASTRUCTURE, ...SERVICES].map((name) => [name, ['    ports: !reset []']]));
  blocks.set('postgres', ['    ports: !override', `      - "127.0.0.1:${POSTGRES_PORT}:5432"`]);
  blocks.set('api-gateway', [
    '    ports: !override',
    `      - "127.0.0.1:${GATEWAY_PORT}:8080"`,
    '    environment:',
    '      RATE_LIMIT_USER_PER_SECOND: "100000"',
    '      RATE_LIMIT_TENANT_PER_ACTIVE_USER_PER_SECOND: "100000"'
  ]);
  // the daily jobs run every 10 seconds, so a test that moves a subscription's dates back sees the trial converted and the grace period end
  blocks.set('billing-service', [
    ...blocks.get('billing-service'),
    '    environment:',
    '      SAAS_BILLING_PAYMENT_SIMULATED_FAILURE_MODE: ALWAYS_FAIL',
    '      SAAS_BILLING_TRIAL_CONVERSION_CRON: "*/10 * * * * *"',
    '      SAAS_BILLING_GRACE_PERIOD_CRON: "*/10 * * * * *"'
  ]);
  return `services:\n${[...blocks].map(([name, lines]) => `  ${name}:\n${lines.join('\n')}`).join('\n')}\n`;
}
export function run(command, args, options = {}) {
  console.log(`> ${command} ${args.join(' ')}`);
  return spawnSync(command, args, { stdio: 'inherit', cwd: repoRoot, shell: platform === 'win32' && command.endsWith('.cmd'), ...options }).status ?? 1;
}

function composeArgs() {
  const override = join(tmpdir(), `${PROJECT}-override.yml`);
  writeFileSync(override, buildOverride(), 'utf8');
  return ['compose', '-p', PROJECT, '-f', join(repoRoot, 'docker-compose.yml'), '-f', override];
}

export function maven() {
  return platform === 'win32' ? 'mvnw.cmd' : './mvnw';
}

export function buildJars() {
  // clean and no test compilation: the jars are all that is needed, and a Java language server in an editor may be writing to target/classes
  return run(maven(), ['-B', '-ntp', '-Dmaven.test.skip=true', 'clean', 'package', '-pl', SERVICES.join(','), '-am']);
}

function up(skipBuild) {
  if (!skipBuild) {
    const built = buildJars();
    if (built !== 0) {
      return built;
    }
  }
  return run('docker', [...composeArgs(), 'up', '-d', '--build', '--wait', '--wait-timeout', '600']);
}

function down() {
  return run('docker', [...composeArgs(), 'down', '-v']);
}

function test() {
  const secrets = readEnvFile(join(repoRoot, '.env'));
  return run(maven(), [
    '-B', '-ntp', '-Pe2e', '-pl', 'e2e-tests', '-am', 'verify', '-DskipTests=false',
    `-De2e.gateway=http://localhost:${GATEWAY_PORT}`,
    `-De2e.db.url=jdbc:postgresql://localhost:${POSTGRES_PORT}`,
    `-De2e.db.user=${secrets.POSTGRES_SUPERUSER}`,
    `-De2e.db.password=${secrets.POSTGRES_SUPERUSER_PASSWORD}`,
    '-Dtest=NoSuchTest', '-Dsurefire.failIfNoSpecifiedTests=false'
  ]);
}

if (import.meta.url === pathToFileURL(argv[1] ?? '').href) {
  const has = (flag) => argv.includes(flag);
  const skipBuild = has('--skip-build');
  if (!existsSync(join(repoRoot, '.env'))) {
    console.error('There is no .env with the development secrets (see section 2 of the implementation plan).');
    exit(2);
  }
  if (has('--down')) {
    exit(down());
  } else if (has('--all')) {
    let status = up(skipBuild);
    if (status === 0) {
      status = test();
    }
    down();
    exit(status);
  } else if (has('--up')) {
    exit(up(skipBuild));
  } else if (has('--test')) {
    exit(test());
  } else {
    console.log('Use --up, --test, --down or --all.');
    exit(2);
  }
}
