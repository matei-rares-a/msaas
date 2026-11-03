import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { ActivatedRouteSnapshot, Router, RouterStateSnapshot, UrlTree } from '@angular/router';
import { adminGuard } from './role.guards';
import { fakeJwt } from '../../../testing/sessions';

describe('REQ-PERS-01 only an ADMIN with write access reaches the theme editor', () => {
  const route = {} as ActivatedRouteSnapshot;
  const state = {} as RouterStateSnapshot;
  const dashboard = { redirect: '/dashboard' } as unknown as UrlTree;
  let router: jasmine.SpyObj<Router>;

  function signInAs(role: string, readOnly = false) {
    localStorage.clear();
    const token = fakeJwt({ sub: 'user-1', tenantId: 'tenant-a', tenantName: 'A', displayName: 'a@test.local', role, readOnly });
    localStorage.setItem('msaas.sessions', JSON.stringify({ 'tenant-a': { accessToken: token, refreshToken: 'r', expiresAt: Date.now() + 60_000 } }));
    localStorage.setItem('msaas.activeTenant', 'tenant-a');
    router = jasmine.createSpyObj<Router>('Router', ['createUrlTree']);
    router.createUrlTree.and.returnValue(dashboard);
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), { provide: Router, useValue: router }]
    });
    return TestBed.runInInjectionContext(() => adminGuard(route, state));
  }

  afterEach(() => localStorage.clear());

  it('lets an ADMIN in', () => {
    expect(signInAs('ADMIN')).toBeTrue();
  });

  it('turns away a MANAGER, a USER and an ADMIN who is read-only', () => {
    expect(signInAs('MANAGER')).toBe(dashboard);
    TestBed.resetTestingModule();
    expect(signInAs('USER')).toBe(dashboard);
    TestBed.resetTestingModule();
    expect(signInAs('ADMIN', true)).toBe(dashboard);
  });

  it('turns away whoever is not signed in', () => {
    localStorage.clear();
    router = jasmine.createSpyObj<Router>('Router', ['createUrlTree']);
    router.createUrlTree.and.returnValue(dashboard);
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), { provide: Router, useValue: router }]
    });

    expect(TestBed.runInInjectionContext(() => adminGuard(route, state))).toBe(dashboard);
  });
});
