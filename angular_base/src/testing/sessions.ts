/** Test helpers for the client's sessions: a token is only read, never verified, by the client, so an unsigned one will do. */

export interface FakeClaims {
  sub?: string;
  tenantId?: string;
  tenantName?: string;
  displayName?: string;
  role?: string;
  readOnly?: boolean;
  typ?: string;
}

function base64Url(value: string): string {
  return btoa(value).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

export function fakeJwt(claims: FakeClaims): string {
  return [base64Url(JSON.stringify({ alg: 'HS256' })), base64Url(JSON.stringify(claims)), 'signature'].join('.');
}

export function tenantToken(tenantId: string, userId = 'user-1', role = 'ADMIN'): string {
  return fakeJwt({ sub: userId, tenantId, tenantName: `Tenant ${tenantId}`, displayName: 'a@test.local', role, readOnly: false });
}

export function authResponse(tenantId: string, userId = 'user-1', role = 'ADMIN', expiresIn = 900) {
  return { accessToken: tenantToken(tenantId, userId, role), refreshToken: `refresh-${tenantId}`, tokenType: 'Bearer', expiresIn };
}

/** Puts a session for the tenant in storage, the way a login would, and makes it the active one. */
export function storeActiveSession(tenantId: string, expiresAt = Date.now() + 60_000, userId = 'user-1'): void {
  const sessions = JSON.parse(localStorage.getItem('msaas.sessions') ?? '{}');
  sessions[tenantId] = { accessToken: tenantToken(tenantId, userId), refreshToken: `refresh-${tenantId}`, expiresAt };
  localStorage.setItem('msaas.sessions', JSON.stringify(sessions));
  localStorage.setItem('msaas.activeTenant', tenantId);
}

/** Signs a user of the given role in to tenant `t` the way a login would, for component tests that read who they are from the token. */
export function signInAs(role: string, readOnly = false, userId = 'u', tenantId = 't'): void {
  localStorage.clear();
  const token = fakeJwt({ sub: userId, tenantId, tenantName: 'T', displayName: 'd', role, readOnly });
  localStorage.setItem('msaas.sessions', JSON.stringify({ [tenantId]: { accessToken: token, refreshToken: 'r', expiresAt: Date.now() + 60_000 } }));
  localStorage.setItem('msaas.activeTenant', tenantId);
}
