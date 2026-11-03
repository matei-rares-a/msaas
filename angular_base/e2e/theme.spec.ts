import { Page, expect, test } from '@playwright/test';
import { stubGateway, tenantToken } from './gateway-stub';

const PNG = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==', 'base64');

const forest = { id: 'forest', name: 'Forest', kind: 'COLOR', definition: { primary: '#15803D', secondary: '#3F3F46', accent: '#EAB308' } };
const ocean = { id: 'ocean', name: 'Ocean', kind: 'GRADIENT', definition: { primary: '#0369A1', secondary: '#134E4A', accent: '#F472B6', gradient: ['#0EA5E9', '#14B8A6'] } };

/** Opens the app as someone already signed in to tenant-a, the way a returning visitor would be. */
async function signedInAs(page: Page, role: string, readOnly = false) {
  const claims = { sub: 'user-1', tenantId: 'tenant-a', tenantName: 'Acme', displayName: 'a@test.local', role, readOnly };
  const token = [Buffer.from('{"alg":"HS256"}').toString('base64url'), Buffer.from(JSON.stringify(claims)).toString('base64url'), 'sig'].join('.');
  await page.addInitScript(([accessToken]) => {
    localStorage.setItem('msaas.sessions', JSON.stringify({ 'tenant-a': { accessToken, refreshToken: 'r', expiresAt: Date.now() + 600_000 } }));
    localStorage.setItem('msaas.activeTenant', 'tenant-a');
  }, [token]);
}

const cssVariable = (page: Page, name: string) =>
  page.evaluate(variable => getComputedStyle(document.documentElement).getPropertyValue(variable).trim(), name);

test.describe('REQ-PERS-06 the theme of the tenant is applied when the app opens', () => {
  test('applies the palette and shows the logo, fetched with the token as a blob', async ({ page }) => {
    await signedInAs(page, 'USER');
    const calls = await stubGateway(page, call => {
      if (call.path === '/organizations/tenant-a/theme') {
        return { body: { paletteId: 'forest', palette: forest, logoUrl: '/organizations/tenant-a/theme/logo?v=1' } };
      }
      if (call.path === '/organizations/tenant-a/theme/logo') {
        return { bytes: PNG, contentType: 'image/png' };
      }
      return call.path === '/organizations' ? { body: [] } : undefined;
    });

    await page.goto('/dashboard');

    await expect.poll(() => cssVariable(page, '--primary-color')).toBe('#15803D');
    const logo = page.getByTestId('app-logo');
    await expect(logo).toBeVisible();
    expect(await logo.getAttribute('src')).toMatch(/^blob:/);
    const logoCall = calls.find(call => call.path === '/organizations/tenant-a/theme/logo');
    expect(logoCall?.authorization).toMatch(/^Bearer /);
  });

  test('shows a hostile palette as no colour at all', async ({ page }) => {
    await signedInAs(page, 'USER');
    await stubGateway(page, call => call.path === '/organizations/tenant-a/theme'
      ? { body: { paletteId: 'x', palette: { id: 'x', name: 'x', kind: 'COLOR', definition: { primary: 'red;background:url(https://evil.example/x)', secondary: '#111111', accent: 'rgb(1,2,3)' } }, logoUrl: null } }
      : call.path === '/organizations' ? { body: [] } : undefined);

    await page.goto('/dashboard');

    await expect.poll(() => cssVariable(page, '--secondary-color')).toBe('#111111');
    expect(await page.evaluate(() => document.documentElement.getAttribute('style'))).not.toContain('evil.example');
    expect(await cssVariable(page, '--primary-color')).toBe('#3b82f6');
  });
});

test.describe('REQ-PERS-01 the ADMIN changes the theme', () => {
  test('picks a palette, sees it applied at once, and uploads a logo as multipart', async ({ page }) => {
    await signedInAs(page, 'ADMIN');
    let current = forest;
    const calls = await stubGateway(page, call => {
      if (call.path === '/organizations/tenant-a/theme' && call.method === 'GET') {
        return { body: { paletteId: current.id, palette: current, logoUrl: null } };
      }
      if (call.path === '/organizations/tenant-a/theme/palettes') {
        return { body: [forest, ocean] };
      }
      if (call.path === '/organizations/tenant-a/theme' && call.method === 'PUT') {
        current = ocean;
        return { body: { paletteId: 'ocean', palette: ocean, logoUrl: null } };
      }
      if (call.path === '/organizations/tenant-a/theme/logo' && call.method === 'PUT') {
        return { body: { logoUrl: '/organizations/tenant-a/theme/logo?v=2' } };
      }
      if (call.path === '/organizations/tenant-a/theme/logo') {
        return { bytes: PNG, contentType: 'image/png' };
      }
      return call.path === '/organizations' ? { body: [] } : undefined;
    });

    await page.goto('/dashboard');
    await page.getByTestId('nav-theme').click();
    await expect(page).toHaveURL(/\/theme/);
    await page.getByTestId('palette-ocean').click();

    await expect(page.getByTestId('theme-message')).toContainText('Ocean');
    await expect.poll(() => cssVariable(page, '--primary-color')).toBe('#0369A1');
    expect(calls.find(call => call.method === 'PUT' && call.path.endsWith('/theme'))?.body).toEqual({ paletteId: 'ocean' });

    await page.getByTestId('logo-input').setInputFiles({ name: 'logo.png', mimeType: 'image/png', buffer: PNG });

    await expect(page.getByTestId('theme-message')).toContainText('logo is updated');
    const upload = calls.find(call => call.method === 'PUT' && call.path.endsWith('/theme/logo'));
    expect(upload?.parts).toEqual(['file']);
    expect(upload?.authorization).toMatch(/^Bearer /);
    expect(await page.getByTestId('logo-preview').getAttribute('src')).toMatch(/^blob:/);
  });

  test('does not upload a file that is not an image the server accepts', async ({ page }) => {
    await signedInAs(page, 'ADMIN');
    const calls = await stubGateway(page, call => call.path === '/organizations/tenant-a/theme'
      ? { body: { paletteId: 'forest', palette: forest, logoUrl: null } }
      : call.path.endsWith('/palettes') ? { body: [forest] } : undefined);

    await page.goto('/theme');
    await page.getByTestId('logo-input').setInputFiles({ name: 'logo.svg', mimeType: 'image/svg+xml', buffer: Buffer.from('<svg/>') });

    await expect(page.getByTestId('theme-error')).toContainText('PNG or a JPG');
    expect(calls.some(call => call.path.endsWith('/theme/logo'))).toBe(false);
  });

  for (const [role, readOnly] of [['MANAGER', false], ['USER', false], ['ADMIN', true]] as const) {
    test(`${role}${readOnly ? ' without write access' : ''} gets no theme editor`, async ({ page }) => {
      await signedInAs(page, role, readOnly);
      await stubGateway(page, call => call.path === '/organizations/tenant-a/theme'
        ? { body: { paletteId: 'forest', palette: forest, logoUrl: null } }
        : call.path === '/organizations' ? { body: [] } : undefined);

      await page.goto('/dashboard');
      await expect(page.getByTestId('tenant-name')).toBeVisible();
      await expect(page.getByTestId('nav-theme')).toHaveCount(0);
      await page.goto('/theme');
      await expect(page).toHaveURL(/\/dashboard/);
    });
  }
});