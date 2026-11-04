import { spawnSync } from 'node:child_process';
import { existsSync, readdirSync } from 'node:fs';
import { join } from 'node:path';

/**
 * Where a JDK may live on each platform. The build refuses anything but 21 (maven-enforcer), so the scripts look for a 21
 * explicitly instead of trusting whichever `java` comes first on the PATH.
 */
function jdkRoots(env) {
  return [
    join(env.LOCALAPPDATA ?? '', 'jdks'),
    'C:\\Program Files\\Eclipse Adoptium',
    'C:\\Program Files\\Java',
    'C:\\Program Files\\Microsoft',
    '/usr/lib/jvm',
    '/Library/Java/JavaVirtualMachines',
    join(env.HOME ?? '', '.sdkman/candidates/java'),
    join(env.HOME ?? '', '.local/jdks'),
  ].filter((candidate) => candidate && existsSync(candidate));
}

/** Homebrew's openjdk@21 is keg-only: it is not in any of the usual places until linked. */
const BREW_KEGS = ['/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home', '/usr/local/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home'];

/** @returns the home of a JDK 21 (what JAVA_HOME should be), or null. */
export function findJdk21(env, platform) {
  const candidates = [env.JAVA_HOME, ...BREW_KEGS].filter(Boolean);
  for (const root of jdkRoots(env)) {
    for (const entry of readdirSync(root, { withFileTypes: true })) {
      if (entry.isDirectory() || entry.isSymbolicLink()) {
        const home = join(root, entry.name);
        candidates.push(home, join(home, 'Contents/Home'));
      }
    }
  }

  for (const home of candidates) {
    const java = join(home, 'bin', platform === 'win32' ? 'java.exe' : 'java');
    if (!existsSync(java)) {
      continue;
    }
    const result = spawnSync(java, ['-version'], { encoding: 'utf8' });
    if (/version "21\./.test(`${result.stdout}${result.stderr}`)) {
      return home;
    }
  }
  return null;
}
