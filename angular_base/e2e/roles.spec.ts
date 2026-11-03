import { Page, expect, test } from '@playwright/test';
import { RecordedCall, stubGateway } from './gateway-stub';
import { signedInAs } from './session';

const subscription = { id: 's1', planId: 'p1', status: 'ACTIVE', startDate: '2026-11-01T00:00:00Z', endDate: null, pastDueSince: null };
const invoice = { id: 'i1', subscriptionId: 's1', amountEur: 9.99, status: 'PAID', issuedAt: '2026-11-01T00:00:00Z', paidAt: '2026-11-01T00:00:00Z' };

function entries(count: number, from = 0) {
  return Array.from({ length: count }, (_, i) => ({
    id: `e${from + i}`, eventType: 'MEMBER_ROLE_CHANGED', actorUserId: 'aaaaaaaa-1111', createdAt: '2026-11-03T10:00:00Z', metadata: '{"newRole":"MANAGER"}'
  }));
}

/** Answers what the pages of this file ask for; `calls` is for the test to look at. */
function backend(call: RecordedCall) {
  switch (call.path) {
    case '/organizations/tenant-a/theme':
      return { body: { paletteId: 'default', palette: null, logoUrl: null } };
    case '/notifications/in-app':
      return { body: { items: [], page: 0, size: 20, totalItems: 0, unreadCount: 0 } };
    case '/billing/subscription':
      return { body: subscription };
    case '/billing/invoices':
      return { body: [invoice] };
    case '/organizations/tenant-a/invitations':
      return call.method === 'POST'
        ? { body: { id: 'inv9', email: call.body.email, role: call.body.role, status: 'PENDING', expiresAt: '2026-11-10T00:00:00Z' } }
        : { body: [{ id: 'inv1', email: 'old@x.com', role: 'USER', status: 'PENDING', expiresAt: '2026-11-10T00:00:00Z' }] };
    case '/notifications/graphql': {
      const page = call.body.variables.page as number;
      return { body: { data: { activityLog: { items: entries(2, page * 2), page, size: 2, totalItems: 4, totalPages: 2 } } } };
    }
    default:
      return undefined;
  }
}

async function open(page: Page, role: string, readOnly: boolean, path: string) {
  await signedInAs(page, role, readOnly);
  const calls = await stubGateway(page, backend);
  await page.goto(path);
  return calls;
}

const links = ['nav-billing', 'nav-invitations', 'nav-journal', 'nav-theme'] as const;

test.describe('REQ-ROLE-05 each role sees only the pages it may open', () => {
  const expected: Record<string, string[]> = {
    ADMIN: ['nav-billing', 'nav-invitations', 'nav-journal', 'nav-theme'],
    MANAGER: ['nav-billing', 'nav-invitations', 'nav-journal'],
    USER: []
  };
  for (const role of ['ADMIN', 'MANAGER', 'USER']) {
    test(`${role}: the bar offers ${expected[role].join(', ') || 'only the basics'}`, async ({ page }) => {
      await open(page, role, false, '/dashboard');
      await expect(page.getByTestId('tenant-name')).toBeVisible();

      for (const link of links) {
        await expect(page.getByTestId(link)).toHaveCount(expected[role].includes(link) ? 1 : 0);
      }
    });
  }

  test('a USER who types the address of a page for staff is sent home and the page never asks for its data', async ({ page }) => {
    const calls = await open(page, 'USER', false, '/billing');

    await expect(page).toHaveURL(/\/dashboard/);
    for (const path of ['/journal', '/invitations']) {
      await page.goto(path);
      await expect(page).toHaveURL(/\/dashboard/);
    }
    expect(calls.some(call => call.path.startsWith('/billing') || call.path === '/notifications/graphql' || call.path.endsWith('/invitations'))).toBe(false);
  });
});

