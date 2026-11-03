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
  /** The names of the parts of a multipart request, in order. */
  parts: string[];
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

type Answer = { status?: number; body?: unknown; bytes?: Buffer; contentType?: string };
type Handler = (call: RecordedCall) => Answer | undefined;

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
    const headers = await request.allHeaders();
    const contentType = headers['content-type'] ?? '';
    const text = request.postDataBuffer()?.toString('latin1');
    const call: RecordedCall = {
      method: request.method(),
      path: new URL(request.url()).pathname,
      authorization: headers['authorization'],
      body: text && contentType.includes('json') ? JSON.parse(text) : undefined,
      parts: text && contentType.includes('multipart') ? [...text.matchAll(/[; ]name="([^"]+)"/g)].map(match => match[1]) : []
    };
    calls.push(call);
    const answer = handler(call);
    await route.fulfill({
      status: answer ? answer.status ?? 200 : 404,
      headers: { ...CORS, 'content-type': answer?.contentType ?? 'application/json' },
      ...(answer?.bytes ? { body: answer.bytes } : { body: JSON.stringify(answer?.body ?? {}) })
    });
  });
  return calls;
}
