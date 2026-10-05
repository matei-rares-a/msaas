/**
 * Self-test for check-traceability.mjs on a sample plan and sample test files.
 *
 *   node --test scripts/verify/check-traceability.test.mjs
 */
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { test } from 'node:test';
import { runCheck } from './check-traceability.mjs';

const samplePlan = `
| ID | Rezumat | Pas |
|---|---|---|
| REQ-AAA-01 | covered, step checked | P1.1 |
| REQ-AAA-02 | not covered, step checked | P1.1 |
| REQ-BBB-01 | not covered, step not checked | P2.1 |
| REQ-CCC-01 | not covered, one of two steps checked | P2.1, P1.2 |
| REQ-DDD-01 | covered by frontend only | P1.2 (frontend) |

- [x] **P1.1 Checked step**
- [x] **P1.2 Another checked step**
- [ ] **P2.1 Open step**
`;

const allCovered = {
  'svc/src/test/java/ATest.java': '@Tag("REQ-AAA-01") @Tag("REQ-AAA-02") class ATest {}',
  'svc/src/test/java/CTest.java': '@Tags({@Tag("REQ-CCC-01")}) class CTest {}',
  'web/src/app/d.component.spec.ts': "describe('REQ-DDD-01 theme', () => {});",
};

function check(files) {
  const directory = mkdtempSync(join(tmpdir(), 'trace-'));
  try {
    const planPath = join(directory, 'plan.md');
    writeFileSync(planPath, samplePlan);
    for (const [relative, content] of Object.entries(files)) {
      const path = join(directory, relative);
      mkdirSync(dirname(path), { recursive: true });
      writeFileSync(path, content);
    }
    const lines = [];
    const code = runCheck({ planPath, root: directory, log: (line) => lines.push(line) });
    return { code, output: lines.join('\n') };
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
}

test('passes when every due REQ has a test', () => {
  const result = check(allCovered);

  assert.equal(result.code, 0);
  assert.match(result.output, /Traceability check OK/);
  assert.doesNotMatch(result.output, /MISSING TEST|REQ-BBB-01/);
});

test('reports a due REQ without a test', () => {
  const result = check({
    'svc/src/test/java/ATest.java': '@Tag("REQ-AAA-01") class ATest {}',
    'svc/src/test/java/CTest.java': '@Tag("REQ-CCC-01") class CTest {}',
    'web/src/x.spec.ts': "describe('REQ-DDD-01', () => {});",
  });

  assert.equal(result.code, 1);
  assert.match(result.output, /MISSING TEST: REQ-AAA-02/);
  assert.doesNotMatch(result.output, /MISSING TEST: REQ-AAA-01|MISSING TEST: REQ-BBB-01/);
});

test('a REQ is due when any of its steps is checked', () => {
  const result = check({
    'svc/src/test/java/ATest.java': '@Tag("REQ-AAA-01") @Tag("REQ-AAA-02") class ATest {}',
    'web/src/x.spec.ts': "describe('REQ-DDD-01', () => {});",
  });

  assert.equal(result.code, 1);
  assert.match(result.output, /MISSING TEST: REQ-CCC-01/);
});

test('reports a tag that is not in the catalog', () => {
  const result = check({ ...allCovered, 'svc/src/test/java/Typo.java': '@Tag("REQ-AAA-99") class Typo {}' });

  assert.equal(result.code, 1);
  assert.match(result.output, /UNKNOWN TAG: REQ-AAA-99/);
  assert.doesNotMatch(result.output, /MISSING TEST/);
});

test('ignores tags outside test sources and build output', () => {
  const result = check({
    'svc/src/test/java/ATest.java': '@Tag("REQ-AAA-01") class ATest {}',
    'svc/src/main/java/Main.java': '@Tag("REQ-AAA-02") class Main {}',
    'svc/target/src/test/java/X.java': '@Tag("REQ-AAA-02") class X {}',
    'web/node_modules/p/a.spec.ts': "describe('REQ-AAA-02', () => {});",
    'svc/src/test/java/CTest.java': '@Tag("REQ-CCC-01") class CTest {}',
    'web/src/x.spec.ts': "describe('REQ-DDD-01', () => {});",
  });

  assert.equal(result.code, 1);
  assert.match(result.output, /MISSING TEST: REQ-AAA-02/);
});

test('fails loudly when the plan is missing', () => {
  const lines = [];
  const code = runCheck({ planPath: join(tmpdir(), 'does-not-exist.md'), root: tmpdir(), log: (line) => lines.push(line) });

  assert.equal(code, 2);
  assert.match(lines.join('\n'), /plan not found/);
});
