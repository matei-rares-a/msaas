import { TestBed, fakeAsync, discardPeriodicTasks, tick } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { NotificationPage, NotificationService, NotificationView, UNREAD_POLL_MS } from './notification.service';

const API = 'http://localhost:8080/notifications/in-app';

function view(id: string, read = false): NotificationView {
  return { id, eventType: 'MEMBER_ROLE_CHANGED', level: 'INFO', title: `Title ${id}`, message: `Message ${id}`, read, createdAt: '2026-11-03T10:00:00Z' };
}

function page(items: NotificationView[], unreadCount: number): NotificationPage {
  return { items, page: 0, size: 20, totalItems: items.length, unreadCount };
}

const list = (http: HttpTestingController) => http.expectOne(r => r.url === API && r.method === 'GET');

describe('REQ-NOTIF-04 notifications load when the app opens and the unread count is polled every 60 seconds', () => {
  let service: NotificationService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    service = TestBed.inject(NotificationService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    service.stop();
    http.verify();
  });

  it('polls every 60 seconds, as the requirement says', () => {
    expect(UNREAD_POLL_MS).toBe(60_000);
  });

  it('loads the first page at once, with the unread count it carries', () => {
    let items: NotificationView[] = [];
    let unread = -1;
    service.items$.subscribe(value => (items = value));
    service.unread$.subscribe(value => (unread = value));

    service.start();
    const request = list(http);
    expect(request.request.params.get('page')).toBe('0');
    expect(request.request.params.get('size')).toBe('20');
    request.flush(page([view('a'), view('b', true)], 1));

    expect(items.map(item => item.id)).toEqual(['a', 'b']);
    expect(unread).toBe(1);
    service.stop();
  });

  it('asks for the unread count again every 60 seconds and not before', fakeAsync(() => {
    let unread = -1;
    service.unread$.subscribe(value => (unread = value));
    service.start();
    list(http).flush(page([], 0));

    tick(UNREAD_POLL_MS - 1);
    http.expectNone(`${API}/unread-count`);
    tick(1);
    http.expectOne(`${API}/unread-count`).flush({ unreadCount: 3 });
    expect(unread).toBe(3);

    tick(UNREAD_POLL_MS);
    http.expectOne(`${API}/unread-count`).flush({ unreadCount: 5 });
    expect(unread).toBe(5);
    discardPeriodicTasks();
    service.stop();
  }));

  it('keeps polling after a failed poll', fakeAsync(() => {
    let unread = -1;
    service.unread$.subscribe(value => (unread = value));
    service.start();
    list(http).flush(page([], 0));

    tick(UNREAD_POLL_MS);
    http.expectOne(`${API}/unread-count`).flush({}, { status: 500, statusText: 'Server Error' });
    tick(UNREAD_POLL_MS);
    http.expectOne(`${API}/unread-count`).flush({ unreadCount: 2 });

    expect(unread).toBe(2);
    discardPeriodicTasks();
    service.stop();
  }));

  it('stops asking, and forgets the notifications, when the tenant changes or nobody is signed in', fakeAsync(() => {
    let items: NotificationView[] = [view('x')];
    let unread = 9;
    service.items$.subscribe(value => (items = value));
    service.unread$.subscribe(value => (unread = value));
    service.start();
    list(http).flush(page([view('a')], 4));

    service.stop();
    tick(UNREAD_POLL_MS * 3);

    http.expectNone(`${API}/unread-count`);
    expect(items).toEqual([]);
    expect(unread).toBe(0);
  }));

  it('starting again replaces the last tenant\'s polling instead of adding to it', fakeAsync(() => {
    service.start();
    list(http).flush(page([], 0));
    service.start();
    list(http).flush(page([], 0));

    tick(UNREAD_POLL_MS);

    expect(http.match(`${API}/unread-count`).length).toBe(1);
    discardPeriodicTasks();
    service.stop();
  }));

  it('a failed first load leaves an empty list and does not stop the polling', fakeAsync(() => {
    service.start();
    list(http).flush({}, { status: 500, statusText: 'Server Error' });

    tick(UNREAD_POLL_MS);
    http.expectOne(`${API}/unread-count`).flush({ unreadCount: 1 });
    discardPeriodicTasks();
    service.stop();
  }));
});

describe('REQ-NOTIF-04 reading notifications', () => {
  let service: NotificationService;
  let http: HttpTestingController;
  let items: NotificationView[];
  let unread: number;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    service = TestBed.inject(NotificationService);
    http = TestBed.inject(HttpTestingController);
    service.items$.subscribe(value => (items = value));
    service.unread$.subscribe(value => (unread = value));
    service.reload().subscribe();
    list(http).flush(page([view('a'), view('b'), view('c', true)], 2));
  });

  afterEach(() => http.verify());

  it('marks one as read and counts one less', () => {
    service.markRead('a').subscribe();
    const request = http.expectOne(`${API}/a/read`);
    expect(request.request.method).toBe('PATCH');
    request.flush(view('a', true));

    expect(items.find(item => item.id === 'a')?.read).toBeTrue();
    expect(items.find(item => item.id === 'b')?.read).toBeFalse();
    expect(unread).toBe(1);
  });

  it('does not count down for one that was already read', () => {
    service.markRead('c').subscribe();
    http.expectOne(`${API}/c/read`).flush(view('c', true));

    expect(unread).toBe(2);
  });

  it('marks them all as read', () => {
    let updated = -1;
    service.markAllRead().subscribe(count => (updated = count));
    const request = http.expectOne(`${API}/read`);
    expect(request.request.method).toBe('PATCH');
    request.flush({ updated: 2 });

    expect(updated).toBe(2);
    expect(items.every(item => item.read)).toBeTrue();
    expect(unread).toBe(0);
  });

  it('keeps everything as it was when marking fails', () => {
    service.markRead('a').subscribe({ error: () => undefined });
    http.expectOne(`${API}/a/read`).flush({}, { status: 404, statusText: 'Not Found' });

    expect(items.find(item => item.id === 'a')?.read).toBeFalse();
    expect(unread).toBe(2);
  });
});
