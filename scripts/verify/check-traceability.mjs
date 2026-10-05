#!/usr/bin/env node
/**
 * Traceability check: every REQ mapped to a checked step in IMPLEMENTATION_PLAN.md must be covered by
 * at least one test tagged with its ID, and every REQ tag used in tests must exist in the catalog.
 *
 *   Java tests:     @Tag("REQ-XXX-NN")
 *   Frontend tests: describe('REQ-XXX-NN ...')
 *
 *   node scripts/verify/check-traceability.mjs
 *   node scripts/verify/check-traceability.mjs --plan <plan.md> --root <dir>
 */
import { readdirSync, readFileSync, existsSync } from 'node:fs';
import { basename, join, sep } from 'node:path';
import { argv, exit } from 'node:process';
import { repoRoot } from '../lib/env-file.mjs';

const CATALOG_ROW = /^\|\s*(REQ-[A-Z]+-\d+)\s*\|[^|]*\|\s*([^|]*)\|\s*$/;
const CHECKED_STEP = /^\s*-\s*\[[xX]\]\s*\*\*(P\d+\.\d+)/;
const STEP_ID = /P\d+\.\d+/g;
const TAG_PATTERNS = [/@Tag\(\s*"(REQ-[A-Z]+-\d+)"\s*\)/g, /describe\(\s*['"`](REQ-[A-Z]+-\d+)/g];
const SKIPPED_DIRECTORIES = new Set(['node_modules', 'target', 'dist', '.git', '.angular']);

function option(name, fallback) {
  const index = argv.indexOf(`--${name}`);
  return index >= 0 && argv[index + 1] ? argv[index + 1] : fallback;
}

export function runCheck({ planPath, root, log = console.log }) {
  if (!existsSync(planPath)) {
    log(`ERROR: plan not found: ${planPath}`);
    return 2;
  }

  const planLines = readFileSync(planPath, 'utf8').split(/\r?\n/);
  const catalog = new Map();
  const checkedSteps = new Set();
  for (const line of planLines) {
    const row = CATALOG_ROW.exec(line);
    if (row) {
      catalog.set(row[1], row[2].match(STEP_ID) ?? []);
    }
    const step = CHECKED_STEP.exec(line);
    if (step) {
      checkedSteps.add(step[1]);
    }
  }
  if (catalog.size === 0) {
    log(`ERROR: no REQ catalog rows found in ${planPath}`);
    return 2;
  }

  const tagged = new Map();
  for (const file of collectTestFiles(root)) {
    const content = readFileSync(file, 'utf8');
    for (const pattern of TAG_PATTERNS) {
      for (const match of content.matchAll(pattern)) {
        const files = tagged.get(match[1]) ?? new Set();
        files.add(basename(file));
        tagged.set(match[1], files);
      }
    }
  }

  const due = [...catalog.keys()].filter((id) => catalog.get(id).some((step) => checkedSteps.has(step)));
  const missing = due.filter((id) => !tagged.has(id));
  const unknown = [...tagged.keys()].filter((id) => !catalog.has(id)).sort();
  const covered = [...tagged.keys()].filter((id) => catalog.has(id)).length;

  log(`Catalog: ${catalog.size} REQ | checked steps: ${checkedSteps.size} | REQ due: ${due.length} | REQ with tests: ${covered}`);
  for (const id of missing) {
    log(`MISSING TEST: ${id} (steps: ${catalog.get(id).join(', ')})`);
  }
  for (const id of unknown) {
    log(`UNKNOWN TAG: ${id} used in ${[...tagged.get(id)].join(', ')}`);
  }

  if (missing.length > 0 || unknown.length > 0) {
    log('Traceability check FAILED');
    return 1;
  }
  log('Traceability check OK');
  return 0;
}

function collectTestFiles(root) {
  const files = [];
  const walk = (directory) => {
    for (const entry of readdirSync(directory, { withFileTypes: true })) {
      const path = join(directory, entry.name);
      if (entry.isDirectory()) {
        if (!SKIPPED_DIRECTORIES.has(entry.name)) {
          walk(path);
        }
      } else if (entry.name.endsWith('.spec.ts')) {
        files.push(path);
      } else if (entry.name.endsWith('.java') && path.includes(`${sep}src${sep}test${sep}`)) {
        files.push(path);
      }
    }
  };
  walk(root);
  return files;
}

if (import.meta.url === `file://${process.argv[1]}` || process.argv[1]?.endsWith('check-traceability.mjs')) {
  exit(runCheck({
    planPath: option('plan', join(repoRoot, 'IMPLEMENTATION_PLAN.md')),
    root: option('root', repoRoot),
  }));
}
