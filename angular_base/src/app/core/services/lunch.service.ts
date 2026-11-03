import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';

export type LunchStatus = 'OPEN' | 'FORMED' | 'CANCELLED';

export interface LunchRound {
  id: string | null;
  weekStart: string;
  deadlineAt: string;
  status: LunchStatus;
  cancelReason: string | null;
  participantCount: number;
  enrolled: boolean;
}

/** A round with the groups the caller may see: their own, or all of them for an approver; user ids, never names. */
export interface LunchRoundDetail {
  round: LunchRound;
  myGroup: string[];
  groups: string[][];
}

export interface LunchConfig {
  day: string;
  cutoff: string;
  groupSize: number;
  timezone: string;
}

export interface LunchRoundPage {
  items: LunchRound[];
  page: number;
  size: number;
  totalItems: number;
}

export const WEEKDAYS = ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY'];

/** The weekly lunch roulette of the tenant (REQ-LUNCH-01..05); the deadline, the grouping and who may do what are the server's. */
@Injectable({
  providedIn: 'root'
})
export class LunchService {
  private apiUrl = 'http://localhost:8080/organizations';

  constructor(private http: HttpClient) {}

  private base(tenantId: string) {
    return `${this.apiUrl}/${tenantId}/lunch`;
  }

  config(tenantId: string): Observable<LunchConfig> {
    return this.http.get<LunchConfig>(`${this.base(tenantId)}/config`);
  }

  setConfig(tenantId: string, config: { day: string; cutoff: string; groupSize: number }): Observable<LunchConfig> {
    return this.http.put<LunchConfig>(`${this.base(tenantId)}/config`, config);
  }

  current(tenantId: string): Observable<LunchRound> {
    return this.http.get<LunchRound>(`${this.base(tenantId)}/rounds/current`);
  }

  enroll(tenantId: string): Observable<LunchRound> {
    return this.http.put<LunchRound>(`${this.base(tenantId)}/rounds/current/enrollment`, {});
  }

  withdraw(tenantId: string): Observable<LunchRound> {
    return this.http.delete<LunchRound>(`${this.base(tenantId)}/rounds/current/enrollment`);
  }

  cancelCurrent(tenantId: string): Observable<LunchRound> {
    return this.http.post<LunchRound>(`${this.base(tenantId)}/rounds/current/cancel`, {});
  }

  rounds(tenantId: string, page = 0, size = 10): Observable<LunchRoundPage> {
    return this.http.get<LunchRoundPage>(`${this.base(tenantId)}/rounds`, { params: new HttpParams().set('page', page).set('size', size) });
  }

  round(tenantId: string, roundId: string): Observable<LunchRoundDetail> {
    return this.http.get<LunchRoundDetail>(`${this.base(tenantId)}/rounds/${roundId}`);
  }
}
