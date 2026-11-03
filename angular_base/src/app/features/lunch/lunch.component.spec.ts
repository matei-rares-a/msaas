import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { LunchComponent } from './lunch.component';
import { signInAs } from '../../../testing/sessions';

const API = 'http://localhost:8080/organizations/t/lunch';
const round = (extra: object = {}) =>
  ({ id: 'rd1', weekStart: '2026-12-07', deadlineAt: '2026-12-10T09:00:00Z', status: 'OPEN', cancelReason: null, participantCount: 3, enrolled: false, ...extra });
const config = { day: 'THURSDAY', cutoff: '11:00', groupSize: 2, timezone: 'Europe/Bucharest' };

interface Setup {
  current?: object;
  rounds?: object[];
  detail?: object;
  role?: string;
  readOnly?: boolean;
}

function open(setup: Setup = {}) {
  signInAs(setup.role ?? 'USER', setup.readOnly ?? false, 'me-1111');
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({ imports: [LunchComponent], providers: [provideHttpClient(), provideHttpClientTesting()] });
  const http = TestBed.inject(HttpTestingController);
  const fixture: ComponentFixture<LunchComponent> = TestBed.createComponent(LunchComponent);
  fixture.detectChanges();
  http.expectOne(`${API}/rounds/current`).flush(setup.current ?? round());
  http.expectOne(r => r.url === `${API}/rounds`).flush({ items: setup.rounds ?? [], page: 0, size: 5, totalItems: (setup.rounds ?? []).length });
  http.expectOne(`${API}/config`).flush(config);
  const formed = (setup.rounds ?? []).find((item: any) => item.status === 'FORMED') as any;
  if (setup.detail && formed) {
    http.expectOne(`${API}/rounds/${formed.id}`).flush(setup.detail);
  }
  fixture.detectChanges();
  const el = (id: string) => fixture.nativeElement.querySelector(`[data-testid="${id}"]`) as HTMLElement | null;
  return { fixture, http, el };
}

describe('REQ-LUNCH-02 joining and leaving the week\'s round', () => {
  afterEach(() => localStorage.clear());

  it('shows the round, how many joined and the way to join', () => {
    const { el } = open();

    expect(el('round-status')!.textContent).toBe('OPEN');
    expect(el('round-count')!.textContent).toContain('3 joined');
    expect(el('enroll')).not.toBeNull();
    expect(el('withdraw')).toBeNull();
  });

  it('joins with the one call and then offers to withdraw', () => {
    const { http, fixture, el } = open();

    el('enroll')!.click();
    const joined = http.expectOne(`${API}/rounds/current/enrollment`);
    expect(joined.request.method).toBe('PUT');
    joined.flush(round({ enrolled: true, participantCount: 4 }));
    fixture.detectChanges();

    expect(el('withdraw')).not.toBeNull();
    expect(el('enroll')).toBeNull();
    expect(el('enrolled')).not.toBeNull();
    expect(el('round-count')!.textContent).toContain('4 joined');
  });

  it('withdraws with the one call', () => {
    const { http, fixture, el } = open({ current: round({ enrolled: true }) });

    el('withdraw')!.click();
    const left = http.expectOne(`${API}/rounds/current/enrollment`);
    expect(left.request.method).toBe('DELETE');
    left.flush(round({ enrolled: false, participantCount: 2 }));
    fixture.detectChanges();

    expect(el('enroll')).not.toBeNull();
  });

  it('shows the server\'s reason when joining is refused, and keeps the state', () => {
    const { http, fixture, el } = open();

    el('enroll')!.click();
    http.expectOne(`${API}/rounds/current/enrollment`).flush({ message: 'the round was cancelled' }, { status: 409, statusText: 'Conflict' });
    fixture.detectChanges();

    expect(el('lunch-error')!.textContent).toContain('the round was cancelled');
    expect(el('enroll')).not.toBeNull();
  });

  it('offers no joining in a round that is not open, and says when it was cancelled', () => {
    const { el } = open({ current: round({ status: 'CANCELLED', cancelReason: 'TOO_FEW_PARTICIPANTS' }) });

    expect(el('enroll')).toBeNull();
    expect(el('round-cancelled')).not.toBeNull();
  });
});

describe('REQ-ROLE-06 a read-only member joins nothing', () => {
  afterEach(() => localStorage.clear());

  it('shows the round and no button, with the reason', () => {
    const { el } = open({ readOnly: true });

    expect(el('round-status')).not.toBeNull();
    expect(el('enroll')).toBeNull();
    expect(el('withdraw')).toBeNull();
    expect(el('lunch-readonly')).not.toBeNull();
  });
});

