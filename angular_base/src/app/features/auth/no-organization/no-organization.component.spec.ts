import { TestBed } from '@angular/core/testing';
import { provideHttpClient, HTTP_INTERCEPTORS, withInterceptorsFromDi } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Router, provideRouter } from '@angular/router';
import { NoOrganizationComponent } from './no-organization.component';
import { AuthService } from '../../../core/services/auth.service';
import { AuthInterceptor } from '../../../core/interceptors/auth.interceptor';

describe('REQ-AUTH-03 a user with no organization creates one or accepts an invitation', () => {
  let http: HttpTestingController;
  let auth: AuthService;
  let router: Router;

  function open() {
    localStorage.clear();
    TestBed.configureTestingModule({
      imports: [NoOrganizationComponent],
      providers: [
        provideHttpClient(withInterceptorsFromDi()),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: HTTP_INTERCEPTORS, useClass: AuthInterceptor, multi: true }
      ]
    });
    http = TestBed.inject(HttpTestingController);
    auth = TestBed.inject(AuthService);
    router = TestBed.inject(Router);
    spyOn(router, 'navigate').and.resolveTo(true);
    auth.login({ email: 'a@test.local', password: 'password-12345' }).subscribe();
    http.expectOne('http://localhost:8080/auth/login').flush({ tenants: [], identityToken: 'identity-token' });
    const fixture = TestBed.createComponent(NoOrganizationComponent);
    fixture.detectChanges();
    return fixture;
  }

  afterEach(() => {
    http.verify();
    localStorage.clear();
  });

  it('creates an organization with the identity token, then sends the user to log in again', () => {
    const fixture = open();

    fixture.componentInstance.createForm.setValue({ name: '  Acme  ' });
    fixture.componentInstance.createOrganization();
    const request = http.expectOne('http://localhost:8080/organizations');
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({ name: 'Acme' });
    expect(request.request.headers.get('Authorization')).toBe('Bearer identity-token');
    request.flush({ id: 'new-tenant', name: 'Acme' });

    expect(auth.getIdentityToken()).toBeNull();
    expect(router.navigate).toHaveBeenCalledWith(['/login'], { queryParams: { notice: 'created' } });
  });

  it('accepts an invitation with the identity token, then sends the user to log in again', () => {
    const fixture = open();

    fixture.componentInstance.acceptForm.setValue({ token: ' invite-code ' });
    fixture.componentInstance.acceptInvitation();
    const request = http.expectOne('http://localhost:8080/invitations/accept');
    expect(request.request.body).toEqual({ token: 'invite-code' });
    expect(request.request.headers.get('Authorization')).toBe('Bearer identity-token');
    request.flush({ tenantId: 'tenant-a', role: 'USER' });

    expect(auth.getIdentityToken()).toBeNull();
    expect(router.navigate).toHaveBeenCalledWith(['/login'], { queryParams: { notice: 'joined' } });
  });

  it('says why an invitation was refused and keeps the identity token for another try', () => {
    const fixture = open();

    fixture.componentInstance.acceptForm.setValue({ token: 'old-code' });
    fixture.componentInstance.acceptInvitation();
    http.expectOne('http://localhost:8080/invitations/accept').flush({ message: 'invitation has expired' }, { status: 409, statusText: 'Conflict' });
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('[data-testid=onboarding-error]').textContent).toContain('invitation has expired');
    expect(auth.getIdentityToken()).toBe('identity-token');
    expect(router.navigate).not.toHaveBeenCalled();
  });

  it('asks for nothing it was not given: an empty name or code sends nothing', () => {
    const fixture = open();

    fixture.componentInstance.createOrganization();
    fixture.componentInstance.acceptInvitation();

    http.expectNone('http://localhost:8080/organizations');
    http.expectNone('http://localhost:8080/invitations/accept');
  });
});
