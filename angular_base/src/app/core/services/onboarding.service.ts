import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

export interface CreatedOrganization {
  id: string;
  name: string;
}

export interface AcceptedInvitation {
  tenantId: string;
  role: string;
}

/**
 * What a user with no tenant can do, with the identity token the interceptor attaches (REQ-AUTH-03): create an
 * organization (becoming its ADMIN) or accept an invitation. Neither returns a token for the tenant, so the user logs
 * in again afterwards and gets one the normal way.
 */
@Injectable({
  providedIn: 'root'
})
export class OnboardingService {
  private apiUrl = 'http://localhost:8080';

  constructor(private http: HttpClient) {}

  createOrganization(name: string): Observable<CreatedOrganization> {
    return this.http.post<CreatedOrganization>(`${this.apiUrl}/organizations`, { name });
  }

  acceptInvitation(token: string): Observable<AcceptedInvitation> {
    return this.http.post<AcceptedInvitation>(`${this.apiUrl}/invitations/accept`, { token });
  }
}