describe('REQ-LUNCH-04 a participant sees their own group, an approver sees all of them', () => {
  const detail = { round: round({ status: 'FORMED' }), myGroup: ['me-1111', 'bbbbbbbb-2222'], groups: [] as string[][] };
  const formedRound = round({ status: 'FORMED', enrolled: true });

  afterEach(() => localStorage.clear());

  it('looks for the groups in the latest formed round, because the current one is the next', () => {
    const { el, fixture } = open({ current: round({ id: 'next' }), rounds: [formedRound], detail });

    expect(el('formed-week')!.textContent).toContain('2026-12-07');
    const mine = Array.from(fixture.nativeElement.querySelectorAll('[data-testid=my-group] li')) as HTMLElement[];
    expect(mine.map(item => item.textContent!.trim())).toEqual(['You', 'bbbbbbbb']);
    expect(el('group')).toBeNull();
  });

  it('shows an approver every group', () => {
    const { fixture } = open({
      role: 'MANAGER',
      rounds: [round({ status: 'FORMED', enrolled: false })],
      detail: { round: round({ status: 'FORMED' }), myGroup: [], groups: [['aaaaaaaa-1', 'bbbbbbbb-2'], ['cccccccc-3', 'dddddddd-4', 'eeeeeeee-5']] }
    });

    expect(fixture.nativeElement.querySelectorAll('[data-testid=group]').length).toBe(2);
    expect(fixture.nativeElement.querySelector('[data-testid=group]').textContent).toContain('aaaaaaaa');
  });

  it('asks for no groups when the member was not in the round and cannot see them all', () => {
    const { el } = open({ rounds: [round({ status: 'FORMED', enrolled: false })] });

    expect(el('groups')).toBeNull();
  });
});

describe('REQ-LUNCH-01 MANAGER and ADMIN configure the round and can cancel it', () => {
  afterEach(() => localStorage.clear());

  it('shows the settings only to who may change them', () => {
    expect(open({ role: 'MANAGER' }).el('config')).not.toBeNull();
    expect(open({ role: 'ADMIN' }).el('config')).not.toBeNull();
    expect(open({ role: 'USER' }).el('config')).toBeNull();
    expect(open({ role: 'MANAGER', readOnly: true }).el('config')).toBeNull();
  });

  it('fills the form from the settings and saves a change', () => {
    const { fixture, http, el } = open({ role: 'MANAGER' });
    expect((el('config-day') as HTMLSelectElement).value).toBe('THURSDAY');
    expect((el('config-cutoff') as HTMLInputElement).value).toBe('11:00');

    fixture.componentInstance.form.setValue({ day: 'FRIDAY', cutoff: '10:30', groupSize: 3 });
    fixture.componentInstance.saveConfig();
    const saved = http.expectOne(`${API}/config`);
    expect(saved.request.method).toBe('PUT');
    expect(saved.request.body).toEqual({ day: 'FRIDAY', cutoff: '10:30', groupSize: 3 });
    saved.flush({ ...config, day: 'FRIDAY', cutoff: '10:30', groupSize: 3 });
    http.expectOne(`${API}/rounds/current`).flush(round());
    fixture.detectChanges();

    expect(el('lunch-message')!.textContent).toContain('settings are saved');
  });

  it('does not save a group size outside 2 to 4 or a time that is not HH:mm', () => {
    const { fixture, http } = open({ role: 'MANAGER' });

    fixture.componentInstance.form.setValue({ day: 'FRIDAY', cutoff: '10:30', groupSize: 5 });
    fixture.componentInstance.saveConfig();
    fixture.componentInstance.form.setValue({ day: 'FRIDAY', cutoff: '25:99', groupSize: 3 });
    fixture.componentInstance.saveConfig();

    http.expectNone(`${API}/config`);
  });

  it('cancels the round, and offers it only to who configures', () => {
    const { http, fixture, el } = open({ role: 'ADMIN' });
    expect(open({ role: 'USER' }).el('cancel-round')).toBeNull();

    const again = open({ role: 'ADMIN' });
    again.el('cancel-round')!.click();
    const cancelled = again.http.expectOne(`${API}/rounds/current/cancel`);
    expect(cancelled.request.method).toBe('POST');
    cancelled.flush(round({ status: 'CANCELLED', cancelReason: 'BY_APPROVER' }));
    again.fixture.detectChanges();

    expect(again.el('round-cancelled')).not.toBeNull();
    expect(http && fixture && el).toBeTruthy();
  });

  it('shows no cancel button for a week nobody has joined yet', () => {
    const { el } = open({ role: 'MANAGER', current: round({ id: null, participantCount: 0 }) });

    expect(el('cancel-round')).toBeNull();
  });
});
