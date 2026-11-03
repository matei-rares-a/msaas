import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { AuthService } from './auth.service';
import { authResponse, fakeJwt, storeActiveSession } from '../../../testing/sessions';

const BASE = 'http://localhost:8080/auth';
const credentials = { email: 'a@test.local', password: 'a-long-enough-password' };

describe('REQ-AUTH-01 login with one tenant gives the token directly', () => {
  let service: AuthService;
  let http: HttpTestingController;

  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    service = TestBed.inject(AuthService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    http.verify();
    localStorage.clear();
  });

  it('signs the user in and reads who they are from the token alone', () => {
    let result: any;
    service.login(credentials).subscribe(r => (result = r));
    http.expectOne(`${BASE}/login`).flush(authResponse('tenant-a'));

    expect(result).toEqual({ kind: 'signedIn' });
    expect(service.hasValidToken()).toBeTrue();
    expect(service.getActiveTenantId()).toBe('tenant-a');
    let user: any;
    service.user$.subscribe(u => (user = u));
    expect(user).toEqual({
      id: 'user-1', tenantId: 'tenant-a', tenantName: 'Tenant tenant-a', displayName: 'a@test.local', role: 'ADMIN', readOnly: false
    });
  });

  it('refuses a token that is not for a tenant', () => {
    let error: any;
    service.login(credentials).subscribe({ error: e => (error = e) });
    http.expectOne(`${BASE}/login`).flush({
      accessToken: fakeJwt({ sub: 'user-1', typ: 'identity' }), refreshToken: 'r', expiresIn: 900
    });

    expect(error).toBeTruthy();
    expect(service.hasValidToken()).toBeFalse();
  });

  it('a failed login signs nobody in', () => {
    let error: any;
    service.login(credentials).subscribe({ error: e => (error = e) });
    http.expectOne(`${BASE}/login`).flush({ message: 'Invalid credentials' }, { status: 401, statusText: 'Unauthorized' });

    expect(error.status).toBe(401);
    expect(service.hasValidToken()).toBeFalse();
  });

  it('registering gives no token: the account exists and the user logs in next', () => {
    service.register(credentials).subscribe();
    http.expectOne(`${BASE}/register`).flush(null, { status: 201, statusText: 'Created' });

    expect(service.hasValidToken()).toBeFalse();
  });
});

describe('REQ-AUTH-02 login with several tenants asks which one', () => {
  let service: AuthService;
  let http: HttpTestingController;
  const tenants = [{ tenantId: 'tenant-a', role: 'ADMIN' }, { tenantId: 'tenant-b', role: 'USER' }];

  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    service = TestBed.inject(AuthService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    http.verify();
    localStorage.clear();
  });

  function loginWithTwoTenants() {
    service.login(credentials).subscribe();
    http.expectOne(`${BASE}/login`).flush({ tenants, loginSessionId: 'opaque-id' });
  }

  it('holds the choice and no token until the user picks', () => {
    let result: any;
    service.login(credentials).subscribe(r => (result = r));
    http.expectOne(`${BASE}/login`).flush({ tenants, loginSessionId: 'opaque-id' });

    expect(result).toEqual({ kind: 'chooseTenant' });
    expect(service.getPendingChoice()).toEqual({ tenants, loginSessionId: 'opaque-id' });
    expect(service.hasValidToken()).toBeFalse();
    expect(localStorage.getItem('msaas.sessions')).toBeNull();
  });

  it('exchanges the login id and the chosen tenant for that tenant\'s token', () => {
    loginWithTwoTenants();

    service.selectTenant('tenant-b').subscribe();
    const request = http.expectOne(`${BASE}/login/tenant`);
    expect(request.request.body).toEqual({ loginSessionId: 'opaque-id', tenantId: 'tenant-b' });
    request.flush(authResponse('tenant-b', 'user-1', 'USER'));

    expect(service.getActiveTenantId()).toBe('tenant-b');
    expect(service.hasValidToken()).toBeTrue();
    expect(service.getPendingChoice()).toBeNull();
  });

  it('spends the login id even when the exchange fails, so it is never used twice', () => {
    loginWithTwoTenants();

    service.selectTenant('tenant-b').subscribe({ error: () => undefined });
    http.expectOne(`${BASE}/login/tenant`).flush({ message: 'expired' }, { status: 400, statusText: 'Bad Request' });

    expect(service.getPendingChoice()).toBeNull();
    let error: any;
    service.selectTenant('tenant-b').subscribe({ error: e => (error = e) });
    http.expectNone(`${BASE}/login/tenant`);
    expect(error).toBeTruthy();
  });
});

