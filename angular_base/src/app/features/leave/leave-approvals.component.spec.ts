import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { LeaveApprovalsComponent } from './leave-approvals.component';
import { LeavePolicyComponent } from './leave-policy.component';
import { signInAs } from '../../../testing/sessions';

const API = 'http://localhost:8080/organizations/t/leave';
const pending = (id: string, userId: string, reason: string | null = null) =>
  ({ id, userId, type: 'ODIHNA', startDate: '2026-12-01', endDate: '2026-12-03', days: 3, reason, status: 'PENDING', autoApproved: false, decisionComment: null, decidedBy: null, createdAt: '2026-11-01T00:00:00Z' });

describe('REQ-LEAVE-05 deciding leave requests', () => {
  let fixture: ComponentFixture<LeaveApprovalsComponent>;
  let http: HttpTestingController;
  const el = (id: string) => fixture.nativeElement.querySelector(`[data-testid="${id}"]`) as HTMLElement | null;

  function open(items: object[]) {
    signInAs('MANAGER', false, 'me-1111');
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({ imports: [LeaveApprovalsComponent], providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])] });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(LeaveApprovalsComponent);
    fixture.detectChanges();
    const list = http.expectOne(r => r.url === `${API}/requests`);
    expect(list.request.params.get('scope')).toBe('ALL');
    expect(list.request.params.get('status')).toBe('PENDING');
    list.flush({ items, page: 0, size: 20, totalItems: items.length });
    fixture.detectChanges();
  }

  afterEach(() => {
    http.verify();
    localStorage.clear();
  });

  it('lists what waits for a decision, with the reason, which only the people who decide see', () => {
    open([pending('r1', 'aaaaaaaa-1', 'family trip')]);

    expect(fixture.nativeElement.querySelectorAll('[data-testid=pending-request]').length).toBe(1);
    expect(el('request-reason')!.textContent).toContain('family trip');
  });

  it('says so when nothing waits', () => {
    open([]);

    expect(el('no-pending')).not.toBeNull();
  });

  it('approves with the comment and drops the request from the list', () => {
    open([pending('r1', 'aaaaaaaa-1')]);
    fixture.componentInstance.comments['r1'] = ' enjoy ';

    el('approve')!.click();
    const approved = http.expectOne(`${API}/requests/r1/approve`);
    expect(approved.request.method).toBe('POST');
    expect(approved.request.body).toEqual({ comment: 'enjoy' });
    approved.flush({ ...pending('r1', 'aaaaaaaa-1'), status: 'APPROVED' });
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelectorAll('[data-testid=pending-request]').length).toBe(0);
    expect(el('approvals-message')!.textContent).toContain('approved');
  });

  it('rejects, with no comment when none was written', () => {
    open([pending('r1', 'aaaaaaaa-1')]);

    el('reject')!.click();
    const rejected = http.expectOne(`${API}/requests/r1/reject`);
    expect(rejected.request.body).toEqual({ comment: null });
    rejected.flush({ ...pending('r1', 'aaaaaaaa-1'), status: 'REJECTED' });
    fixture.detectChanges();

    expect(el('approvals-message')!.textContent).toContain('rejected');
  });

  it('offers no decision on the approver\'s own request, only why', () => {
    open([pending('r1', 'me-1111')]);

    expect(el('approve')).toBeNull();
    expect(el('reject')).toBeNull();
    expect(el('own-request')).not.toBeNull();
  });

  it('shows the server\'s refusal and keeps the request listed', () => {
    open([pending('r1', 'aaaaaaaa-1')]);

    el('approve')!.click();
    http.expectOne(`${API}/requests/r1/approve`).flush({ message: 'already decided' }, { status: 409, statusText: 'Conflict' });
    fixture.detectChanges();

    expect(el('approvals-error')!.textContent).toContain('already decided');
    expect(fixture.nativeElement.querySelectorAll('[data-testid=pending-request]').length).toBe(1);
  });
});

