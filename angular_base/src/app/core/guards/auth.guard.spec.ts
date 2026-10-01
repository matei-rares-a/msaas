import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { ActivatedRouteSnapshot, Router, RouterStateSnapshot } from '@angular/router';
import { AuthGuard } from './auth.guard';

describe('AuthGuard', () => {
  let guard: AuthGuard;
  let router: jasmine.SpyObj<Router>;
  const route = {} as ActivatedRouteSnapshot;
  const state = { url: '/billing/42' } as RouterStateSnapshot;

  beforeEach(() => {
    localStorage.clear();
    router = jasmine.createSpyObj<Router>('Router', ['navigate']);
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), { provide: Router, useValue: router }]
    });
    guard = TestBed.inject(AuthGuard);
  });

  afterEach(() => localStorage.clear());

  it('redirects to /login with the requested url when there is no token', () => {
    expect(guard.canActivate(route, state)).toBeFalse();
    expect(router.navigate).toHaveBeenCalledWith(['/login'], { queryParams: { returnUrl: '/billing/42' } });
  });

  it('redirects to /login when the token is expired', () => {
    localStorage.setItem('accessToken', 'token');
    localStorage.setItem('expiresAt', String(Date.now() - 1000));

    expect(guard.canActivate(route, state)).toBeFalse();
  });

  it('allows navigation with a non-expired token', () => {
    localStorage.setItem('accessToken', 'token');
    localStorage.setItem('expiresAt', String(Date.now() + 60_000));

    expect(guard.canActivate(route, state)).toBeTrue();
    expect(router.navigate).not.toHaveBeenCalled();
  });
});
