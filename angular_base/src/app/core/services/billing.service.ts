import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

export interface Subscription {
  id: string;
  planId: string;
  status: 'TRIALING' | 'ACTIVE' | 'PAST_DUE' | 'SUSPENDED' | 'CANCELED' | string;
  startDate: string;
  endDate: string | null;
  pastDueSince: string | null;
}

export interface Invoice {
  id: string;
  subscriptionId: string;
  amountEur: number;
  status: string;
  issuedAt: string;
  paidAt: string | null;
}

/** The tenant's own billing, from its token: ADMIN and MANAGER read it, only an ADMIN acts on it (REQ-MGR-02). */
@Injectable({
  providedIn: 'root'
})
export class BillingService {
  private apiUrl = 'http://localhost:8080/billing';

  constructor(private http: HttpClient) {}

  getSubscription(): Observable<Subscription> {
    return this.http.get<Subscription>(`${this.apiUrl}/subscription`);
  }

  getInvoices(): Observable<Invoice[]> {
    return this.http.get<Invoice[]>(`${this.apiUrl}/invoices`);
  }

  cancelSubscription(): Observable<Subscription> {
    return this.http.post<Subscription>(`${this.apiUrl}/subscriptions/cancel`, {});
  }
}