test.describe('REQ-MGR-02 a MANAGER sees billing and cannot act on it', () => {
  test('shows the subscription and the invoices, with no cancel button', async ({ page }) => {
    await open(page, 'MANAGER', false, '/billing');

    await expect(page.getByTestId('subscription-status')).toHaveText('ACTIVE');
    await expect(page.getByTestId('invoice')).toHaveCount(1);
    await expect(page.getByTestId('billing-readonly')).toBeVisible();
    await expect(page.getByTestId('cancel-subscription')).toHaveCount(0);
  });

  test('an ADMIN with write access can cancel, a read-only ADMIN cannot', async ({ page, browser }) => {
    const calls = await open(page, 'ADMIN', false, '/billing');
    await page.route('http://localhost:8080/billing/subscriptions/cancel', route => route.fulfill({
      status: 200, headers: { 'access-control-allow-origin': '*', 'content-type': 'application/json' }, body: JSON.stringify({ ...subscription, status: 'CANCELED' })
    }));
    await page.getByTestId('cancel-subscription').click();
    await expect(page.getByTestId('subscription-status')).toHaveText('CANCELED');
    expect(calls.length).toBeGreaterThan(0);

    const readOnlyPage = await (await browser.newContext()).newPage();
    await open(readOnlyPage, 'ADMIN', true, '/billing');
    await expect(readOnlyPage.getByTestId('subscription-status')).toBeVisible();
    await expect(readOnlyPage.getByTestId('cancel-subscription')).toHaveCount(0);
  });
});

test.describe('REQ-MGR-04 ADMIN and MANAGER read the journal through GraphQL', () => {
  test('lists entries, filters, and pages with the page as a variable', async ({ page }) => {
    const calls = await open(page, 'MANAGER', false, '/journal');
    const queries = () => calls.filter(call => call.path === '/notifications/graphql');

    await expect(page.getByTestId('journal-entry')).toHaveCount(2);
    expect(queries()[0].body.query).toContain('activityLog');
    expect(queries()[0].body.query).not.toContain('mutation');
    expect(queries()[0].authorization).toMatch(/^Bearer /);

    await page.getByTestId('journal-next').click();
    await expect(page.getByTestId('journal-page')).toContainText('Page 2 of 2');
    expect(queries().at(-1)!.body.variables.page).toBe(1);

    await page.getByTestId('filter-types').fill('LOGIN, MEMBER_ROLE_CHANGED');
    await page.getByTestId('journal-search').click();
    await expect.poll(() => queries().at(-1)!.body.variables).toEqual({ filter: { eventTypes: ['LOGIN', 'MEMBER_ROLE_CHANGED'] }, page: 0, size: 20 });
  });
});

test.describe('REQ-MGR-01 invitations follow the role', () => {
  test('a MANAGER can invite only with USER; an ADMIN with any role', async ({ page, browser }) => {
    await open(page, 'MANAGER', false, '/invitations');
    await expect(page.getByTestId('invite-role').locator('option')).toHaveText(['USER']);

    const adminPage = await (await browser.newContext()).newPage();
    await open(adminPage, 'ADMIN', false, '/invitations');
    await expect(adminPage.getByTestId('invite-role').locator('option')).toHaveText(['USER', 'MANAGER', 'ADMIN']);
  });

  test('sends the invitation and lists it', async ({ page }) => {
    const calls = await open(page, 'ADMIN', false, '/invitations');

    await page.getByLabel('Email').fill('new@x.com');
    await page.getByTestId('invite-role').selectOption('MANAGER');
    await page.getByTestId('invite-submit').click();

    await expect(page.getByTestId('invitations-message')).toContainText('new@x.com');
    await expect(page.getByTestId('invitation')).toHaveCount(2);
    expect(calls.find(call => call.method === 'POST' && call.path.endsWith('/invitations'))?.body).toEqual({ email: 'new@x.com', role: 'MANAGER' });
  });
});

test.describe('REQ-ROLE-06 a read-only member is offered no write, whatever the role', () => {
  for (const role of ['ADMIN', 'MANAGER', 'USER']) {
    test(`${role} without write access: flagged, no theme editor, no invitation form`, async ({ page }) => {
      await open(page, role, true, '/dashboard');

      await expect(page.getByTestId('nav-readonly')).toBeVisible();
      await expect(page.getByTestId('readonly-notice')).toBeVisible();
      await expect(page.getByTestId('nav-theme')).toHaveCount(0);

      if (role !== 'USER') {
        await page.getByTestId('nav-invitations').click();
        await expect(page.getByTestId('invite-form')).toHaveCount(0);
        await expect(page.getByTestId('invite-unavailable')).toContainText('read-only');
      }
    });
  }
});
