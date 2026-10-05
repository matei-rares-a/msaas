#!/usr/bin/env node
/**
 * Starts or stops the local infrastructure from docker-compose.yml (Postgres, PgBouncer, Kafka,
 * RabbitMQ, Redis) for manual checks. Secrets come from .env, which docker compose reads itself.
 *
 * A separate compose project and no published host ports by default, so it cannot collide with a
 * locally installed Postgres on 5432. Integration tests do NOT need this: they start their own
 * containers through Testcontainers.
 *
 *   node scripts/infra/local-stack.mjs --up
 *   node scripts/infra/local-stack.mjs --up --with-ports
 *   node scripts/infra/local-stack.mjs --down
 */
import { spawnSync } from 'node:child_process';
import { writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { argv, exit } from 'node:process';
import { repoRoot } from '../lib/env-file.mjs';

const up = argv.includes('--up');
const down = argv.includes('--down');
const withPorts = argv.includes('--with-ports');
const project = 'msaas-local';
const services = ['postgres', 'pgbouncer', 'kafka', 'rabbitmq', 'redis'];

if (up === down) {
  console.log('Use --up or --down.');
  exit(2);
}

const composeArgs = ['compose', '-p', project, '-f', join(repoRoot, 'docker-compose.yml')];

if (down) {
  exit(spawnSync('docker', [...composeArgs, 'down', '-v'], { stdio: 'inherit' }).status ?? 1);
}

if (!withPorts) {
  const override = join(tmpdir(), `${project}-no-ports.yml`);
  const body = ['services:', ...services.flatMap((service) => [`  ${service}:`, '    ports: !reset []'])].join('\n');
  writeFileSync(override, `${body}\n`, 'utf8');
  composeArgs.push('-f', override);
}

exit(spawnSync('docker', [...composeArgs, 'up', '-d', '--wait', ...services], { stdio: 'inherit' }).status ?? 1);
