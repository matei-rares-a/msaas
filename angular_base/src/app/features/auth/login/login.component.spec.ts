import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { LoginComponent } from './login.component';
import { authResponse } from '../../../../testing/sessions';

const LOGIN_URL = 'http://localhost:8080/auth/login';

interface Page {
  fixture: ComponentFixture<LoginComponent>;
  http: HttpTestingController;
  router: Router;
  submit: (email?: string, password?: string) => void;
}

function openLoginPage(queryParams: Record<string, string> = {}): Page {
  localStorage.clear();
  TestBed.configureTestingModule({
    imports: [LoginComponent],
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideRouter([]),
      { provide: ActivatedRoute, useValue: { snapshot: { queryParams, paramMap: convertToParamMap({}) } } }
    ]
  });
  const router = TestBed.inject(Router);
  spyOn(router, 'navigate').and.resolveTo(true);
  spyOn(router, 'navigateByUrl').and.resolveTo(true);
  const fixture = TestBed.createComponent(LoginComponent);
  fixture.detectChanges();
  return {
    fixture,
    router,
    http: TestBed.inject(HttpTestingController),
    submit: (email = 'a@test.local', password = 'password-12345') => {
      fixture.componentInstance.form.setValue({ email, password });
      fixture.componentInstance.onSubmit();
    }
  };
}

describe('REQ-AUTH-01 the login page signs a one-tenant user straight in', () => {
  let page: Page;

  afterEach(() => {
    page.http.verify();
    localStorage.clear();
  });

  it('goes to the page it was asked for after a direct token', () => {
    page = openLoginPage({ returnUrl: '/billing/42' });

    page.submit();
    page.http.expectOne(LOGIN_URL).flush(authResponse('tenant-a'));

    expect(page.router.navigateByUrl).toHaveBeenCalledWith('/billing/42');
  });

  it('goes to the dashboard when no page was asked for', () => {
    page = openLoginPage();

    page.submit();
    page.http.expectOne(LOGIN_URL).flush(authResponse('tenant-a'));

    expect(page.router.navigateByUrl).toHaveBeenCalledWith('/dashboard');
  });

  it('does not call the service with an invalid form', () => {
    page = openLoginPage();

    page.submit('not-an-email', 'x');

    page.http.expectNone(LOGIN_URL);
  });

  it('shows why a login failed and lets the user try again', () => {
    page = openLoginPage();

    page.submit();
    page.http.expectOne(LOGIN_URL).flush({ message: 'Invalid credentials' }, { status: 401, statusText: 'Unauthorized' });
    page.fixture.detectChanges();

    expect(page.fixture.nativeElement.textContent).toContain('Invalid credentials');
    expect(page.fixture.componentInstance.loading).toBeFalse();
  });

  it('says what happened after registering, creating an organization or joining one', () => {
    for (const [notice, text] of [['registered', 'account is created'], ['created', 'organization is created'], ['joined', 'joined the organization']]) {
      TestBed.resetTestingModule();
      page = openLoginPage({ notice });
      expect(page.fixture.nativeElement.querySelector('[data-testid=login-notice]').textContent).toContain(text);
      page.http.verify();
    }
  });

  it('shows no notice for one it does not know', () => {
    page = openLoginPage({ notice: '<script>alert(1)</script>' });

    expect(page.fixture.nativeElement.querySelector('[data-testid=login-notice]')).toBeNull();
  });
});

describe('REQ-AUTH-02 the login page sends a several-tenant user to choose', () => {
  it('goes to the tenant choice, with no token yet', () => {
    const page = openLoginPage();

    page.submit();
    page.http.expectOne(LOGIN_URL).flush({
      tenants: [{ tenantId: 't1', role: 'ADMIN' }, { tenantId: 't2', role: 'USER' }], loginSessionId: 'opaque'
    });

    expect(page.router.navigate).toHaveBeenCalledWith(['/select-tenant']);
    expect(page.router.navigateByUrl).not.toHaveBeenCalled();
    expect(localStorage.getItem('msaas.sessions')).toBeNull();
    page.http.verify();
    localStorage.clear();
  });
});

describe('REQ-AUTH-03 the login page sends a no-tenant user to create or join one', () => {
  it('goes to the page for creating an organization or accepting an invitation', () => {
    const page = openLoginPage();

    page.submit();
    page.http.expectOne(LOGIN_URL).flush({ tenants: [], identityToken: 'identity-token' });

    expect(page.router.navigate).toHaveBeenCalledWith(['/no-organization']);
    expect(localStorage.getItem('msaas.sessions')).toBeNull();
    page.http.verify();
    localStorage.clear();
  });
});
