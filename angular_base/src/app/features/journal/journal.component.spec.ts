import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { JournalComponent } from './journal.component';

const URL = 'http://localhost:8080/notifications/graphql';
const entry = (id: string) => ({ id, eventType: 'MEMBER_ROLE_CHANGED', actorUserId: 'aaaaaaaa-bbbb', createdAt: '2026-11-03T10:00:00Z', metadata: '{"newRole":"MANAGER"}' });
const answer = (items: unknown[], page = 0, totalPages = 1) => ({ data: { activityLog: { items, page, size: 20, totalItems: items.length, totalPages } } });

describe('REQ-MGR-04 the journal page for an ADMIN or a MANAGER', () => {
  let fixture: ComponentFixture<JournalComponent>;
  let http: HttpTestingController;
  const el = (id: string) => fixture.nativeElement.querySelector(`[data-testid="${id}"]`) as HTMLElement | null;

  function open(first: object) {
    TestBed.configureTestingModule({ imports: [JournalComponent], providers: [provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(JournalComponent);
    fixture.detectChanges();
    http.expectOne(URL).flush(first);
    fixture.detectChanges();
  }

  afterEach(() => http.verify());

  it('shows the entries of the first page, newest first as the server gives them', () => {
    open(answer([entry('1'), entry('2')]));

    expect(fixture.nativeElement.querySelectorAll('[data-testid=journal-entry]').length).toBe(2);
    expect(el('journal-entry')!.textContent).toContain('MEMBER_ROLE_CHANGED');
    expect(el('journal-entry')!.textContent).toContain('aaaaaaaa');
  });

  it('says so when nothing matches', () => {
    open(answer([]));

    expect(el('journal-empty')).not.toBeNull();
  });

  it('searches with the filters it was given, from the first page again', () => {
    open(answer([entry('1')]));

    fixture.componentInstance.form.setValue({ from: '', to: '', eventTypes: ' LOGIN , MEMBER_ROLE_CHANGED ,', actorUserId: ' u1 ' });
    fixture.componentInstance.search();
    const request = http.expectOne(URL);
    expect(request.request.body.variables).toEqual({ filter: { eventTypes: ['LOGIN', 'MEMBER_ROLE_CHANGED'], actorUserId: 'u1' }, page: 0, size: 20 });
    request.flush(answer([]));
  });

  it('turns the dates of the form into instants', () => {
    open(answer([entry('1')]));

    fixture.componentInstance.form.patchValue({ from: '2026-11-01T10:00' });
    fixture.componentInstance.search();
    const request = http.expectOne(URL);
    expect(request.request.body.variables.filter.from).toBe(new Date('2026-11-01T10:00').toISOString());
    request.flush(answer([]));
  });

  it('pages forward and back, and not past either end', () => {
    open(answer([entry('1')], 0, 2));
    expect((el('journal-previous') as HTMLButtonElement).disabled).toBeTrue();

    el('journal-next')!.click();
    const next = http.expectOne(URL);
    expect(next.request.body.variables.page).toBe(1);
    next.flush(answer([entry('2')], 1, 2));
    fixture.detectChanges();
    expect(el('journal-page')!.textContent).toContain('Page 2 of 2');
    expect((el('journal-next') as HTMLButtonElement).disabled).toBeTrue();

    el('journal-previous')!.click();
    const previous = http.expectOne(URL);
    expect(previous.request.body.variables.page).toBe(0);
    previous.flush(answer([entry('1')], 0, 2));
  });

  it('tells a caller who may not read the journal why, and shows other failures plainly', () => {
    open({ errors: [{ message: 'forbidden', extensions: { classification: 'FORBIDDEN' } }], data: null });
    expect(el('journal-error')!.textContent).toContain('administrators and managers');

    fixture.componentInstance.search();
    http.expectOne(URL).flush({}, { status: 500, statusText: 'Server Error' });
    fixture.detectChanges();
    expect(el('journal-error')!.textContent).toContain('could not be read');
  });
});
