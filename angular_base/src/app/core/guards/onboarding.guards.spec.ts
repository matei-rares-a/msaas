import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ActivatedRouteSnapshot, Router, RouterStateSnapshot, UrlTree } from '@angular/router';
import { identityGuard, pendingChoiceGuard } from './onboarding.guards';
import { AuthService } from '../services/auth.service';

describe('REQ-AUTH-03 the pages for a user with no tenant need what produces them', () => {
  const route = {} as ActivatedRouteSnapshot;
  const state = {} as RouterStateSnapshot;
  let auth: AuthService;
  let http: HttpTestingController;
  let router: jasmine.SpyObj<Router>;
  const loginUrl = { redirect: '/login' } as unknown as UrlTree;

  beforeEach(() => {
    localStorage.clear();
    router = jasmine.createSpyObj<Router>('Router', ['createUrlTree']);
    router.createUrlTree.and.returnValue(loginUrl);
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), { provide: Router, useValue: router }]
    });
    auth = TestBed.inject(AuthService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => localStorage.clear());

  const run = (guard: typeof identityGuard) => TestBed.runInInjectionContext(() => guard(route, state));

  it('sends to /login whoever has no identity token', () => {
    expect(run(identityGuard)).toBe(loginUrl);
    expect(router.createUrlTree).toHaveBeenCalledWith(['/login']);
  });

  it('lets in whoever logged in with no tenant', () => {
    auth.login({ email: 'a@test.local', password: 'password-12345' }).subscribe();
    http.expectOne('http://localhost:8080/auth/login').flush({ tenants: [], identityToken: 'identity-token' });

    expect(run(identityGuard)).toBeTrue();
  });
});

describe('REQ-AUTH-02 the tenant choice is only reachable right after a login that needs it', () => {
  const route = {} as ActivatedRouteSnapshot;
  const state = {} as RouterStateSnapshot;
  let auth: AuthService;
  let http: HttpTestingController;
  let router: jasmine.SpyObj<Router>;
  const loginUrl = { redirect: '/login' } as unknown as UrlTree;

  beforeEach(() => {
    localStorage.clear();
    router = jasmine.createSpyObj<Router>('Router', ['createUrlTree']);
    router.createUrlTree.and.returnValue(loginUrl);
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), { provide: Router, useValue: router }]
    });
    auth = TestBed.inject(AuthService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => localStorage.clear());

  const run = () => TestBed.runInInjectionContext(() => pendingChoiceGuard(route, state));

  it('sends to /login when there is no choice to make', () => {
    expect(run()).toBe(loginUrl);
  });

  it('lets in after a login with several tenants, and not again once the choice is made or spent', () => {
    auth.login({ email: 'a@test.local', password: 'password-12345' }).subscribe();
    http.expectOne('http://localhost:8080/auth/login').flush({ tenants: [{ tenantId: 't1', role: 'USER' }], loginSessionId: 'opaque' });
    expect(run()).toBeTrue();

    auth.selectTenant('t1').subscribe({ error: () => undefined });
    http.expectOne('http://localhost:8080/auth/login/tenant').flush({}, { status: 400, statusText: 'Bad Request' });

    expect(run()).toBe(loginUrl);
  });
});
