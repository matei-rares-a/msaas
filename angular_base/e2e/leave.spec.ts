import { expect, test } from '@playwright/test';
import { stubGateway } from './gateway-stub';
import { newFake, organizationApi } from './organization-api';
import { signedInAs } from './session';

test.describe('REQ-LEAVE-01 a member asks for leave, a manager decides, the member sees the outcome', () => {
  test('request -> approval -> the balance and the calendar follow', async ({ browser }) => {
    const fake = newFake();
    const api = organizationApi(fake);

    const member = await (await browser.newContext()).newPage();
    await signedInAs(member, 'USER', false, 'member-1');
    const memberCalls = await stubGateway(member, api);
    const manager = await (await browser.newContext()).newPage();
    await signedInAs(manager, 'MANAGER', false, 'manager-1');
    await stubGateway(manager, api);

    await member.goto('/dashboard');
    await member.getByTestId('nav-leave').click();
    await expect(member.getByTestId('balance-available')).toHaveText('21');

    await member.getByTestId('leave-type').selectOption('ODIHNA');
    await member.getByTestId('leave-start').fill('2026-12-07');
    await member.getByTestId('leave-end').fill('2026-12-09');
    await member.getByTestId('leave-reason').fill('family trip');
    await member.getByTestId('leave-submit').click();

    await expect(member.getByTestId('leave-message')).toContainText('sent for a decision');
    await expect(member.getByTestId('request-status')).toHaveText('PENDING');
    await expect(member.getByTestId('balance-pending')).toHaveText('3');
    await expect(member.getByTestId('balance-available')).toHaveText('18');
    const sent = memberCalls.find(call => call.method === 'POST' && call.path.endsWith('/leave/requests'));
    expect(sent?.body).toEqual({ type: 'ODIHNA', startDate: '2026-12-07', endDate: '2026-12-09', reason: 'family trip' });
    expect(sent?.authorization).toMatch(/^Bearer /);

    await manager.goto('/leave/approvals');
    await expect(manager.getByTestId('pending-request')).toHaveCount(1);
    await expect(manager.getByTestId('request-reason')).toContainText('family trip');
    await manager.getByTestId('approve').click();
    await expect(manager.getByTestId('approvals-message')).toContainText('approved');
    await expect(manager.getByTestId('no-pending')).toBeVisible();

    await member.reload();
    await expect(member.getByTestId('request-status')).toHaveText('APPROVED');
    await expect(member.getByTestId('balance-approved')).toHaveText('3');
    await expect(member.getByTestId('calendar-entry')).toHaveCount(1);
    await expect(member.getByTestId('calendar-entry')).toContainText('You');
    expect(await member.getByTestId('calendar-entry').textContent()).not.toContain('family trip');
  });

  test('the member can cancel an open request and the days come back', async ({ page }) => {
    const fake = newFake();
    await signedInAs(page, 'USER', false, 'member-1');
    await stubGateway(page, organizationApi(fake));

    await page.goto('/leave');
    await page.getByTestId('leave-start').fill('2026-12-07');
    await page.getByTestId('leave-end').fill('2026-12-09');
    await page.getByTestId('leave-submit').click();
    await expect(page.getByTestId('balance-available')).toHaveText('18');

    await page.getByTestId('cancel-request').click();

    await expect(page.getByTestId('leave-message')).toContainText('days are back');
    await expect(page.getByTestId('balance-available')).toHaveText('21');
    await expect(page.getByTestId('request-status')).toHaveText('CANCELLED');
  });

  test('a read-only member sees leave and cannot ask for it', async ({ page }) => {
    await signedInAs(page, 'USER', true);
    await stubGateway(page, organizationApi(newFake()));

    await page.goto('/leave');

    await expect(page.getByTestId('balance')).toBeVisible();
    await expect(page.getByTestId('leave-form')).toHaveCount(0);
    await expect(page.getByTestId('leave-readonly')).toBeVisible();
  });
});

test.describe('REQ-LEAVE-05 who decides', () => {
  test('a USER cannot open the approvals, a MANAGER cannot open the policy, an ADMIN opens both', async ({ browser }) => {
    const fake = newFake();
    const open = async (role: string, path: string) => {
      const page = await (await browser.newContext()).newPage();
      await signedInAs(page, role);
      await stubGateway(page, organizationApi(fake));
      await page.goto(path);
      return page;
    };

    await expect(await open('USER', '/leave/approvals')).toHaveURL(/\/dashboard/);
    await expect(await open('MANAGER', '/leave/policy')).toHaveURL(/\/dashboard/);
    await expect(await open('MANAGER', '/leave/approvals')).toHaveURL(/\/leave\/approvals/);
    await expect(await open('ADMIN', '/leave/policy')).toHaveURL(/\/leave\/policy/);
  });

  test('the ADMIN changes the default allowance and a member sees it in their balance', async ({ browser }) => {
    const fake = newFake();
    const api = organizationApi(fake);
    const admin = await (await browser.newContext()).newPage();
    await signedInAs(admin, 'ADMIN', false, 'admin-1');
    await stubGateway(admin, api);
    const member = await (await browser.newContext()).newPage();
    await signedInAs(member, 'USER', false, 'member-1');
    await stubGateway(member, api);

    await admin.goto('/leave/policy');
    await admin.getByTestId('default-allowance').fill('25');
    await admin.getByTestId('save-policy').click();
    await expect(admin.getByTestId('policy-message')).toContainText('default allowance');

    await member.goto('/leave');
    await expect(member.getByTestId('balance-allocated')).toHaveText('25');
  });
});
