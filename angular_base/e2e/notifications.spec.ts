import { expect, test } from '@playwright/test';
import { RecordedCall, stubGateway } from './gateway-stub';
import { signedInAs } from './session';

const NOTIFICATIONS = '/notifications/in-app';

const item = (id: string, read: boolean) =>
  ({ id, eventType: 'MEMBER_ROLE_CHANGED', level: 'INFO', title: `Title ${id}`, message: `Message ${id}`, read, createdAt: '2026-11-03T10:00:00Z' });

/** A small in-memory notification service: the list, the unread count, and what marking does to both. */
function notificationApi(initial: ReturnType<typeof item>[]) {
  const items = [...initial];
  const unread = () => items.filter(i => !i.read).length;
  return {
    items,
    handler: (call: RecordedCall) => {
      if (call.path === NOTIFICATIONS && call.method === 'GET') {
        return { body: { items, page: 0, size: 20, totalItems: items.length, unreadCount: unread() } };
      }
      if (call.path === `${NOTIFICATIONS}/unread-count`) {
        return { body: { unreadCount: unread() } };
      }
      if (call.path === `${NOTIFICATIONS}/read` && call.method === 'PATCH') {
        const updated = unread();
        items.forEach(i => (i.read = true));
        return { body: { updated } };
      }
      const single = call.path.match(/^\/notifications\/in-app\/([^/]+)\/read$/);
      if (single && call.method === 'PATCH') {
        const found = items.find(i => i.id === single[1])!;
        found.read = true;
        return { body: found };
      }
      if (call.path === '/organizations/tenant-a/theme') {
        return { body: { paletteId: 'default', palette: null, logoUrl: null } };
      }
      return call.path === '/organizations' ? { body: [] } : undefined;
    }
  };
}

test.describe('REQ-NOTIF-04 notifications load when the app opens', () => {
  test('shows the unread count in the bar as soon as the app opens', async ({ page }) => {
    await signedInAs(page, 'USER');
    const api = notificationApi([item('a', false), item('b', false), item('c', true)]);
    const calls = await stubGateway(page, api.handler);

    await page.goto('/dashboard');

    await expect(page.getByTestId('notification-badge')).toHaveText('2');
    const opening = calls.find(call => call.path === NOTIFICATIONS && call.method === 'GET');
    expect(opening?.authorization).toMatch(/^Bearer /);
  });

  test('shows no badge when there is nothing unread', async ({ page }) => {
    await signedInAs(page, 'USER');
    await stubGateway(page, notificationApi([item('a', true)]).handler);

    await page.goto('/dashboard');

    await expect(page.getByTestId('nav-notifications')).toBeVisible();
    await expect(page.getByTestId('notification-badge')).toHaveCount(0);
  });
});

test.describe('REQ-NOTIF-04 the unread count is polled every 60 seconds', () => {
  test('asks again after 60 seconds, not before, and shows the new count', async ({ page }) => {
    await page.clock.install();
    await signedInAs(page, 'USER');
    const api = notificationApi([item('a', false)]);
    const calls = await stubGateway(page, api.handler);
    const polls = () => calls.filter(call => call.path === `${NOTIFICATIONS}/unread-count`).length;

    await page.goto('/dashboard');
    await expect(page.getByTestId('notification-badge')).toHaveText('1');

    await page.clock.fastForward(59_000);
    expect(polls()).toBe(0);

    api.items.push(item('b', false), item('c', false));
    await page.clock.fastForward(2_000);

    await expect(page.getByTestId('notification-badge')).toHaveText('3');
    expect(polls()).toBe(1);
    await page.clock.fastForward(60_000);
    await expect.poll(polls).toBe(2);
  });
});

test.describe('REQ-NOTIF-04 reading notifications', () => {
  test('lists them, marks one as read and lowers the badge, then marks the rest', async ({ page }) => {
    await signedInAs(page, 'USER');
    const api = notificationApi([item('a', false), item('b', false)]);
    const calls = await stubGateway(page, api.handler);

    await page.goto('/dashboard');
    await page.getByTestId('nav-notifications').click();
    await expect(page.getByTestId('notification')).toHaveCount(2);

    await page.getByTestId('notification').first().click();
    await expect(page.getByTestId('notification-badge')).toHaveText('1');
    expect(calls.some(call => call.method === 'PATCH' && call.path === `${NOTIFICATIONS}/a/read`)).toBe(true);

    await page.getByTestId('mark-all-read').click();
    await expect(page.getByTestId('notification-badge')).toHaveCount(0);
    await expect(page.getByTestId('mark-all-read')).toBeDisabled();
  });

  test('the page needs a signed-in user', async ({ page }) => {
    await stubGateway(page, () => undefined);

    await page.goto('/notifications');

    await expect(page).toHaveURL(/\/login\?returnUrl=%2Fnotifications/);
  });
});
