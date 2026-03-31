import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

export interface Plan {
  id: string;
  name: string;
  price: number;
  maxUsers: number;
  features: string[];
  createdAt: string;
}

export interface Subscription {
  id: string;
  organizationId: string;
  planId: string;
  status: 'TRIALING' | 'ACTIVE' | 'PAST_DUE' | 'CANCELED';
  startDate: string;
  endDate: string | null;
  createdAt: string;
}

export interface Invoice {
  id: string;
  organizationId: string;
  subscriptionId: string;
  amount: number;
  status: 'PAID' | 'UNPAID';
  issuedAt: string;
  paidAt: string | null;
}

export interface SubscribeRequest {
  planId: string;
}

@Injectable({
  providedIn: 'root'
})
export class BillingService {
  private apiUrl = 'http://localhost:8080/billing';

  constructor(private http: HttpClient) {}

  getPlans(): Observable<Plan[]> {
    return this.http.get<Plan[]>(`${this.apiUrl}/plans`);
  }

  getPlanById(id: string): Observable<Plan> {
    return this.http.get<Plan>(`${this.apiUrl}/plans/${id}`);
  }

  subscribe(request: SubscribeRequest): Observable<Subscription> {
    return this.http.post<Subscription>(`${this.apiUrl}/subscribe`, request);
  }

  getSubscription(): Observable<Subscription> {
    return this.http.get<Subscription>(`${this.apiUrl}/subscription`);
  }

  cancelSubscription(): Observable<void> {
    return this.http.post<void>(`${this.apiUrl}/cancel`, {});
  }

  getInvoices(): Observable<Invoice[]> {
    return this.http.get<Invoice[]>(`${this.apiUrl}/invoices`);
  }

  getInvoiceById(id: string): Observable<Invoice> {
    return this.http.get<Invoice>(`${this.apiUrl}/invoices/${id}`);
  }

  exportInvoicePdf(id: string): Observable<Blob> {
    return this.http.get(`${this.apiUrl}/invoices/${id}/pdf`, {
      responseType: 'blob'
    });
  }

  uploadAttachment(file: File): Observable<{ fileName: string; path: string }> {
    const formData = new FormData();
    formData.append('file', file);
    return this.http.post<{ fileName: string; path: string }>(
      'http://localhost:8080/notifications/uploads',
      formData
    );
  }
}
