import { expect, test } from '@playwright/test';
import { stubGateway } from './gateway-stub';
import { newFake, organizationApi } from './organization-api';
import { signedInAs } from './session';

test.describe('REQ-MOD-01 the ADMIN turns the business modules on and off', () => {
  test('both are off at first: no links, and their pages turn the member away', async ({ page }) => {
    await signedInAs(page, 'USER');
    await stubGateway(page, organizationApi(newFake({ modules: { LEAVE: false, LUNCH_ROULETTE: false } })));

    await page.goto('/dashboard');
    await expect(page.getByTestId('tenant-name')).toBeVisible();

    await expect(page.getByTestId('nav-leave')).toHaveCount(0);
    await expect(page.getByTestId('nav-lunch')).toHaveCount(0);
    await page.goto('/leave');
    await expect(page).toHaveURL(/\/dashboard/);
    await page.goto('/lunch');
    await expect(page).toHaveURL(/\/dashboard/);
  });

  test('the ADMIN switches a module on and its link appears; off, and it goes', async ({ page }) => {
    await signedInAs(page, 'ADMIN');
    const fake = newFake({ modules: { LEAVE: false, LUNCH_ROULETTE: false } });
    const calls = await stubGateway(page, organizationApi(fake));

    await page.goto('/dashboard');
    await page.getByTestId('nav-modules').click();
    await expect(page.getByTestId('toggle-LEAVE')).toHaveText('Off');
    await expect(page.getByTestId('nav-leave')).toHaveCount(0);

    await page.getByTestId('toggle-LEAVE').click();
    await expect(page.getByTestId('modules-message')).toContainText('Leave requests is now on');
    await expect(page.getByTestId('nav-leave')).toBeVisible();
    expect(calls.find(call => call.method === 'PUT' && call.path.endsWith('/modules/LEAVE'))?.body).toEqual({ enabled: true });

    await page.getByTestId('toggle-LEAVE').click();
    await expect(page.getByTestId('nav-leave')).toHaveCount(0);
    expect(fake.modules.LEAVE).toBe(false);
  });

  for (const [role, readOnly] of [['MANAGER', false], ['USER', false], ['ADMIN', true]] as const) {
    test(`${role}${readOnly ? ' without write access' : ''} gets no module settings`, async ({ page }) => {
      await signedInAs(page, role, readOnly);
      await stubGateway(page, organizationApi(newFake()));

      await page.goto('/dashboard');
      await expect(page.getByTestId('tenant-name')).toBeVisible();
      await expect(page.getByTestId('nav-modules')).toHaveCount(0);
      await page.goto('/modules');
      await expect(page).toHaveURL(/\/dashboard/);
    });
  }
});
