import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

export interface Organization {
  id: string;
  name: string;
  ownerId: string;
  createdAt: string;
  updatedAt: string;
}

export interface Membership {
  id: string;
  userId: string;
  organizationId: string;
  role: 'ADMIN' | 'MANAGER' | 'MEMBER';
  createdAt: string;
}

export interface Invitation {
  id: string;
  email: string;
  organizationId: string;
  role: 'ADMIN' | 'MANAGER' | 'MEMBER';
  token: string;
  expiresAt: string;
  createdAt: string;
}

export interface CreateOrganizationRequest {
  name: string;
}

export interface InviteRequest {
  email: string;
  role: 'ADMIN' | 'MANAGER' | 'MEMBER';
}

export interface UpdateRoleRequest {
  role: 'ADMIN' | 'MANAGER' | 'MEMBER';
}

@Injectable({
  providedIn: 'root'
})
export class OrganizationService {
  private apiUrl = 'http://localhost:8080/organizations';

  constructor(private http: HttpClient) {}

  create(request: CreateOrganizationRequest): Observable<Organization> {
    return this.http.post<Organization>(this.apiUrl, request);
  }

  list(): Observable<Organization[]> {
    return this.http.get<Organization[]>(this.apiUrl);
  }

  getById(id: string): Observable<Organization> {
    return this.http.get<Organization>(`${this.apiUrl}/${id}`);
  }

  getMembers(orgId: string): Observable<Membership[]> {
    return this.http.get<Membership[]>(`${this.apiUrl}/${orgId}/members`);
  }

  invite(orgId: string, request: InviteRequest): Observable<Invitation> {
    return this.http.post<Invitation>(`${this.apiUrl}/${orgId}/invite`, request);
  }

  updateMemberRole(orgId: string, memberId: string, request: UpdateRoleRequest): Observable<Membership> {
    return this.http.patch<Membership>(`${this.apiUrl}/${orgId}/members/${memberId}/role`, request);
  }

  removeMember(orgId: string, memberId: string): Observable<void> {
    return this.http.delete<void>(`${this.apiUrl}/${orgId}/members/${memberId}`);
  }
}
