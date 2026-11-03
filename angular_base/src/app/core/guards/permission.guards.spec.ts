import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { ActivatedRouteSnapshot, Router, RouterStateSnapshot, UrlTree } from '@angular/router';
import { billingGuard, invitationsGuard, journalGuard } from './permission.guards';
import { fakeJwt } from '../../../testing/sessions';

describe('REQ-ROLE-05 pages are opened by the roles that may use them', () => {
  const route = {} as ActivatedRouteSnapshot;
  const state = {} as RouterStateSnapshot;
  const dashboard = { redirect: '/dashboard' } as unknown as UrlTree;

  function run(guard: typeof billingGuard, role: string | null, readOnly = false) {
    localStorage.clear();
    TestBed.resetTestingModule();
    if (role) {
      const token = fakeJwt({ sub: 'u', tenantId: 't', tenantName: 'T', displayName: 'd', role, readOnly });
      localStorage.setItem('msaas.sessions', JSON.stringify({ t: { accessToken: token, refreshToken: 'r', expiresAt: Date.now() + 60_000 } }));
      localStorage.setItem('msaas.activeTenant', 't');
    }
    const router = jasmine.createSpyObj<Router>('Router', ['createUrlTree']);
    router.createUrlTree.and.returnValue(dashboard);
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting(), { provide: Router, useValue: router }] });
    return TestBed.runInInjectionContext(() => guard(route, state));
  }

  afterEach(() => localStorage.clear());

  it('lets an ADMIN and a MANAGER open billing, invitations and the journal', () => {
    for (const guard of [billingGuard, invitationsGuard, journalGuard]) {
      expect(run(guard, 'ADMIN')).toBeTrue();
      expect(run(guard, 'MANAGER')).toBeTrue();
    }
  });

  it('turns a USER away from all three, and so whoever is not signed in', () => {
    for (const guard of [billingGuard, invitationsGuard, journalGuard]) {
      expect(run(guard, 'USER')).toBe(dashboard);
      expect(run(guard, null)).toBe(dashboard);
    }
  });

  it('does not turn a read-only member away from what they may read', () => {
    expect(run(billingGuard, 'MANAGER', true)).toBeTrue();
    expect(run(journalGuard, 'ADMIN', true)).toBeTrue();
  });
});
