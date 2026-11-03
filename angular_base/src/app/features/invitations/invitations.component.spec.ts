import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { InvitationsComponent } from './invitations.component';
import { fakeJwt } from '../../../testing/sessions';

const API = 'http://localhost:8080/organizations/t/invitations';
const pending = { id: 'inv1', email: 'a@x.com', role: 'USER', status: 'PENDING', expiresAt: '2026-11-10T00:00:00Z' };

function openAs(role: string, readOnly = false) {
  localStorage.clear();
  TestBed.resetTestingModule();
  const token = fakeJwt({ sub: 'u', tenantId: 't', tenantName: 'T', displayName: 'd', role, readOnly });
  localStorage.setItem('msaas.sessions', JSON.stringify({ t: { accessToken: token, refreshToken: 'r', expiresAt: Date.now() + 60_000 } }));
  localStorage.setItem('msaas.activeTenant', 't');
  TestBed.configureTestingModule({ imports: [InvitationsComponent], providers: [provideHttpClient(), provideHttpClientTesting()] });
  const http = TestBed.inject(HttpTestingController);
  const fixture: ComponentFixture<InvitationsComponent> = TestBed.createComponent(InvitationsComponent);
  fixture.detectChanges();
  http.expectOne(API).flush([pending]);
  fixture.detectChanges();
  const el = (id: string) => fixture.nativeElement.querySelector(`[data-testid="${id}"]`) as HTMLElement | null;
  const roles = () => Array.from((el('invite-role') as HTMLSelectElement).options).map(option => option.value);
  return { fixture, http, el, roles };
}

describe('REQ-ROLE-05 a USER sends no invitations', () => {
  afterEach(() => localStorage.clear());

  it('shows a USER no form and no revoke button, only why', () => {
    const { el } = openAs('USER');

    expect(el('invite-form')).toBeNull();
    expect(el('revoke')).toBeNull();
    expect(el('invite-unavailable')).not.toBeNull();
  });
});

describe('REQ-ROLE-06 a read-only member sends no invitations whatever their role', () => {
  afterEach(() => localStorage.clear());

  it('shows a read-only ADMIN the list and says why there is no form', () => {
    const { el, fixture } = openAs('ADMIN', true);

    expect(el('invite-form')).toBeNull();
    expect(el('revoke')).toBeNull();
    expect(el('invite-unavailable')?.textContent).toContain('read-only');
    expect(fixture.nativeElement.querySelectorAll('[data-testid=invitation]').length).toBe(1);
  });
});

describe('REQ-MGR-01 invitations: an ADMIN with any role, a MANAGER only with USER', () => {
  afterEach(() => localStorage.clear());

  it('offers an ADMIN every role and a MANAGER only USER', () => {
    expect(openAs('ADMIN').roles().sort()).toEqual(['ADMIN', 'MANAGER', 'USER']);
    expect(openAs('MANAGER').roles()).toEqual(['USER']);
  });

  it('sends the invitation with the email and the role, and lists it', () => {
    const { fixture, http, el } = openAs('ADMIN');
    fixture.componentInstance.form.setValue({ email: 'new@x.com', role: 'MANAGER' });

    fixture.componentInstance.invite();
    const request = http.expectOne(API);
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({ email: 'new@x.com', role: 'MANAGER' });
    request.flush({ id: 'inv2', email: 'new@x.com', role: 'MANAGER', status: 'PENDING', expiresAt: '2026-11-10T00:00:00Z' });
    fixture.detectChanges();

    expect(el('invitations-message')?.textContent).toContain('new@x.com');
    expect(fixture.nativeElement.querySelectorAll('[data-testid=invitation]').length).toBe(2);
  });

  it('does not send a role the user may not give, even when it is forced into the form', () => {
    const { fixture, http } = openAs('MANAGER');
    fixture.componentInstance.form.setValue({ email: 'x@x.com', role: 'ADMIN' });

    fixture.componentInstance.invite();

    http.expectNone(API);
  });

  it('does not send an invalid email', () => {
    const { fixture, http } = openAs('ADMIN');
    fixture.componentInstance.form.setValue({ email: 'not-an-email', role: 'USER' });

    fixture.componentInstance.invite();

    http.expectNone(API);
  });

  it('shows the server\'s refusal', () => {
    const { fixture, http, el } = openAs('ADMIN');
    fixture.componentInstance.form.setValue({ email: 'x@x.com', role: 'USER' });

    fixture.componentInstance.invite();
    http.expectOne(API).flush({ message: 'plan limit reached' }, { status: 409, statusText: 'Conflict' });
    fixture.detectChanges();

    expect(el('invitations-error')?.textContent).toContain('plan limit reached');
  });

  it('revokes a pending invitation and drops it from the list', () => {
    const { fixture, http, el } = openAs('MANAGER');

    el('revoke')!.click();
    const request = http.expectOne(`${API}/inv1`);
    expect(request.request.method).toBe('DELETE');
    request.flush(null, { status: 204, statusText: 'No Content' });
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelectorAll('[data-testid=invitation]').length).toBe(0);
  });
});