describe('REQ-AUTH-03 a user with no tenant holds only an identity token', () => {
  let service: AuthService;
  let http: HttpTestingController;

  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    service = TestBed.inject(AuthService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    http.verify();
    localStorage.clear();
  });

  it('keeps the identity token in memory, never in storage, and it signs nobody in', () => {
    let result: any;
    service.login(credentials).subscribe(r => (result = r));
    http.expectOne(`${BASE}/login`).flush({ tenants: [], identityToken: 'identity-token' });

    expect(result).toEqual({ kind: 'noTenant' });
    expect(service.getIdentityToken()).toBe('identity-token');
    expect(service.hasValidToken()).toBeFalse();
    expect(JSON.stringify(localStorage)).not.toContain('identity-token');
  });

  it('drops the identity token when its job is done, and at logout', () => {
    service.login(credentials).subscribe();
    http.expectOne(`${BASE}/login`).flush({ tenants: [], identityToken: 'identity-token' });

    service.endIdentitySession();
    expect(service.getIdentityToken()).toBeNull();

    service.login(credentials).subscribe();
    http.expectOne(`${BASE}/login`).flush({ tenants: [], identityToken: 'identity-token-2' });
    service.logout();
    expect(service.getIdentityToken()).toBeNull();
  });

  it('a new login forgets the identity token of the last one', () => {
    service.login(credentials).subscribe();
    http.expectOne(`${BASE}/login`).flush({ tenants: [], identityToken: 'identity-token' });

    service.login(credentials).subscribe();
    expect(service.getIdentityToken()).toBeNull();
    http.expectOne(`${BASE}/login`).flush(authResponse('tenant-a'));
  });
});

describe('REQ-AUTH-04 one token per tenant, never one for all', () => {
  let service: AuthService;
  let http: HttpTestingController;

  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    service = TestBed.inject(AuthService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    http.verify();
    localStorage.clear();
  });

  function signInTo(tenantId: string, userId = 'user-1') {
    service.login(credentials).subscribe();
    http.expectOne(`${BASE}/login`).flush(authResponse(tenantId, userId));
  }

  it('keeps a token for each tenant the user signed in to, and only the active one is sent', () => {
    signInTo('tenant-a');
    const tokenA = service.getAccessToken();
    signInTo('tenant-b');
    const tokenB = service.getAccessToken();

    expect(tokenA).not.toEqual(tokenB);
    expect(service.getSessionTenantIds().sort()).toEqual(['tenant-a', 'tenant-b']);
    expect(service.getActiveTenantId()).toBe('tenant-b');

    expect(service.activateTenant('tenant-a')).toBeTrue();
    expect(service.getAccessToken()).toBe(tokenA);
    expect(service.getRefreshToken()).toBe('refresh-tenant-a');
  });

  it('a token carries one tenant and nothing about the others', () => {
    signInTo('tenant-a');
    signInTo('tenant-b');

    const payload = JSON.parse(atob(service.getAccessToken()!.split('.')[1]));
    expect(payload.tenantId).toBe('tenant-b');
    expect(JSON.stringify(payload)).not.toContain('tenant-a');
  });

  it('cannot switch to a tenant without a good token: the user logs in again', () => {
    signInTo('tenant-a');
    storeActiveSession('tenant-old', Date.now() - 1000);
    storeActiveSession('tenant-a');

    expect(service.activateTenant('tenant-unknown')).toBeFalse();
    expect(service.activateTenant('tenant-old')).toBeFalse();
    expect(service.getActiveTenantId()).toBe('tenant-a');
  });

  it('never keeps another user\'s tokens next to the new user\'s', () => {
    signInTo('tenant-a', 'user-1');
    signInTo('tenant-b', 'user-2');

    expect(service.getSessionTenantIds()).toEqual(['tenant-b']);
  });

  it('renews the active tenant\'s token with that tenant\'s refresh token only', () => {
    signInTo('tenant-a');
    signInTo('tenant-b');
    service.activateTenant('tenant-a');

    service.refresh().subscribe();
    const request = http.expectOne(`${BASE}/refresh`);
    expect(request.request.body).toEqual({ refreshToken: 'refresh-tenant-a' });
    request.flush({ ...authResponse('tenant-a'), refreshToken: 'rotated-a' });

    expect(service.getRefreshToken()).toBe('rotated-a');
    service.activateTenant('tenant-b');
    expect(service.getRefreshToken()).toBe('refresh-tenant-b');
  });

  it('logging out forgets every tenant\'s token', () => {
    signInTo('tenant-a');
    signInTo('tenant-b');

    service.logout();

    expect(service.getSessionTenantIds()).toEqual([]);
    expect(service.getAccessToken()).toBeNull();
    expect(service.hasValidToken()).toBeFalse();
  });

  it('an expired token is not a valid one', () => {
    storeActiveSession('tenant-a', Date.now() - 1000);

    expect(service.hasValidToken()).toBeFalse();
  });
});
