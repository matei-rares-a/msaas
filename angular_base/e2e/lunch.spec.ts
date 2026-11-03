import { expect, test } from '@playwright/test';
import { stubGateway } from './gateway-stub';
import { newFake, organizationApi } from './organization-api';
import { signedInAs } from './session';

test.describe('REQ-LUNCH-02 a member joins and leaves the weekly round', () => {
  test('joins, the count goes up, withdraws, and it goes down', async ({ page }) => {
    const fake = newFake();
    await signedInAs(page, 'USER', false, 'member-1');
    const calls = await stubGateway(page, organizationApi(fake));

    await page.goto('/dashboard');
    await page.getByTestId('nav-lunch').click();
    await expect(page.getByTestId('round-status')).toHaveText('OPEN');
    await expect(page.getByTestId('round-count')).toContainText('0 joined');

    await page.getByTestId('enroll').click();
    await expect(page.getByTestId('round-count')).toContainText('1 joined');
    await expect(page.getByTestId('enrolled')).toBeVisible();
    expect(calls.some(call => call.method === 'PUT' && call.path.endsWith('/rounds/current/enrollment'))).toBe(true);

    await page.getByTestId('withdraw').click();
    await expect(page.getByTestId('round-count')).toContainText('0 joined');
    await expect(page.getByTestId('enroll')).toBeVisible();
  });

  test('a read-only member sees the round and cannot join', async ({ page }) => {
    await signedInAs(page, 'USER', true);
    await stubGateway(page, organizationApi(newFake()));

    await page.goto('/lunch');

    await expect(page.getByTestId('round-status')).toBeVisible();
    await expect(page.getByTestId('enroll')).toHaveCount(0);
    await expect(page.getByTestId('lunch-readonly')).toBeVisible();
  });
});

test.describe('REQ-LUNCH-04 who sees which groups', () => {
  const formed = {
    id: 'rd0', weekStart: '2026-11-30', deadlineAt: '2026-12-03T09:00:00Z', status: 'FORMED', cancelReason: null,
    groups: [['member-1', 'aaaaaaaa-1111'], ['cccccccc-3', 'dddddddd-4', 'eeeeeeee-5']],
    members: ['member-1', 'aaaaaaaa-1111', 'cccccccc-3', 'dddddddd-4', 'eeeeeeee-5'],
    approvers: ['manager-1']
  };

  test('a participant sees their own group and not the others', async ({ page }) => {
    await signedInAs(page, 'USER', false, 'member-1');
    await stubGateway(page, organizationApi(newFake({ formed })));

    await page.goto('/lunch');

    await expect(page.getByTestId('my-group').locator('li')).toHaveText(['You', 'aaaaaaaa']);
    await expect(page.getByTestId('group')).toHaveCount(0);
  });

  test('a manager sees every group', async ({ page }) => {
    await signedInAs(page, 'MANAGER', false, 'manager-1');
    await stubGateway(page, organizationApi(newFake({ formed })));

    await page.goto('/lunch');

    await expect(page.getByTestId('group')).toHaveCount(2);
    await expect(page.getByTestId('group').nth(1)).toContainText('eeeeeeee');
  });

  test('someone who was not in the round sees no groups', async ({ page }) => {
    await signedInAs(page, 'USER', false, 'outsider-9');
    await stubGateway(page, organizationApi(newFake({ formed })));

    await page.goto('/lunch');

    await expect(page.getByTestId('round-status')).toBeVisible();
    await expect(page.getByTestId('groups')).toHaveCount(0);
  });
});

test.describe('REQ-LUNCH-01 MANAGER and ADMIN configure the round', () => {
  test('a manager changes the day, the closing time and the group size', async ({ page }) => {
    const fake = newFake();
    await signedInAs(page, 'MANAGER', false, 'manager-1');
    const calls = await stubGateway(page, organizationApi(fake));

    await page.goto('/lunch');
    await page.getByTestId('config-day').selectOption('FRIDAY');
    await page.getByTestId('config-cutoff').fill('10:30');
    await page.getByTestId('config-size').fill('3');
    await page.getByTestId('save-config').click();

    await expect(page.getByTestId('lunch-message')).toContainText('settings are saved');
    expect(calls.find(call => call.method === 'PUT' && call.path.endsWith('/lunch/config'))?.body).toEqual({ day: 'FRIDAY', cutoff: '10:30', groupSize: 3 });
    expect(fake.config).toEqual({ day: 'FRIDAY', cutoff: '10:30', groupSize: 3 });
  });

  test('a manager can cancel the round once someone has joined', async ({ page }) => {
    await signedInAs(page, 'MANAGER', false, 'manager-1');
    await stubGateway(page, organizationApi(newFake()));

    await page.goto('/lunch');
    await page.getByTestId('cancel-round').click();

    await expect(page.getByTestId('round-cancelled')).toBeVisible();
    await expect(page.getByTestId('enroll')).toHaveCount(0);
  });

  test('a user gets no settings and no cancel button', async ({ page }) => {
    await signedInAs(page, 'USER');
    await stubGateway(page, organizationApi(newFake()));

    await page.goto('/lunch');

    await expect(page.getByTestId('round-status')).toBeVisible();
    await expect(page.getByTestId('config')).toHaveCount(0);
    await expect(page.getByTestId('cancel-round')).toHaveCount(0);
  });
});
