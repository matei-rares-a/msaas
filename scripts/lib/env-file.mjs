import { existsSync, readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

/** Repository root, independent of the working directory the script is started from. */
export const repoRoot = fileURLToPath(new URL('../..', import.meta.url));

/** Secrets and local settings live in .env only; scripts never hardcode them. */
export function readEnvFile(path) {
  if (!existsSync(path)) {
    return {};
  }
  const values = {};
  for (const line of readFileSync(path, 'utf8').split(/\r?\n/)) {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith('#')) {
      continue;
    }
    const separator = trimmed.indexOf('=');
    if (separator > 0) {
      values[trimmed.slice(0, separator).trim()] = trimmed.slice(separator + 1).trim();
    }
  }
  return values;
}
