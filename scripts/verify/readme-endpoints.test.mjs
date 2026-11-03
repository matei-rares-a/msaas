/**
 * README.md names endpoints; an endpoint that was renamed or removed must not stay in it (P12.2). Every path written in backticks
 * in the README has to exist in the OpenAPI snapshot of one of the services (the snapshots are themselves checked against the
 * running services by the API compatibility tests). A path starting with "..." is a fragment of an organization path.
 *
 *   node --test scripts/verify/readme-endpoints.test.mjs
 */
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';

const root = new URL('../../', import.meta.url);
const read = (relative) => readFileSync(fileURLToPath(new URL(relative, root)), 'utf8');

const SERVICES = ['auth-service', 'organization-service', 'billing-service', 'notification-service'];
// served by something other than the application's REST controllers
const NOT_IN_OPENAPI = new Set(['/swagger-ui.html', '/actuator/health', '/notifications/graphql']);

const operations = SERVICES.flatMap((service) => {
  const snapshot = JSON.parse(read(`${service}/src/test/resources/api-snapshots/openapi.json`));
  return Object.entries(snapshot.paths).flatMap(([path, methods]) => Object.keys(methods).map((method) => ({ method: method.toUpperCase(), path })));
});

const mentioned = [...read('README.md').matchAll(/`(?:(GET|POST|PUT|PATCH|DELETE) )?(\.\.\.)?(\/[^\s`*]*)`/g)]
  .map(([, method, fragment, path]) => ({ method, fragment: Boolean(fragment), path }))
  .filter(({ path }) => !NOT_IN_OPENAPI.has(path));

test('the README mentions endpoints', () => {
  assert.ok(mentioned.length >= 20, `found ${mentioned.length}`);
});

test('every endpoint the README names exists in the OpenAPI snapshots', () => {
  const missing = mentioned.filter(({ method, fragment, path }) => !operations.some((operation) =>
    (method === undefined || operation.method === method) && (fragment ? operation.path.includes(path) : operation.path === path)));
  assert.deepEqual(missing.map(({ method, fragment, path }) => `${method ?? ''} ${fragment ? '...' : ''}${path}`.trim()), []);
});
