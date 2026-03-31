import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

export interface InAppNotification {
  id: string;
  organizationId: string;
  userId: string;
  title: string;
  message: string;
  level: string;
  read: boolean;
  createdAt: string;
}

@Injectable({
  providedIn: 'root'
})
export class AdminService {
  private readonly apiUrl = 'http://localhost:8080/notifications';

  constructor(private http: HttpClient) {}

  getFeatureFlags(): Observable<Record<string, boolean>> {
    return this.http.get<Record<string, boolean>>(`${this.apiUrl}/feature-flags`);
  }

  setFeatureFlag(flagKey: string, enabled: boolean): Observable<Record<string, boolean>> {
    return this.http.patch<Record<string, boolean>>(
      `${this.apiUrl}/feature-flags/${flagKey}?enabled=${enabled}`,
      {}
    );
  }

  getInAppNotifications(): Observable<InAppNotification[]> {
    return this.http.get<InAppNotification[]>(`${this.apiUrl}/in-app`);
  }

  markAsRead(id: string): Observable<void> {
    return this.http.patch<void>(`${this.apiUrl}/in-app/${id}/read`, {});
  }
}
