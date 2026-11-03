/**
 * Self-test for demo-stack.mjs: the stack to try by hand must publish only the gateway, on the loopback interface and on the port the
 * Angular app has built in, and the seed must not hold a secret that is not meant to be public.
 *
 *   node --test scripts/infra/demo-stack.test.mjs
 */
import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';
import { DEMO, GATEWAY_PORT, PASSWORD, PROJECT, buildOverride } from './demo-stack.mjs';
import { PROJECT as E2E_PROJECT } from './e2e-stack.mjs';

const root = new URL('../../', import.meta.url);
const override = buildOverride();

test('only the gateway is published, on the loopback interface', () => {
  const published = [...override.matchAll(/^\s+- "([^"]+)"$/gm)].map((match) => match[1]);
  assert.deepEqual(published, [`127.0.0.1:${GATEWAY_PORT}:8080`]);
  assert.ok(override.includes('ports: !reset []'), 'every other service has its ports removed');
});

test('the gateway is on the address the Angular app calls', () => {
  const services = readdirSync(fileURLToPath(new URL('angular_base/src/app/core/services/', root))).filter((file) => file.endsWith('.service.ts'));
  const gateways = services.flatMap((file) => readFileSync(fileURLToPath(new URL(`angular_base/src/app/core/services/${file}`, root)), 'utf8').match(/http:\/\/localhost:\d+/g) ?? []);
  assert.ok(gateways.length > 0);
  assert.deepEqual([...new Set(gateways)], [`http://localhost:${GATEWAY_PORT}`]);
});

test('it is a project of its own, with the rate limits and the payment provider of a deployment', () => {
  assert.notEqual(PROJECT, E2E_PROJECT);
  assert.doesNotMatch(override, /RATE_LIMIT|SIMULATED_FAILURE_MODE|_CRON/);
});

test('the demo password satisfies the password policy and the data fits the plans', () => {
  assert.ok(PASSWORD.length >= 12);
  for (const organization of DEMO.organizations) {
    const plan = DEMO.plans.find((candidate) => candidate.name === organization.plan);
    assert.ok(plan, `${organization.name} has a plan in the catalog`);
    // the administrator, the members and, in the first organization, the pending invitation
    const people = 1 + organization.members.length + (organization === DEMO.organizations[0] ? 1 : 0);
    assert.ok(people <= plan.maxUsers, `${organization.name} fits its plan`);
  }
});

test('the demo covers every role and a user with no organization', () => {
  const roles = new Set(DEMO.organizations.flatMap((organization) => organization.members.map(([, role]) => role)));
  assert.deepEqual([...roles].sort(), ['MANAGER', 'USER']);
  assert.ok(DEMO.invited);
});
