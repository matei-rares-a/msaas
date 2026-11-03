import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { LeaveComponent } from './leave.component';
import { signInAs } from '../../../testing/sessions';

const API = 'http://localhost:8080/organizations/t/leave';
const balance = { userId: 'u', year: 2026, allocated: 21, approved: 5, pending: 2, available: 14 };
const request = (id: string, status: string, extra: object = {}) =>
  ({ id, userId: 'u', type: 'ODIHNA', startDate: '2026-12-01', endDate: '2026-12-03', days: 3, reason: null, status, autoApproved: false, decisionComment: null, decidedBy: null, createdAt: '2026-11-01T00:00:00Z', ...extra });

function open(role: string, readOnly = false, requests: object[] = [request('r1', 'PENDING'), request('r2', 'REJECTED', { decisionComment: 'busy week' })]) {
  signInAs(role, readOnly);
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({ imports: [LeaveComponent], providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])] });
  const http = TestBed.inject(HttpTestingController);
  const fixture: ComponentFixture<LeaveComponent> = TestBed.createComponent(LeaveComponent);
  fixture.detectChanges();
  const answer = (requestsNow: object[]) => {
    http.expectOne(r => r.url === `${API}/balance`).flush(balance);
    http.expectOne(r => r.url === `${API}/requests` && r.params.get('scope') === 'MINE').flush({ items: requestsNow, page: 0, size: 20, totalItems: requestsNow.length });
    http.expectOne(r => r.url === `${API}/calendar`).flush([{ userId: 'u', startDate: '2026-12-01', endDate: '2026-12-03' }, { userId: 'bbbbbbbb-2222', startDate: '2026-12-10', endDate: '2026-12-11' }]);
    fixture.detectChanges();
  };
  answer(requests);
  const el = (id: string) => fixture.nativeElement.querySelector(`[data-testid="${id}"]`) as HTMLElement | null;
  return { fixture, http, el, answer };
}

describe('REQ-LEAVE-03 the member sees their balance', () => {
  afterEach(() => localStorage.clear());

  it('shows allowed, approved, waiting and available days', () => {
    const { el } = open('USER');

    expect(el('balance-allocated')!.textContent).toBe('21');
    expect(el('balance-approved')!.textContent).toBe('5');
    expect(el('balance-pending')!.textContent).toBe('2');
    expect(el('balance-available')!.textContent).toBe('14');
  });
});

describe('REQ-LEAVE-01 a member asks for leave for themselves', () => {
  afterEach(() => localStorage.clear());

  it('sends the type, the first and the last day, and the reason, and never a user', () => {
    const { fixture, http, answer, el } = open('USER');
    fixture.componentInstance.form.setValue({ type: 'FARA_PLATA', startDate: '2026-12-07', endDate: '2026-12-08', reason: ' family ' });

    fixture.componentInstance.submit();
    const created = http.expectOne(`${API}/requests`);
    expect(created.request.method).toBe('POST');
    expect(created.request.body).toEqual({ type: 'FARA_PLATA', startDate: '2026-12-07', endDate: '2026-12-08', reason: 'family' });
    expect(JSON.stringify(created.request.body)).not.toContain('userId');
    created.flush(request('r3', 'PENDING'));
    answer([request('r1', 'PENDING')]);

    expect(el('leave-message')!.textContent).toContain('sent for a decision');
  });

  it('says so when the leave was approved at once', () => {
    const { fixture, http, answer, el } = open('MANAGER');
    fixture.componentInstance.form.setValue({ type: 'ODIHNA', startDate: '2026-12-07', endDate: '2026-12-08', reason: '' });

    fixture.componentInstance.submit();
    http.expectOne(`${API}/requests`).flush(request('r3', 'APPROVED', { autoApproved: true }));
    answer([]);

    expect(el('leave-message')!.textContent).toContain('approved');
  });

  it('does not send a request that ends before it starts, or an empty one', () => {
    const { fixture, http, el } = open('USER');

    fixture.componentInstance.form.setValue({ type: 'ODIHNA', startDate: '2026-12-09', endDate: '2026-12-08', reason: '' });
    fixture.componentInstance.submit();
    fixture.detectChanges();
    expect(el('leave-error')!.textContent).toContain('cannot be before');

    fixture.componentInstance.form.setValue({ type: 'ODIHNA', startDate: '', endDate: '', reason: '' });
    fixture.componentInstance.submit();

    http.expectNone(`${API}/requests`);
  });

  it('shows the server\'s reason when the request is refused', () => {
    const { fixture, http, el } = open('USER');
    fixture.componentInstance.form.setValue({ type: 'ODIHNA', startDate: '2026-12-07', endDate: '2026-12-30', reason: '' });

    fixture.componentInstance.submit();
    http.expectOne(`${API}/requests`).flush({ message: 'not enough days left' }, { status: 409, statusText: 'Conflict' });
    fixture.detectChanges();

    expect(el('leave-error')!.textContent).toContain('not enough days left');
  });
});

