#!/usr/bin/env node
/**
 * A platform to try by hand, with data in it: the same compose stack as the end-to-end tests, in its own project (msaas-demo),
 * with the volumes kept between runs and the gateway on the port the Angular app talks to (8080).
 *
 *   node scripts/infra/demo-stack.mjs --up [--skip-build]   build the jars, start the stack, wait until it is healthy
 *   node scripts/infra/demo-stack.mjs --seed                create the demo accounts, organizations and data (safe to repeat)
 *   node scripts/infra/demo-stack.mjs --down [--purge]      stop it; --purge also deletes the data
 *
 * Only the gateway is published, on 127.0.0.1:8080. The container names of the stack are fixed (saas-*), so the E2E stack and
 * local-stack.mjs must be down. The seed goes through the gateway like a client; it reaches into Postgres only for what an email
 * would carry (the invitation codes) and for the plan catalog, which belongs to the platform and has no endpoint.
 */
import { spawnSync } from 'node:child_process';
import { existsSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { argv, exit } from 'node:process';
import { pathToFileURL } from 'node:url';
import { readEnvFile, repoRoot } from '../lib/env-file.mjs';
import { INFRASTRUCTURE, SERVICES, buildJars, run } from './e2e-stack.mjs';

export const PROJECT = 'msaas-demo';
export const GATEWAY_PORT = 8080;
export const PASSWORD = 'Demo-Passphrase-2026';

/** The compose override: nothing is published but the gateway, on the loopback interface. */
export function buildOverride() {
  const blocks = new Map([...INFRASTRUCTURE, ...SERVICES].map((name) => [name, ['    ports: !reset []']]));
  blocks.set('api-gateway', ['    ports: !override', `      - "127.0.0.1:${GATEWAY_PORT}:8080"`]);
  return `services:\n${[...blocks].map(([name, lines]) => `  ${name}:\n${lines.join('\n')}`).join('\n')}\n`;
}

function composeArgs() {
  const override = join(tmpdir(), `${PROJECT}-override.yml`);
  writeFileSync(override, buildOverride(), 'utf8');
  return ['compose', '-p', PROJECT, '-f', join(repoRoot, 'docker-compose.yml'), '-f', override];
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

function down(purge) {
  return run('docker', [...composeArgs(), 'down', ...(purge ? ['-v'] : [])]);
}

// ---------------------------------------------------------------------------------------------------------------- the seed

/** The demo data: who is in which organization with which role. */
export const DEMO = {
  plans: [
    { name: 'Starter', price: '9.99', maxUsers: 5 },
    { name: 'Business', price: '49.00', maxUsers: 50 },
    { name: 'Enterprise', price: '199.00', maxUsers: 500 }
  ],
  organizations: [
    {
      name: 'Acme Corp', plan: 'Business', palette: 'forest', admin: 'alice@demo.test',
      members: [['bob@demo.test', 'MANAGER'], ['carol@demo.test', 'USER'], ['dave@demo.test', 'USER']]
    },
    {
      name: 'Globex', plan: 'Starter', palette: 'sunset', admin: 'erin@demo.test',
      members: [['alice@demo.test', 'USER']]
    }
  ],
  // has an account and no organization, with an invitation to Acme waiting for the code to be entered
  invited: 'frank@demo.test'
};

function psql(database, sql) {
  const secrets = readEnvFile(join(repoRoot, '.env'));
  const result = spawnSync('docker', [...composeArgs(), 'exec', '-T', 'postgres', 'psql', '-U', secrets.POSTGRES_SUPERUSER, '-d', database, '-tAX', '-c', sql],
    { encoding: 'utf8', cwd: repoRoot });
  if (result.status !== 0) {
    throw new Error(`psql on ${database} failed: ${result.stderr || result.stdout}`);
  }
  return result.stdout.trim();
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

async function call(gateway, method, path, { token, body } = {}) {
  for (let attempt = 0; attempt < 5; attempt++) {
    const response = await fetch(`${gateway}${path}`, {
      method,
      headers: { ...(body ? { 'Content-Type': 'application/json' } : {}), ...(token ? { Authorization: `Bearer ${token}` } : {}) },
      body: body ? JSON.stringify(body) : undefined
    });
    if (response.status === 429) {
      await sleep(Number(response.headers.get('Retry-After') ?? 1) * 1000);
      continue;
    }
    const text = await response.text();
    return { status: response.status, json: text ? safeJson(text) : null };
  }
  throw new Error(`${method} ${path}: rate limited`);
}

function safeJson(text) {
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}

function claim(token, name) {
  return JSON.parse(Buffer.from(token.split('.')[1], 'base64url').toString('utf8'))[name];
}

function expect(response, statuses, what) {
  if (!statuses.includes(response.status)) {
    throw new Error(`${what}: ${response.status} ${JSON.stringify(response.json)}`);
  }
  return response;
}

export async function seed(gateway, log = console.log, database = psql) {
  const api = (method, path, options) => call(gateway, method, path, options);

  const login = async (email) => (await api('POST', '/auth/login', { body: { email, password: PASSWORD } })).json;

  /** A token for the organization, whether the user belongs to one or to several; null when not a member. */
  const signIn = async (email, tenantId) => {
    const result = await login(email);
    if (result?.accessToken) {
      return claim(result.accessToken, 'tenantId') === tenantId ? result.accessToken : null;
    }
    if (result?.loginSessionId && result.tenants?.some((tenant) => tenant.tenantId === tenantId)) {
      return expect(await api('POST', '/auth/login/tenant', { body: { loginSessionId: result.loginSessionId, tenantId } }), [200], `choose tenant for ${email}`).json.accessToken;
    }
    return null;
  };

  const register = async (email) => {
    const response = await api('POST', '/auth/register', { body: { email, password: PASSWORD } });
    if (![201, 409].includes(response.status) && !(await login(email))) {
      throw new Error(`register ${email}: ${response.status} ${JSON.stringify(response.json)}`);
    }
  };

  log('plans');
  for (const plan of DEMO.plans) {
    database('saas_global', `INSERT INTO global.plans (id, name, price_eur, max_users) SELECT gen_random_uuid(), '${plan.name}', ${plan.price}, ${plan.maxUsers} WHERE NOT EXISTS (SELECT 1 FROM global.plans WHERE name = '${plan.name}')`);
  }
  const planId = (name) => database('saas_global', `SELECT id FROM global.plans WHERE name = '${name}'`);

  const tenants = {};
  for (const organization of DEMO.organizations) {
    log(`organization ${organization.name}`);
    await register(organization.admin);
    const first = await login(organization.admin);
    let token = first?.accessToken ?? null;
    // someone who is already in several organizations (alice, on a second run) administers the one this entry describes
    const administered = first?.tenants?.find((tenant) => tenant.role === 'ADMIN');
    if (!token && first?.loginSessionId && administered) {
      token = await signIn(organization.admin, administered.tenantId);
    }
    if (!token && first?.identityToken) {
      expect(await api('POST', '/organizations', { token: first.identityToken, body: { name: organization.name } }), [201], `create ${organization.name}`);
      token = (await login(organization.admin)).accessToken;
    }
    if (!token) {
      throw new Error(`${organization.admin} is in several organizations; cannot tell which one is ${organization.name}`);
    }
    const tenantId = claim(token, 'tenantId');
    tenants[organization.name] = { tenantId, token };

    if ((await api('GET', '/billing/subscription', { token })).status === 404) {
      expect(await api('POST', '/billing/subscriptions', { token, body: { planId: planId(organization.plan) } }), [200, 201], 'subscribe');
    }
    // the plan reaches the organization service by an event; until then invitations are refused
    for (let attempt = 0; attempt < 120 && !database('saas_shard_1', `SELECT max_users FROM org.tenants WHERE id = '${tenantId}'`); attempt++) {
      await sleep(1000);
    }
    expect(await api('PUT', `/organizations/${tenantId}/theme`, { token, body: { paletteId: organization.palette } }), [200], 'theme');

    for (const [email, role] of organization.members) {
      await register(email);
      if (await signIn(email, tenantId)) {
        continue;
      }
      const current = await login(email);
      // a person already in another organization accepts with that organization's token; a new one with the identity token
      const acceptor = current?.identityToken ?? (await signIn(email, tenants[DEMO.organizations[0].name].tenantId));
      expect(await api('POST', `/organizations/${tenantId}/invitations`, { token, body: { email, role } }), [201], `invite ${email}`);
      const code = database('saas_shard_1', `SELECT token FROM org.invitations WHERE email = '${email}' AND tenant_id = '${tenantId}' LIMIT 1`);
      expect(await api('POST', '/invitations/accept', { token: acceptor, body: { token: code } }), [200], `accept ${email}`);
    }
  }

  const acme = tenants[DEMO.organizations[0].name];
  const as = async (email) => signIn(email, acme.tenantId);
  const alice = await as('alice@demo.test');
  const bob = await as('bob@demo.test');
  const carol = await as('carol@demo.test');
  const base = `/organizations/${acme.tenantId}`;

  log('modules, leave and lunch roulette (Acme)');
  for (const module of ['LEAVE', 'LUNCH_ROULETTE']) {
    expect(await api('PUT', `${base}/modules/${module}`, { token: alice, body: { enabled: true } }), [200], `module ${module}`);
  }
  const monday = new Date();
  monday.setUTCDate(monday.getUTCDate() + ((8 - monday.getUTCDay()) % 7 || 7) + 7);
  const day = (offset) => new Date(monday.getTime() + offset * 86400000).toISOString().slice(0, 10);
  const leave = async (token, from, to, reason) => api('POST', `${base}/leave/requests`, { token, body: { type: 'ODIHNA', startDate: day(from), endDate: day(to), reason } });
  if ((await api('GET', `${base}/leave/requests`, { token: carol })).json?.items?.length === 0) {
    expect(await leave(carol, 7, 9, 'Family trip'), [201], 'carol leave');
    const approved = expect(await leave(bob, 14, 15, 'Long weekend'), [201], 'bob leave');
    expect(await api('POST', `${base}/leave/requests/${approved.json.id}/approve`, { token: alice, body: { comment: 'Enjoy' } }), [200], 'approve');
  }
  expect(await api('PUT', `${base}/lunch/config`, { token: alice, body: { day: 'FRIDAY', cutoff: '11:00', groupSize: 2 } }), [200], 'lunch config');
  for (const token of [alice, bob, carol]) {
    expect(await api('PUT', `${base}/lunch/rounds/current/enrollment`, { token }), [200], 'lunch enrollment');
  }

  log(`invitation for ${DEMO.invited}`);
  await register(DEMO.invited);
  let code = database('saas_shard_1', `SELECT token FROM org.invitations WHERE email = '${DEMO.invited}' AND tenant_id = '${acme.tenantId}' LIMIT 1`);
  if (!code && (await login(DEMO.invited))?.identityToken) {
    expect(await api('POST', `${base}/invitations`, { token: alice, body: { email: DEMO.invited, role: 'USER' } }), [201], 'invite frank');
    code = database('saas_shard_1', `SELECT token FROM org.invitations WHERE email = '${DEMO.invited}' AND tenant_id = '${acme.tenantId}' LIMIT 1`);
  }
  return { tenants, invitationCode: code };
}

function summary(result) {
  const lines = [
    '',
    `Demo data ready. Password of every account: ${PASSWORD}`,
    '',
    ...DEMO.organizations.flatMap((organization) => [
      `${organization.name} (plan ${organization.plan}, tenant ${result.tenants[organization.name].tenantId})`,
      `  ${organization.admin.padEnd(18)} ADMIN`,
      ...organization.members.map(([email, role]) => `  ${email.padEnd(18)} ${role}`)
    ]),
    '',
    `${DEMO.invited} has an account and no organization. Invitation code to Acme Corp: ${result.invitationCode || '(already accepted)'}`,
    '',
    'alice@demo.test belongs to both organizations: her login offers a choice.',
    'Open the app: cd angular_base && npm start, then http://localhost:4200'
  ];
  console.log(lines.join('\n'));
}

if (import.meta.url === pathToFileURL(argv[1] ?? '').href) {
  const has = (flag) => argv.includes(flag);
  if (!existsSync(join(repoRoot, '.env'))) {
    console.error('There is no .env with the development secrets (see section 2 of the implementation plan).');
    exit(2);
  }
  if (has('--down')) {
    exit(down(has('--purge')));
  } else if (has('--up')) {
    exit(up(has('--skip-build')));
  } else if (has('--seed')) {
    try {
      summary(await seed(`http://localhost:${GATEWAY_PORT}`));
    } catch (error) {
      console.error(`Seed failed: ${error.message}`);
      exit(1);
    }
  } else {
    console.log('Use --up, --seed or --down [--purge].');
    exit(2);
  }
}
