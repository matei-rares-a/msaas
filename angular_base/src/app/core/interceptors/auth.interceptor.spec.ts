import { TestBed } from '@angular/core/testing';
import { HTTP_INTERCEPTORS, HttpClient, provideHttpClient, withInterceptorsFromDi } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Router } from '@angular/router';
import { AuthInterceptor } from './auth.interceptor';
import { AuthService } from '../services/auth.service';
import { authResponse, storeActiveSession } from '../../../testing/sessions';

describe('REQ-AUTH-03 the identity token goes only to the two routes that accept it', () => {
  let http: HttpClient;
  let controller: HttpTestingController;
  let auth: AuthService;
  let router: jasmine.SpyObj<Router>;

  beforeEach(() => {
    localStorage.clear();
    router = jasmine.createSpyObj<Router>('Router', ['navigate']);
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptorsFromDi()),
        provideHttpClientTesting(),
        { provide: HTTP_INTERCEPTORS, useClass: AuthInterceptor, multi: true },
        { provide: Router, useValue: router }
      ]
    });
    http = TestBed.inject(HttpClient);
    controller = TestBed.inject(HttpTestingController);
    auth = TestBed.inject(AuthService);
    auth.login({ email: 'a@test.local', password: 'password-12345' }).subscribe();
    controller.expectOne('http://localhost:8080/auth/login').flush({ tenants: [], identityToken: 'identity-token' });
  });

  afterEach(() => {
    controller.verify();
    localStorage.clear();
  });

  it('sends it to create an organization and to accept an invitation', () => {
    http.post('http://localhost:8080/organizations', { name: 'Acme' }).subscribe();
    expect(controller.expectOne('http://localhost:8080/organizations').request.headers.get('Authorization')).toBe('Bearer identity-token');

    http.post('http://localhost:8080/invitations/accept', { token: 'abc' }).subscribe();
    expect(controller.expectOne('http://localhost:8080/invitations/accept').request.headers.get('Authorization')).toBe('Bearer identity-token');
  });

  it('sends it nowhere else, not even to the same path with another method', () => {
    for (const [method, path] of [['GET', '/organizations'], ['GET', '/organizations/t1/theme'], ['PUT', '/organizations'],
      ['GET', '/billing/subscription'], ['GET', '/notifications/in-app'], ['POST', '/notifications/activity-log/exports']]) {
      http.request(method, `http://localhost:8080${path}`).subscribe({ error: () => undefined });
      const request = controller.expectOne(`http://localhost:8080${path}`);
      expect(request.request.headers.has('Authorization')).withContext(`${method} ${path}`).toBeFalse();
      request.flush({});
    }
  });

  it('does not treat a lookalike path as one of the two routes', () => {
    http.post('http://localhost:8080/organizations/x', {}).subscribe({ error: () => undefined });
    const request = controller.expectOne('http://localhost:8080/organizations/x');
    expect(request.request.headers.has('Authorization')).toBeFalse();
    request.flush({});
  });
});

describe('REQ-AUTH-04 every request carries the active tenant\'s token', () => {
  let http: HttpClient;
  let controller: HttpTestingController;
  let auth: AuthService;
  let router: jasmine.SpyObj<Router>;

  beforeEach(() => {
    localStorage.clear();
    router = jasmine.createSpyObj<Router>('Router', ['navigate']);
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptorsFromDi()),
        provideHttpClientTesting(),
        { provide: HTTP_INTERCEPTORS, useClass: AuthInterceptor, multi: true },
        { provide: Router, useValue: router }
      ]
    });
    http = TestBed.inject(HttpClient);
    controller = TestBed.inject(HttpTestingController);
    auth = TestBed.inject(AuthService);
  });

  afterEach(() => {
    controller.verify();
    localStorage.clear();
  });

  it('attaches the token of the active tenant, and the other one after switching', () => {
    storeActiveSession('tenant-a');
    const tokenA = auth.getAccessToken();
    storeActiveSession('tenant-b');
    const tokenB = auth.getAccessToken();

    http.get('http://localhost:8080/billing/subscription').subscribe();
    expect(controller.expectOne('http://localhost:8080/billing/subscription').request.headers.get('Authorization')).toBe(`Bearer ${tokenB}`);

    auth.activateTenant('tenant-a');
    http.get('http://localhost:8080/billing/subscription').subscribe();
    expect(controller.expectOne('http://localhost:8080/billing/subscription').request.headers.get('Authorization')).toBe(`Bearer ${tokenA}`);
  });

  it('sends no token to login, register, refresh or the password routes', () => {
    storeActiveSession('tenant-a');

    for (const path of ['/auth/login', '/auth/login/tenant', '/auth/register', '/auth/refresh', '/auth/forgot-password', '/auth/reset-password']) {
      http.post(`http://localhost:8080${path}`, {}).subscribe({ error: () => undefined });
      const request = controller.expectOne(`http://localhost:8080${path}`);
      expect(request.request.headers.has('Authorization')).withContext(path).toBeFalse();
      request.flush({});
    }
  });

  it('on a 401 renews the token once and repeats the request with the new one', () => {
    storeActiveSession('tenant-a');
    let body: any;

    http.get('http://localhost:8080/billing/subscription').subscribe(b => (body = b));
    controller.expectOne('http://localhost:8080/billing/subscription').flush({}, { status: 401, statusText: 'Unauthorized' });
    const refreshed = authResponse('tenant-a');
    controller.expectOne('http://localhost:8080/auth/refresh').flush(refreshed);
    const repeated = controller.expectOne('http://localhost:8080/billing/subscription');
    expect(repeated.request.headers.get('Authorization')).toBe(`Bearer ${refreshed.accessToken}`);
    repeated.flush({ ok: true });

    expect(body).toEqual({ ok: true });
  });

  it('when the renewal itself is refused, signs the user out and sends them to log in, without waiting forever', () => {
    storeActiveSession('tenant-a');
    let error: any;

    http.get('http://localhost:8080/billing/subscription').subscribe({ error: e => (error = e) });
    controller.expectOne('http://localhost:8080/billing/subscription').flush({}, { status: 401, statusText: 'Unauthorized' });
    controller.expectOne('http://localhost:8080/auth/refresh').flush({}, { status: 401, statusText: 'Unauthorized' });

    expect(error).toBeTruthy();
    expect(auth.hasValidToken()).toBeFalse();
    expect(router.navigate).toHaveBeenCalledWith(['/login']);
  });

  it('a 401 for a request that carried no token is passed on, not retried', () => {
    let error: any;

    http.get('http://localhost:8080/billing/plans').subscribe({ error: e => (error = e) });
    controller.expectOne('http://localhost:8080/billing/plans').flush({}, { status: 401, statusText: 'Unauthorized' });

    expect(error.status).toBe(401);
  });
});
