import { defineConfig } from '@playwright/test';

/**
 * Browser tests of the Angular app against a stubbed gateway (every call to http://localhost:8080 is answered by the
 * test), so they need no backend. The browser is the one already on the machine: CHROME_BIN when set (Chrome, Chromium
 * or Edge), otherwise the installed Google Chrome. The full-stack runs against Docker Compose are P12.1.
 */
const port = 4300;
const chromeBin = process.env['CHROME_BIN'];

export default defineConfig({
  testDir: './e2e',
  fullyParallel: true,
  retries: process.env['CI'] ? 1 : 0,
  reporter: [['list']],
  use: {
    baseURL: `http://localhost:${port}`,
    channel: chromeBin ? undefined : 'chrome',
    launchOptions: chromeBin ? { executablePath: chromeBin } : {},
    trace: 'retain-on-failure'
  },
  webServer: {
    command: `npx ng serve --port ${port}`,
    url: `http://localhost:${port}`,
    reuseExistingServer: !process.env['CI'],
    timeout: 240_000
  }
});
