import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { BehaviorSubject, EMPTY, Observable, Subscription, timer } from 'rxjs';
import { catchError, map, switchMap, tap } from 'rxjs/operators';

export interface NotificationView {
  id: string;
  eventType: string;
  level: string;
  title: string;
  message: string;
  read: boolean;
  createdAt: string;
}

export interface NotificationPage {
  items: NotificationView[];
  page: number;
  size: number;
  totalItems: number;
  unreadCount: number;
}

/** The unread count is refreshed this often: there is no websocket or SSE in this version (REQ-NOTIF-04). */
export const UNREAD_POLL_MS = 60_000;

/**
 * The signed-in user's in-app notifications for the active tenant (REQ-NOTIF-04): the first page is loaded when the app
 * opens for the tenant, and the number of unread ones is asked again every 60 seconds for as long as the tenant is
 * active. Everything is the server's: a notification is read, listed and marked by the tenant token alone.
 */
@Injectable({
  providedIn: 'root'
})
export class NotificationService {
  private apiUrl = 'http://localhost:8080/notifications/in-app';

  private unreadSubject = new BehaviorSubject<number>(0);
  public unread$ = this.unreadSubject.asObservable();

  private itemsSubject = new BehaviorSubject<NotificationView[]>([]);
  public items$ = this.itemsSubject.asObservable();

  private polling?: Subscription;

  constructor(private http: HttpClient) {}

  /** Opens the notifications of a tenant: loads the first page now and starts the 60-second count. */
  start(): void {
    this.stop();
    this.reload().subscribe({ error: () => undefined });
    this.polling = timer(UNREAD_POLL_MS, UNREAD_POLL_MS)
      .pipe(switchMap(() => this.refreshUnread().pipe(catchError(() => EMPTY)))) // one failed poll must not end the polling
      .subscribe({ error: () => undefined });
  }

  /** Nobody is signed in, or the tenant changed: no more asking, and nothing of the last tenant stays. */
  stop(): void {
    this.polling?.unsubscribe();
    this.polling = undefined;
    this.itemsSubject.next([]);
    this.unreadSubject.next(0);
  }

  /** The first page of notifications, newest first; also brings the unread count the page carries. */
  reload(page = 0, size = 20): Observable<NotificationPage> {
    const params = new HttpParams().set('page', page).set('size', size);
    return this.http.get<NotificationPage>(this.apiUrl, { params }).pipe(
      tap(result => {
        this.itemsSubject.next(result.items);
        this.unreadSubject.next(result.unreadCount);
      })
    );
  }

  refreshUnread(): Observable<number> {
    return this.http.get<{ unreadCount: number }>(`${this.apiUrl}/unread-count`).pipe(
      map(result => result.unreadCount),
      tap(count => this.unreadSubject.next(count))
    );
  }

  markRead(id: string): Observable<NotificationView> {
    return this.http.patch<NotificationView>(`${this.apiUrl}/${id}/read`, {}).pipe(
      tap(updated => {
        const items = this.itemsSubject.value;
        const before = items.find(item => item.id === id);
        this.itemsSubject.next(items.map(item => (item.id === id ? { ...item, read: true } : item)));
        if (before && !before.read && updated.read) {
          this.unreadSubject.next(Math.max(0, this.unreadSubject.value - 1));
        }
      })
    );
  }

  markAllRead(): Observable<number> {
    return this.http.patch<{ updated: number }>(`${this.apiUrl}/read`, {}).pipe(
      tap(() => {
        this.itemsSubject.next(this.itemsSubject.value.map(item => ({ ...item, read: true })));
        this.unreadSubject.next(0);
      }),
      map(result => result.updated)
    );
  }
}
