/**
 * Self-test for e2e-stack.mjs: what the end-to-end stack publishes and changes, so it cannot drift into publishing a database
 * on the usual port, leaving the rate limit on for the tests, or losing the switches that force the billing flows.
 *
 *   node --test scripts/infra/e2e-stack.test.mjs
 */
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';
import { GATEWAY_PORT, POSTGRES_PORT, PROJECT, buildOverride } from './e2e-stack.mjs';

const override = buildOverride();
const compose = readFileSync(fileURLToPath(new URL('../../docker-compose.yml', import.meta.url)), 'utf8');

/** The lines of one service's block in the override. */
function block(name) {
  const lines = override.split('\n');
  const start = lines.indexOf(`  ${name}:`);
  assert.notEqual(start, -1, `${name} is in the override`);
  const rest = lines.slice(start + 1);
  const end = rest.findIndex((line) => /^ {2}\S/.test(line));
  return (end === -1 ? rest : rest.slice(0, end)).join('\n');
}

test('uses a project and ports of its own, so it cannot collide with a local Postgres or the manual stack', () => {
  assert.equal(PROJECT, 'msaas-e2e');
  assert.notEqual(POSTGRES_PORT, 5432);
  assert.notEqual(GATEWAY_PORT, 8080);
});

test('publishes only the gateway and Postgres, and only on the loopback interface', () => {
  assert.match(block('api-gateway'), new RegExp(`"127.0.0.1:${GATEWAY_PORT}:8080"`));
  assert.match(block('postgres'), new RegExp(`"127.0.0.1:${POSTGRES_PORT}:5432"`));
  for (const name of ['pgbouncer', 'kafka', 'rabbitmq', 'redis', 'auth-service', 'organization-service', 'billing-service', 'notification-service']) {
    assert.match(block(name), /ports: !reset \[\]/, `${name} publishes nothing`);
  }
  assert.doesNotMatch(override, /"(?!127\.0\.0\.1)[0-9.:]*\d+:\d+"/);
});

test('lifts the rate limit, which a test would hit long before a person does', () => {
  assert.match(block('api-gateway'), /RATE_LIMIT_USER_PER_SECOND: "100000"/);
  assert.match(block('api-gateway'), /RATE_LIMIT_TENANT_PER_ACTIVE_USER_PER_SECOND: "100000"/);
});

test('makes billing fail every charge and run its daily jobs every few seconds', () => {
  const billing = block('billing-service');
  assert.match(billing, /SAAS_BILLING_PAYMENT_SIMULATED_FAILURE_MODE: ALWAYS_FAIL/);
  assert.match(billing, /SAAS_BILLING_TRIAL_CONVERSION_CRON: "\*\/10 \* \* \* \* \*"/);
  assert.match(billing, /SAAS_BILLING_GRACE_PERIOD_CRON: "\*\/10 \* \* \* \* \*"/);
  assert.equal(override.split('\n  billing-service:').length - 1, 1, 'one block per service, or compose refuses the file');
});

test('every setting the override names is one the services really read', () => {
  const sources = {
    'SAAS_BILLING_PAYMENT_SIMULATED_FAILURE_MODE': 'billing-service/src/main/java/com/saas/billing/payment/SimulatedPaymentGateway.java',
    'SAAS_BILLING_TRIAL_CONVERSION_CRON': 'billing-service/src/main/java/com/saas/billing/config/BillingSchedulingConfig.java',
    'SAAS_BILLING_GRACE_PERIOD_CRON': 'billing-service/src/main/java/com/saas/billing/config/BillingSchedulingConfig.java',
    'RATE_LIMIT_USER_PER_SECOND': 'api-gateway/src/main/resources/application.yml',
    'RATE_LIMIT_TENANT_PER_ACTIVE_USER_PER_SECOND': 'api-gateway/src/main/resources/application.yml'
  };
  for (const [variable, file] of Object.entries(sources)) {
    const text = readFileSync(fileURLToPath(new URL(`../../${file}`, import.meta.url)), 'utf8');
    const dotted = variable.toLowerCase().replace(/_/g, '[-.]');
    assert.match(text.toLowerCase().replace(/_/g, '-'), new RegExp(dotted.replace(/\[-\.\]/g, '[-.]')), `${file} reads ${variable}`);
  }
});

test('the deployment compose file lets every service reach the others by name and runs the outbox relays', () => {
  for (const variable of ['AUTH_SERVICE_URL: http://auth-service:8081', 'ORG_SERVICE_URL: http://organization-service:8082',
    'BILLING_SERVICE_URL: http://billing-service:8083', 'NOTIFICATION_SERVICE_URL: http://notification-service:8084']) {
    assert.ok(compose.includes(variable), `${variable} is in docker-compose.yml`);
  }
  assert.equal(compose.split('SAAS_OUTBOX_RELAY_ENABLED: "true"').length - 1, 4, 'auth, organization, billing and notification publish their events');
});

test('Flyway, which connects to Postgres directly, has a user and a password in every service that sets its URL', () => {
  const urls = compose.split('SPRING_FLYWAY_URL:').length - 1;
  assert.equal(urls, 4);
  assert.equal(compose.split('SPRING_FLYWAY_USER:').length - 1, urls, 'a Flyway user for each URL');
  assert.equal(compose.split('SPRING_FLYWAY_PASSWORD:').length - 1, urls, 'a Flyway password for each URL');
});