describe('REQ-LEAVE-02 the ADMIN sets the policy, the allowances and the days off', () => {
  let fixture: ComponentFixture<LeavePolicyComponent>;
  let http: HttpTestingController;
  const el = (id: string) => fixture.nativeElement.querySelector(`[data-testid="${id}"]`) as HTMLElement | null;

  beforeEach(async () => {
    signInAs('ADMIN');
    TestBed.configureTestingModule({ imports: [LeavePolicyComponent], providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])] });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(LeavePolicyComponent);
    fixture.detectChanges();
    http.expectOne(`${API}/policy`).flush({ defaultAllowance: 21 });
    http.expectOne(r => r.url === `${API}/holidays`).flush([{ date: '2026-12-25', name: 'Christmas' }]);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  });

  afterEach(() => {
    http.verify();
    localStorage.clear();
  });

  it('shows the default allowance and the days off', () => {
    expect((el('default-allowance') as HTMLInputElement).value).toBe('21');
    expect(fixture.nativeElement.querySelectorAll('[data-testid=holiday]').length).toBe(1);
  });

  it('saves a new default allowance', () => {
    fixture.componentInstance.allowance = 25;

    fixture.componentInstance.savePolicy();
    const saved = http.expectOne(`${API}/policy`);
    expect(saved.request.method).toBe('PUT');
    expect(saved.request.body).toEqual({ allowance: 25 });
    saved.flush({ defaultAllowance: 25 });
    fixture.detectChanges();

    expect(el('policy-message')!.textContent).toContain('default allowance');
  });

  it('does not save a negative or an empty allowance', () => {
    fixture.componentInstance.allowance = -1;
    fixture.componentInstance.savePolicy();
    fixture.componentInstance.allowance = null;
    fixture.componentInstance.savePolicy();
    fixture.detectChanges();

    http.expectNone(`${API}/policy`);
    expect(el('policy-error')).not.toBeNull();
  });

  it('sets and clears the allowance of one member', () => {
    fixture.componentInstance.overrideUserId = ' member-1 ';
    fixture.componentInstance.overrideAllowance = 30;

    fixture.componentInstance.saveOverride();
    const set = http.expectOne(`${API}/balances/member-1`);
    expect(set.request.method).toBe('PUT');
    expect(set.request.body).toEqual({ allowance: 30 });
    set.flush(null);

    fixture.componentInstance.clearOverride();
    const cleared = http.expectOne(`${API}/balances/member-1`);
    expect(cleared.request.method).toBe('DELETE');
    cleared.flush(null);
  });

  it('adds a day off and reloads the list, and removes one', () => {
    fixture.componentInstance.holidayDate = '2026-12-24';
    fixture.componentInstance.holidayName = ' Christmas Eve ';

    fixture.componentInstance.addHoliday();
    const added = http.expectOne(`${API}/holidays`);
    expect(added.request.method).toBe('POST');
    expect(added.request.body).toEqual({ date: '2026-12-24', name: 'Christmas Eve' });
    added.flush({ date: '2026-12-24', name: 'Christmas Eve' });
    http.expectOne(r => r.url === `${API}/holidays` && r.method === 'GET').flush([{ date: '2026-12-24', name: 'Christmas Eve' }, { date: '2026-12-25', name: 'Christmas' }]);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelectorAll('[data-testid=holiday]').length).toBe(2);

    fixture.componentInstance.removeHoliday({ date: '2026-12-24', name: 'Christmas Eve' });
    const removed = http.expectOne(`${API}/holidays/2026-12-24`);
    expect(removed.request.method).toBe('DELETE');
    removed.flush(null);
    http.expectOne(r => r.url === `${API}/holidays` && r.method === 'GET').flush([{ date: '2026-12-25', name: 'Christmas' }]);
  });

  it('shows the server\'s refusal', () => {
    fixture.componentInstance.allowance = 10;

    fixture.componentInstance.savePolicy();
    http.expectOne(`${API}/policy`).flush({ message: 'only an ADMIN' }, { status: 403, statusText: 'Forbidden' });
    fixture.detectChanges();

    expect(el('policy-error')!.textContent).toContain('only an ADMIN');
  });
});
