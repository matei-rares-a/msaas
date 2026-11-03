import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { AppComponent } from './app.component';
import { AuthService } from './core/services/auth.service';
import { ThemeService } from './core/services/theme.service';
import { authResponse } from '../testing/sessions';

const variable = (name: string) => document.documentElement.style.getPropertyValue(name);
const theme = (primary: string) => ({
  paletteId: 'p', palette: { id: 'p', name: 'P', kind: 'COLOR', definition: { primary, secondary: '#111111', accent: '#222222' } }, logoUrl: null
});

describe('REQ-PERS-06 the app applies the theme of the tenant it is opened for', () => {
  let http: HttpTestingController;
  let auth: AuthService;

  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({
      imports: [AppComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])]
    });
    http = TestBed.inject(HttpTestingController);
    auth = TestBed.inject(AuthService);
  });

  afterEach(() => {
    TestBed.inject(ThemeService).clear();
    localStorage.clear();
  });

  function signIn(tenantId: string) {
    auth.login({ email: 'a@test.local', password: 'password-12345' }).subscribe();
    http.expectOne('http://localhost:8080/auth/login').flush(authResponse(tenantId));
  }

  it('reads and applies the theme when it opens already signed in', () => {
    signIn('tenant-a');

    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();
    http.expectOne('http://localhost:8080/organizations/tenant-a/theme').flush(theme('#15803D'));

    expect(variable('--primary-color')).toBe('#15803D');
    http.verify();
  });

  it('reads the new tenant\'s theme when the active tenant changes, and clears it at logout', () => {
    signIn('tenant-a');
    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();
    http.expectOne('http://localhost:8080/organizations/tenant-a/theme').flush(theme('#15803D'));

    signIn('tenant-b');
    http.expectOne('http://localhost:8080/organizations/tenant-b/theme').flush(theme('#0369A1'));
    expect(variable('--primary-color')).toBe('#0369A1');

    auth.logout();
    expect(variable('--primary-color')).toBe('');
    http.verify();
  });

  it('keeps the default look, and the app working, when the theme cannot be read', () => {
    signIn('tenant-a');
    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();

    http.expectOne('http://localhost:8080/organizations/tenant-a/theme').flush({}, { status: 500, statusText: 'Server Error' });
    fixture.detectChanges();

    expect(variable('--primary-color')).toBe('');
    expect(fixture.nativeElement.querySelector('[data-testid=tenant-name]').textContent).toContain('Tenant tenant-a');
    http.verify();
  });

  it('offers the theme editor only to an ADMIN with write access', () => {
    signIn('tenant-a');
    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();
    http.expectOne('http://localhost:8080/organizations/tenant-a/theme').flush(theme('#15803D'));
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[data-testid=nav-theme]')).not.toBeNull();

    auth.login({ email: 'a@test.local', password: 'password-12345' }).subscribe();
    http.expectOne('http://localhost:8080/auth/login').flush(authResponse('tenant-c', 'user-1', 'USER'));
    http.expectOne('http://localhost:8080/organizations/tenant-c/theme').flush(theme('#0369A1'));
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('[data-testid=nav-theme]')).toBeNull();
    http.verify();
  });
});