describe('REQ-ROLE-06 a read-only member asks for no leave', () => {
  afterEach(() => localStorage.clear());

  it('shows the requests and the calendar but no form and no cancel button', () => {
    const { el, fixture } = open('MANAGER', true, [request('r1', 'PENDING')]);

    expect(el('leave-form')).toBeNull();
    expect(el('leave-readonly')).not.toBeNull();
    expect(el('cancel-request')).toBeNull();
    expect(fixture.nativeElement.querySelectorAll('[data-testid=request]').length).toBe(1);
    expect(fixture.nativeElement.querySelectorAll('[data-testid=calendar-entry]').length).toBe(2);
  });
});

describe('REQ-LEAVE-04 a request can be cancelled while it is open or approved', () => {
  afterEach(() => localStorage.clear());

  it('offers cancel for a pending and an approved request only', () => {
    const { fixture } = open('USER', false, [request('r1', 'PENDING'), request('r2', 'APPROVED'), request('r3', 'REJECTED'), request('r4', 'CANCELLED')]);

    expect(fixture.nativeElement.querySelectorAll('[data-testid=cancel-request]').length).toBe(2);
  });

  it('cancels with the one call and refreshes', () => {
    const { http, answer, el } = open('USER', false, [request('r1', 'APPROVED')]);

    el('cancel-request')!.click();
    const cancelled = http.expectOne(`${API}/requests/r1/cancel`);
    expect(cancelled.request.method).toBe('POST');
    cancelled.flush(request('r1', 'CANCELLED'));
    answer([request('r1', 'CANCELLED')]);

    expect(el('leave-message')!.textContent).toContain('days are back');
    expect(el('cancel-request')).toBeNull();
  });

  it('shows why a cancellation was refused', () => {
    const { http, fixture, el } = open('USER', false, [request('r1', 'APPROVED')]);

    el('cancel-request')!.click();
    http.expectOne(`${API}/requests/r1/cancel`).flush({ message: 'the leave has begun' }, { status: 409, statusText: 'Conflict' });
    fixture.detectChanges();

    expect(el('leave-error')!.textContent).toContain('the leave has begun');
  });
});

describe('REQ-LEAVE-06 the team calendar says who is away and nothing about why', () => {
  afterEach(() => localStorage.clear());

  it('shows the dates and who, marking the member as "You", and never a type or a reason', () => {
    const { fixture } = open('USER');

    const entries = Array.from(fixture.nativeElement.querySelectorAll('[data-testid=calendar-entry]')) as HTMLElement[];
    expect(entries.map(entry => Array.from(entry.querySelectorAll('span')).map(span => span.textContent!.trim()))).toEqual([
      ['You', '2026-12-01 – 2026-12-03'], ['bbbbbbbb', '2026-12-10 – 2026-12-11']
    ]);
  });

  it('asks for a calendar window that covers this month and the next', () => {
    signInAs('USER');
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({ imports: [LeaveComponent], providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])] });
    const http = TestBed.inject(HttpTestingController);
    TestBed.createComponent(LeaveComponent).detectChanges();

    const calendar = http.expectOne(r => r.url === `${API}/calendar`);
    const from = calendar.request.params.get('from')!;
    const to = calendar.request.params.get('to')!;
    expect(from).toMatch(/^\d{4}-\d{2}-01$/);
    expect(to >= from).toBeTrue();
    http.match(() => true).forEach(r => r.flush([]));
  });
});

describe('REQ-LEAVE-05 the links to deciding and to the policy follow the role', () => {
  afterEach(() => localStorage.clear());

  it('shows approvals to a MANAGER, approvals and policy to an ADMIN, and neither to a USER', () => {
    expect(open('MANAGER').el('link-approvals')).not.toBeNull();
    expect(open('MANAGER').el('link-policy')).toBeNull();
    const admin = open('ADMIN');
    expect(admin.el('link-approvals') && admin.el('link-policy')).toBeTruthy();
    const user = open('USER');
    expect(user.el('link-approvals')).toBeNull();
    expect(user.el('link-policy')).toBeNull();
  });
});
