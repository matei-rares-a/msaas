import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { Role } from './access.service';

export interface Invitation {
  id: string;
  email: string;
  role: string;
  status: string;
  expiresAt: string;
}

/** Invitations of the tenant (REQ-MGR-01, REQ-ROLE-05): the tenant comes from the token, the role is the server's to check. */
@Injectable({
  providedIn: 'root'
})
export class InvitationService {
  private apiUrl = 'http://localhost:8080/organizations';

  constructor(private http: HttpClient) {}

  list(tenantId: string): Observable<Invitation[]> {
    return this.http.get<Invitation[]>(`${this.apiUrl}/${tenantId}/invitations`);
  }

  invite(tenantId: string, email: string, role: Role): Observable<Invitation> {
    return this.http.post<Invitation>(`${this.apiUrl}/${tenantId}/invitations`, { email, role });
  }

  revoke(tenantId: string, invitationId: string): Observable<void> {
    return this.http.delete<void>(`${this.apiUrl}/${tenantId}/invitations/${invitationId}`);
  }
}
