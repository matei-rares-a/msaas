import { Page } from '@playwright/test';

/** Opens the app as someone already signed in to tenant-a, the way a returning visitor would be. */
export async function signedInAs(page: Page, role: string, readOnly = false) {
  const claims = { sub: 'user-1', tenantId: 'tenant-a', tenantName: 'Acme', displayName: 'a@test.local', role, readOnly };
  const token = [Buffer.from('{"alg":"HS256"}').toString('base64url'), Buffer.from(JSON.stringify(claims)).toString('base64url'), 'sig'].join('.');
  await page.addInitScript(([accessToken]) => {
    localStorage.setItem('msaas.sessions', JSON.stringify({ 'tenant-a': { accessToken, refreshToken: 'r', expiresAt: Date.now() + 600_000 } }));
    localStorage.setItem('msaas.activeTenant', 'tenant-a');
  }, [token]);
}
