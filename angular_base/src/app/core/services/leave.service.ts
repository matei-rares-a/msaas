import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';

export type LeaveType = 'ODIHNA' | 'FARA_PLATA';
export type LeaveStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'CANCELLED';

export interface LeaveRequest {
  id: string;
  userId: string;
  type: LeaveType;
  startDate: string;
  endDate: string;
  days: number;
  reason: string | null;
  status: LeaveStatus;
  autoApproved: boolean;
  decisionComment: string | null;
  decidedBy: string | null;
  createdAt: string;
}

export interface LeavePage {
  items: LeaveRequest[];
  page: number;
  size: number;
  totalItems: number;
}

export interface Balance {
  userId: string;
  year: number;
  allocated: number;
  approved: number;
  pending: number;
  available: number;
}

/** What the team calendar shows: who is away and when, never the type or the reason (REQ-LEAVE-06). */
export interface CalendarEntry {
  userId: string;
  startDate: string;
  endDate: string;
}

export interface Holiday {
  date: string;
  name: string;
}

export interface LeaveListQuery {
  scope?: 'MINE' | 'ALL';
  status?: LeaveStatus;
  page?: number;
  size?: number;
}

/** Leave requests of the tenant, from the token (REQ-LEAVE-01..08); every rule is the server's, this only asks. */
@Injectable({
  providedIn: 'root'
})
export class LeaveService {
  private apiUrl = 'http://localhost:8080/organizations';

  constructor(private http: HttpClient) {}

  private base(tenantId: string) {
    return `${this.apiUrl}/${tenantId}/leave`;
  }

  list(tenantId: string, query: LeaveListQuery = {}): Observable<LeavePage> {
    let params = new HttpParams().set('scope', query.scope ?? 'MINE').set('page', query.page ?? 0).set('size', query.size ?? 20);
    if (query.status) {
      params = params.set('status', query.status);
    }
    return this.http.get<LeavePage>(`${this.base(tenantId)}/requests`, { params });
  }

  create(tenantId: string, request: { type: LeaveType; startDate: string; endDate: string; reason?: string }): Observable<LeaveRequest> {
    return this.http.post<LeaveRequest>(`${this.base(tenantId)}/requests`, request);
  }

  approve(tenantId: string, id: string, comment?: string): Observable<LeaveRequest> {
    return this.http.post<LeaveRequest>(`${this.base(tenantId)}/requests/${id}/approve`, { comment: comment || null });
  }

  reject(tenantId: string, id: string, comment?: string): Observable<LeaveRequest> {
    return this.http.post<LeaveRequest>(`${this.base(tenantId)}/requests/${id}/reject`, { comment: comment || null });
  }

  cancel(tenantId: string, id: string): Observable<LeaveRequest> {
    return this.http.post<LeaveRequest>(`${this.base(tenantId)}/requests/${id}/cancel`, {});
  }

  balance(tenantId: string, year?: number, userId?: string): Observable<Balance> {
    let params = new HttpParams();
    if (year) {
      params = params.set('year', year);
    }
    if (userId) {
      params = params.set('userId', userId);
    }
    return this.http.get<Balance>(`${this.base(tenantId)}/balance`, { params });
  }

  calendar(tenantId: string, from: string, to: string): Observable<CalendarEntry[]> {
    return this.http.get<CalendarEntry[]>(`${this.base(tenantId)}/calendar`, { params: new HttpParams().set('from', from).set('to', to) });
  }

  getPolicy(tenantId: string): Observable<{ defaultAllowance: number }> {
    return this.http.get<{ defaultAllowance: number }>(`${this.base(tenantId)}/policy`);
  }

  setPolicy(tenantId: string, allowance: number): Observable<{ defaultAllowance: number }> {
    return this.http.put<{ defaultAllowance: number }>(`${this.base(tenantId)}/policy`, { allowance });
  }

  setAllowance(tenantId: string, userId: string, allowance: number): Observable<void> {
    return this.http.put<void>(`${this.base(tenantId)}/balances/${userId}`, { allowance });
  }

  clearAllowance(tenantId: string, userId: string): Observable<void> {
    return this.http.delete<void>(`${this.base(tenantId)}/balances/${userId}`);
  }

  holidays(tenantId: string, year: number): Observable<Holiday[]> {
    return this.http.get<Holiday[]>(`${this.base(tenantId)}/holidays`, { params: new HttpParams().set('year', year) });
  }

  addHoliday(tenantId: string, holiday: Holiday): Observable<Holiday> {
    return this.http.post<Holiday>(`${this.base(tenantId)}/holidays`, holiday);
  }

  removeHoliday(tenantId: string, date: string): Observable<void> {
    return this.http.delete<void>(`${this.base(tenantId)}/holidays/${date}`);
  }
}
