import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ACTIVITY_LOG_QUERY, JournalError, JournalService } from './journal.service';

const URL = 'http://localhost:8080/notifications/graphql';
const page = { items: [{ id: '1', eventType: 'LOGIN', actorUserId: 'u', createdAt: '2026-11-03T10:00:00Z', metadata: '{}' }], page: 0, size: 20, totalItems: 1, totalPages: 1 };

describe('REQ-MGR-04 the journal is read through the read-only GraphQL API', () => {
  let service: JournalService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    service = TestBed.inject(JournalService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('posts a query, never a mutation, with the filters and the page as variables and no tenant', () => {
    service.read({ from: '2026-11-01T00:00:00Z', eventTypes: ['LOGIN'], actorUserId: 'u' }, 2, 10).subscribe();
    const request = http.expectOne(URL);

    expect(request.request.method).toBe('POST');
    expect(request.request.body.query).toBe(ACTIVITY_LOG_QUERY);
    expect(request.request.body.query).not.toContain('mutation');
    expect(request.request.body.variables).toEqual({ filter: { from: '2026-11-01T00:00:00Z', eventTypes: ['LOGIN'], actorUserId: 'u' }, page: 2, size: 10 });
    expect(JSON.stringify(request.request.body)).not.toContain('tenant');
    request.flush({ data: { activityLog: page } });
  });

  it('sends only the filters that are set', () => {
    service.read({ from: '', to: undefined, eventTypes: [], actorUserId: '' }).subscribe();
    const request = http.expectOne(URL);

    expect(request.request.body.variables.filter).toEqual({});
    request.flush({ data: { activityLog: page } });
  });

  it('gives back the page', () => {
    let result: any;
    service.read({}).subscribe(r => (result = r));
    http.expectOne(URL).flush({ data: { activityLog: page } });

    expect(result.items[0].eventType).toBe('LOGIN');
    expect(result.totalPages).toBe(1);
  });

  it('turns a GraphQL error into an error, and tells a refusal from other failures', () => {
    const errors: any[] = [];
    service.read({}).subscribe({ error: e => errors.push(e) });
    http.expectOne(URL).flush({ errors: [{ message: 'forbidden', extensions: { classification: 'FORBIDDEN' } }], data: null });
    service.read({}).subscribe({ error: e => errors.push(e) });
    http.expectOne(URL).flush({ errors: [{ message: 'query too complex' }], data: null });

    expect(errors[0] instanceof JournalError && errors[0].forbidden).toBeTrue();
    expect(errors[1] instanceof JournalError && !errors[1].forbidden).toBeTrue();
    expect(errors[1].message).toBe('query too complex');
  });
});
