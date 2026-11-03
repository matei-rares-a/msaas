import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { map } from 'rxjs/operators';

export interface ActivityLogEntry {
  id: string;
  eventType: string;
  actorUserId: string | null;
  createdAt: string;
  metadata: string;
}

export interface ActivityLogPage {
  items: ActivityLogEntry[];
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
}

export interface ActivityLogFilter {
  from?: string;
  to?: string;
  eventTypes?: string[];
  actorUserId?: string;
}

/** The query asks only for the fields the page shows; the tenant is never an argument, it comes from the token (REQ-MON-08). */
export const ACTIVITY_LOG_QUERY = `query ActivityLog($filter: ActivityLogFilter, $page: Int, $size: Int) {
  activityLog(filter: $filter, page: $page, size: $size) {
    items { id eventType actorUserId createdAt metadata }
    page size totalItems totalPages
  }
}`;

/** Thrown for a GraphQL answer with errors; {@code forbidden} is a caller that may not read the journal. */
export class JournalError extends Error {
  constructor(message: string, readonly forbidden: boolean) {
    super(message);
  }
}

/** The tenant journal, read through the read-only GraphQL API of notification-service (REQ-MGR-04, REQ-MON-08). */
@Injectable({
  providedIn: 'root'
})
export class JournalService {
  private url = 'http://localhost:8080/notifications/graphql';

  constructor(private http: HttpClient) {}

  read(filter: ActivityLogFilter, page = 0, size = 20): Observable<ActivityLogPage> {
    const cleaned: ActivityLogFilter = {};
    if (filter.from) {
      cleaned.from = filter.from;
    }
    if (filter.to) {
      cleaned.to = filter.to;
    }
    if (filter.eventTypes?.length) {
      cleaned.eventTypes = filter.eventTypes;
    }
    if (filter.actorUserId) {
      cleaned.actorUserId = filter.actorUserId;
    }
    return this.http
      .post<{ data?: { activityLog: ActivityLogPage }; errors?: { message: string; extensions?: { classification?: string } }[] }>(
        this.url,
        { query: ACTIVITY_LOG_QUERY, variables: { filter: cleaned, page, size } }
      )
      .pipe(
        map(response => {
          if (response.errors?.length || !response.data) {
            const first = response.errors?.[0];
            throw new JournalError(first?.message ?? 'The journal could not be read.', first?.extensions?.classification === 'FORBIDDEN');
          }
          return response.data.activityLog;
        })
      );
  }
}
