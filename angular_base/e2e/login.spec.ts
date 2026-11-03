import { expect, test } from '@playwright/test';
import { stubGateway, tokenResponse } from './gateway-stub';

async function logIn(page: import('@playwright/test').Page) {
  await page.goto('/login');
  await page.getByLabel('Email Address').fill('a@test.local');
  await page.getByLabel('Password').fill('a-long-enough-password');
  await page.getByRole('button', { name: 'Log In' }).click();
}

test.describe('REQ-AUTH-01 a user with one organization is signed in at once', () => {
  test('lands on the dashboard holding that organization\'s token', async ({ page }) => {
    const calls = await stubGateway(page, call => {
      if (call.path === '/auth/login') {
        return { body: tokenResponse('tenant-a') };
      }
      return call.path === '/notifications/in-app' ? { body: { items: [], page: 0, size: 20, totalItems: 0, unreadCount: 0 } } : undefined;
    });

    await logIn(page);

    await expect(page).toHaveURL(/\/dashboard/);
    expect(await page.evaluate(() => localStorage.getItem('msaas.activeTenant'))).toBe('tenant-a');
    expect(calls.find(call => call.path === '/auth/login')?.authorization).toBeUndefined();
    expect(calls.find(call => call.path === '/notifications/in-app')?.authorization).toMatch(/^Bearer /);
  });

  test('a wrong password shows the reason and stays on the page', async ({ page }) => {
    await stubGateway(page, () => ({ status: 401, body: { message: 'Invalid credentials' } }));

    await logIn(page);

    await expect(page.getByText('Invalid credentials')).toBeVisible();
    await expect(page).toHaveURL(/\/login/);
  });
});

test.describe('REQ-AUTH-02 a user with several organizations chooses one', () => {
  test('is asked, and the chosen one gets its own token', async ({ page }) => {
    const calls = await stubGateway(page, call => {
      if (call.path === '/auth/login') {
        return { body: { tenants: [{ tenantId: 'aaaaaaaa-1', role: 'ADMIN' }, { tenantId: 'bbbbbbbb-2', role: 'USER' }], loginSessionId: 'opaque' } };
      }
      if (call.path === '/auth/login/tenant') {
        return { body: tokenResponse(call.body.tenantId, 'USER') };
      }
      return call.path === '/organizations' ? { body: [] } : undefined;
    });

    await logIn(page);

    await expect(page).toHaveURL(/\/select-tenant/);
    expect(await page.evaluate(() => localStorage.getItem('msaas.sessions'))).toBeNull();
    await page.getByTestId('tenant-option').nth(1).click();

    await expect(page).toHaveURL(/\/dashboard/);
    expect(calls.find(call => call.path === '/auth/login/tenant')?.body).toEqual({ loginSessionId: 'opaque', tenantId: 'bbbbbbbb-2' });
    expect(await page.evaluate(() => localStorage.getItem('msaas.activeTenant'))).toBe('bbbbbbbb-2');
  });

  test('the choice page cannot be opened without a login that needs it', async ({ page }) => {
    await stubGateway(page, () => undefined);

    await page.goto('/select-tenant');

    await expect(page).toHaveURL(/\/login/);
  });
});

test.describe('REQ-AUTH-03 a user with no organization creates one with the identity token', () => {
  test('creates it, is told to log in again, and the identity token never reaches storage', async ({ page }) => {
    const calls = await stubGateway(page, call => {
      if (call.path === '/auth/login') {
        return { body: { tenants: [], identityToken: 'identity-token' } };
      }
      return call.path === '/organizations' && call.method === 'POST' ? { status: 201, body: { id: 'new-tenant', name: 'Acme' } } : undefined;
    });

    await logIn(page);
    await expect(page).toHaveURL(/\/no-organization/);
    await page.getByLabel('Create an organization').fill('Acme');
    await page.getByRole('button', { name: 'Create organization' }).click();

    await expect(page).toHaveURL(/\/login/);
    await expect(page.getByTestId('login-notice')).toContainText('organization is created');
    const created = calls.find(call => call.path === '/organizations');
    expect(created?.authorization).toBe('Bearer identity-token');
    expect(created?.body).toEqual({ name: 'Acme' });
    expect(await page.evaluate(() => JSON.stringify(localStorage) + JSON.stringify(sessionStorage))).not.toContain('identity-token');
  });

  test('accepts an invitation with the same token', async ({ page }) => {
    const calls = await stubGateway(page, call => {
      if (call.path === '/auth/login') {
        return { body: { tenants: [], identityToken: 'identity-token' } };
      }
      return call.path === '/invitations/accept' ? { body: { tenantId: 'tenant-a', role: 'USER' } } : undefined;
    });

    await logIn(page);
    await page.getByLabel('Accept an invitation').fill('invite-code');
    await page.getByRole('button', { name: 'Accept invitation' }).click();

    await expect(page.getByTestId('login-notice')).toContainText('joined the organization');
    expect(calls.find(call => call.path === '/invitations/accept')?.authorization).toBe('Bearer identity-token');
  });

  test('the page cannot be opened without an identity token', async ({ page }) => {
    await stubGateway(page, () => undefined);

    await page.goto('/no-organization');

    await expect(page).toHaveURL(/\/login/);
  });
});

test.describe('REQ-AUTH-04 each organization keeps its own token', () => {
  test('a page that needs sign-in sends the user to log in and back', async ({ page }) => {
    await stubGateway(page, () => undefined);

    await page.goto('/billing');

    await expect(page).toHaveURL(/\/login\?returnUrl=%2Fbilling/);
  });
});
