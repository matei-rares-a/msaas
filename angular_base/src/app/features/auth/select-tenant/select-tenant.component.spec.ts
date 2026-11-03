import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Router, provideRouter } from '@angular/router';
import { SelectTenantComponent } from './select-tenant.component';
import { AuthService } from '../../../core/services/auth.service';
import { authResponse } from '../../../../testing/sessions';

describe('REQ-AUTH-02 choosing the tenant to sign in to', () => {
  let http: HttpTestingController;
  let auth: AuthService;
  let router: Router;

  function open() {
    localStorage.clear();
    TestBed.configureTestingModule({
      imports: [SelectTenantComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])]
    });
    http = TestBed.inject(HttpTestingController);
    auth = TestBed.inject(AuthService);
    router = TestBed.inject(Router);
    spyOn(router, 'navigateByUrl').and.resolveTo(true);
    spyOn(router, 'navigate').and.resolveTo(true);
    auth.login({ email: 'a@test.local', password: 'password-12345' }).subscribe();
    http.expectOne('http://localhost:8080/auth/login').flush({
      tenants: [{ tenantId: 'aaaaaaaa-1111', role: 'ADMIN' }, { tenantId: 'bbbbbbbb-2222', role: 'USER' }], loginSessionId: 'opaque'
    });
    const fixture = TestBed.createComponent(SelectTenantComponent);
    fixture.detectChanges();
    return fixture;
  }

  afterEach(() => {
    http.verify();
    localStorage.clear();
  });

  it('lists every tenant of the user with their role there', () => {
    const fixture = open();

    const options = fixture.nativeElement.querySelectorAll('[data-testid=tenant-option]');
    expect(options.length).toBe(2);
    expect(options[0].textContent).toContain('aaaaaaaa');
    expect(options[0].textContent).toContain('ADMIN');
    expect(options[1].textContent).toContain('USER');
  });

  it('signs in to the one chosen and goes to the dashboard', () => {
    const fixture = open();

    fixture.nativeElement.querySelectorAll('[data-testid=tenant-option]')[1].click();
    const request = http.expectOne('http://localhost:8080/auth/login/tenant');
    expect(request.request.body).toEqual({ loginSessionId: 'opaque', tenantId: 'bbbbbbbb-2222' });
    request.flush(authResponse('bbbbbbbb-2222', 'user-1', 'USER'));

    expect(auth.getActiveTenantId()).toBe('bbbbbbbb-2222');
    expect(router.navigateByUrl).toHaveBeenCalledWith('/dashboard');
  });

  it('sends the user back to log in again when the choice no longer works', () => {
    const fixture = open();

    fixture.nativeElement.querySelectorAll('[data-testid=tenant-option]')[0].click();
    http.expectOne('http://localhost:8080/auth/login/tenant').flush({ message: 'expired' }, { status: 400, statusText: 'Bad Request' });

    expect(router.navigate).toHaveBeenCalledWith(['/login'], { queryParams: { notice: 'choice-expired' } });
    expect(auth.hasValidToken()).toBeFalse();
  });
});
