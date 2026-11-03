import { Page, Route } from '@playwright/test';

export const GATEWAY = 'http://localhost:8080';

const CORS = {
  'access-control-allow-origin': '*',
  'access-control-allow-headers': '*',
  'access-control-allow-methods': 'GET,POST,PUT,PATCH,DELETE,OPTIONS'
};

export interface RecordedCall {
  method: string;
  path: string;
  authorization: string | undefined;
  body: any;
}

function base64Url(value: string): string {
  return Buffer.from(value).toString('base64url');
}

/** A token the app only reads (the gateway is a stub, so nothing verifies it). */
export function tenantToken(tenantId: string, role = 'ADMIN', userId = 'user-1'): string {
  const claims = { sub: userId, tenantId, tenantName: `Tenant ${tenantId}`, displayName: 'a@test.local', role, readOnly: false };
  return [base64Url('{"alg":"HS256"}'), base64Url(JSON.stringify(claims)), 'signature'].join('.');
}

export function tokenResponse(tenantId: string, role = 'ADMIN') {
  return { accessToken: tenantToken(tenantId, role), refreshToken: `refresh-${tenantId}`, tokenType: 'Bearer', expiresIn: 900 };
}

type Handler = (call: RecordedCall) => { status?: number; body?: unknown } | undefined;

/**
 * Answers every call to the gateway with {@code handler} (404 when it returns nothing), answers the browser's CORS
 * preflights, and returns the calls it saw, so a test can say what the app sent and with which token.
 */
export async function stubGateway(page: Page, handler: Handler): Promise<RecordedCall[]> {
  const calls: RecordedCall[] = [];
  await page.route(`${GATEWAY}/**`, async (route: Route) => {
    const request = route.request();
    if (request.method() === 'OPTIONS') {
      await route.fulfill({ status: 204, headers: CORS });
      return;
    }
    const call: RecordedCall = {
      method: request.method(),
      path: new URL(request.url()).pathname,
      authorization: (await request.allHeaders())['authorization'],
      body: request.postData() ? JSON.parse(request.postData()!) : undefined
    };
    calls.push(call);
    const answer = handler(call);
    await route.fulfill({
      status: answer ? answer.status ?? 200 : 404,
      headers: { ...CORS, 'content-type': 'application/json' },
      body: JSON.stringify(answer?.body ?? {})
    });
  });
  return calls;
}
