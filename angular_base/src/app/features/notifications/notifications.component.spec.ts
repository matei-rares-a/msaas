import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { NotificationsComponent } from './notifications.component';
import { NotificationService } from '../../core/services/notification.service';

const API = 'http://localhost:8080/notifications/in-app';
const item = (id: string, read: boolean, level = 'INFO') =>
  ({ id, eventType: 'X', level, title: `Title ${id}`, message: `Message ${id}`, read, createdAt: '2026-11-03T10:00:00Z' });

describe('REQ-NOTIF-04 the notifications page', () => {
  let fixture: ComponentFixture<NotificationsComponent>;
  let http: HttpTestingController;

  function open(items: ReturnType<typeof item>[], unreadCount: number) {
    TestBed.configureTestingModule({ imports: [NotificationsComponent], providers: [provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(NotificationsComponent);
    fixture.detectChanges();
    http.expectOne(r => r.url === API && r.method === 'GET').flush({ items, page: 0, size: 20, totalItems: items.length, unreadCount });
    fixture.detectChanges();
  }

  const rows = () => Array.from(fixture.nativeElement.querySelectorAll('[data-testid=notification]')) as HTMLElement[];

  afterEach(() => http.verify());

  it('loads the notifications when it opens and marks the unread ones', () => {
    open([item('a', false), item('b', true)], 1);

    expect(rows().length).toBe(2);
    expect(rows()[0].textContent).toContain('Title a');
    expect(rows()[0].classList).toContain('unread');
    expect(rows()[1].classList).not.toContain('unread');
  });

  it('says so when there are none', () => {
    open([], 0);

    expect(fixture.nativeElement.querySelector('[data-testid=notifications-empty]')).not.toBeNull();
  });

  it('marks a notification as read when it is opened, once', () => {
    open([item('a', false), item('b', true)], 1);

    rows()[0].click();
    http.expectOne(`${API}/a/read`).flush(item('a', true));
    fixture.detectChanges();
    rows()[0].click();
    rows()[1].click();

    expect(rows()[0].classList).not.toContain('unread');
    http.expectNone(r => r.url.endsWith('/read'));
  });

  it('marks them all as read, and the button is off when there is nothing unread', () => {
    open([item('a', false), item('b', false)], 2);
    const button = fixture.nativeElement.querySelector('[data-testid=mark-all-read]') as HTMLButtonElement;
    expect(button.textContent).toContain('(2)');
    expect(button.disabled).toBeFalse();

    button.click();
    http.expectOne(`${API}/read`).flush({ updated: 2 });
    fixture.detectChanges();

    expect(button.disabled).toBeTrue();
    expect(rows().every(row => !row.classList.contains('unread'))).toBeTrue();
  });

  it('shows an error when they cannot be loaded', () => {
    TestBed.configureTestingModule({ imports: [NotificationsComponent], providers: [provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(NotificationsComponent);
    fixture.detectChanges();
    http.expectOne(r => r.url === API).flush({}, { status: 500, statusText: 'Server Error' });
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('[data-testid=notifications-error]').textContent).toContain('could not be loaded');
    expect(TestBed.inject(NotificationService)).toBeTruthy();
  });
});
