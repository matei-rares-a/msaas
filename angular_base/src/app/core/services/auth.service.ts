import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { BehaviorSubject, Observable, throwError } from 'rxjs';
import { map, tap } from 'rxjs/operators';

export interface AuthResponse {
  accessToken: string;
  refreshToken: string;
  tokenType?: string;
  expiresIn: number;
}

/** What the per-tenant token says about the signed-in user (REQ-AUTH-04): one tenant, never a list. */
export interface User {
  id: string;
  tenantId: string;
  tenantName: string;
  displayName: string;
  role: string;
  readOnly: boolean;
}

export interface RegisterRequest {
  email: string;
  password: string;
}

export interface LoginRequest {
  email: string;
  password: string;
}

export interface TenantOption {
  tenantId: string;
  role: string;
}

/** A user in more than one tenant chooses; the id is opaque, short-lived and works once (REQ-AUTH-02). */
export interface TenantChoice {
  tenants: TenantOption[];
  loginSessionId: string;
}

export type LoginResult = { kind: 'signedIn' } | { kind: 'chooseTenant' } | { kind: 'noTenant' };

interface StoredSession {
  accessToken: string;
  refreshToken: string;
  expiresAt: number;
}

const SESSIONS_KEY = 'msaas.sessions';
const ACTIVE_TENANT_KEY = 'msaas.activeTenant';

/**
 * Login and the tokens it yields. There is one token per tenant (REQ-AUTH-04): each tenant the user chose keeps its own
 * access and refresh token here, and exactly one tenant is active, whose token every request carries. A user with no
 * tenant holds only an identity token (REQ-AUTH-03), which lives in memory, never in storage, and is sent solely to
 * the two routes that accept it (see the interceptor).
 */
@Injectable({
  providedIn: 'root'
})
export class AuthService {
  private apiUrl = 'http://localhost:8080/auth';
  private identityToken: string | null = null;
  private pendingChoice: TenantChoice | null = null;

  private userSubject = new BehaviorSubject<User | null>(null);
  public user$ = this.userSubject.asObservable();

  private isAuthenticatedSubject = new BehaviorSubject<boolean>(false);
  public isAuthenticated$ = this.isAuthenticatedSubject.asObservable();

  constructor(private http: HttpClient) {
    this.publishState();
  }

  /** Who is signed in, as the active tenant's token says; null when nobody is. */
  get currentUser(): User | null {
    return this.userSubject.value;
  }

  register(request: RegisterRequest): Observable<void> {
    return this.http.post<void>(`${this.apiUrl}/register`, request);
  }

  /** 1 tenant: signed in. More than 1: the user must choose. None: only an identity token. */
  login(request: LoginRequest): Observable<LoginResult> {
    this.pendingChoice = null;
    this.identityToken = null;
    return this.http.post<any>(`${this.apiUrl}/login`, request).pipe(
      map((response): LoginResult => {
        if (response.accessToken) {
          this.storeSession(response);
          return { kind: 'signedIn' };
        }
        if (response.loginSessionId) {
          this.pendingChoice = { tenants: response.tenants ?? [], loginSessionId: response.loginSessionId };
          return { kind: 'chooseTenant' };
        }
        this.identityToken = response.identityToken;
        return { kind: 'noTenant' };
      })
    );
  }

  /** Exchanges the pending loginSessionId and the chosen tenant for that tenant's token; the id is spent either way. */
  selectTenant(tenantId: string): Observable<void> {
    const choice = this.pendingChoice;
    this.pendingChoice = null;
    if (!choice) {
      return throwError(() => new Error('There is no login waiting for a tenant to be chosen'));
    }
    return this.http
      .post<AuthResponse>(`${this.apiUrl}/login/tenant`, { loginSessionId: choice.loginSessionId, tenantId })
      .pipe(map(response => this.storeSession(response)));
  }

  /** Renews the active tenant's token with that tenant's refresh token. */
  refresh(): Observable<AuthResponse> {
    const refreshToken = this.getRefreshToken();
    if (!refreshToken) {
      return throwError(() => new Error('No refresh token available'));
    }
    return this.http.post<AuthResponse>(`${this.apiUrl}/refresh`, { refreshToken }).pipe(
      tap(response => this.storeSession(response))
    );
  }

  forgotPassword(email: string): Observable<any> {
    return this.http.post(`${this.apiUrl}/forgot-password`, { email });
  }

  resetPassword(token: string, password: string): Observable<any> {
    return this.http.post(`${this.apiUrl}/reset-password`, { token, password });
  }

  logout(): void {
    localStorage.removeItem(SESSIONS_KEY);
    localStorage.removeItem(ACTIVE_TENANT_KEY);
    this.identityToken = null;
    this.pendingChoice = null;
    this.publishState();
  }

  // ---- the identity of a user without a tenant ----

  getIdentityToken(): string | null {
    return this.identityToken;
  }

  /** The identity token has done its job (an organization was created, an invitation accepted): it is dropped. */
  endIdentitySession(): void {
    this.identityToken = null;
  }

  getPendingChoice(): TenantChoice | null {
    return this.pendingChoice;
  }

  // ---- one token per tenant ----

  getAccessToken(): string | null {
    return this.activeSession()?.accessToken ?? null;
  }

  getRefreshToken(): string | null {
    return this.activeSession()?.refreshToken ?? null;
  }

  getActiveTenantId(): string | null {
    return localStorage.getItem(ACTIVE_TENANT_KEY);
  }

  /** Tenants this client holds a token for, the active one included. */
  getSessionTenantIds(): string[] {
    return Object.keys(this.readSessions());
  }

  /** Makes another tenant the active one when its token is still good; false when the user has to log in again. */
  activateTenant(tenantId: string): boolean {
    const session = this.readSessions()[tenantId];
    if (!session || session.expiresAt <= Date.now()) {
      return false;
    }
    localStorage.setItem(ACTIVE_TENANT_KEY, tenantId);
    this.publishState();
    return true;
  }

  hasValidToken(): boolean {
    const session = this.activeSession();
    return !!session && Date.now() < session.expiresAt;
  }

  isTokenExpiringSoon(): boolean {
    const session = this.activeSession();
    if (!session) {
      return true;
    }
    const fiveMinutesInMs = 5 * 60 * 1000;
    return session.expiresAt - Date.now() < fiveMinutesInMs;
  }

  // ---- storage ----

  private storeSession(response: AuthResponse): void {
    const claims = this.decodeToken(response.accessToken);
    if (!claims?.tenantId) {
      throw new Error('The token is not a tenant token');
    }
    const sessions = this.readSessions();
    // sessions of another user are never kept next to this one
    for (const [tenantId, session] of Object.entries(sessions)) {
      if (this.decodeToken(session.accessToken)?.sub !== claims.sub) {
        delete sessions[tenantId];
      }
    }
    sessions[claims.tenantId] = {
      accessToken: response.accessToken,
      refreshToken: response.refreshToken,
      expiresAt: Date.now() + response.expiresIn * 1000
    };
    localStorage.setItem(SESSIONS_KEY, JSON.stringify(sessions));
    localStorage.setItem(ACTIVE_TENANT_KEY, claims.tenantId);
    this.identityToken = null;
    this.pendingChoice = null;
    this.publishState();
  }

  private readSessions(): Record<string, StoredSession> {
    try {
      return JSON.parse(localStorage.getItem(SESSIONS_KEY) ?? '{}') ?? {};
    } catch {
      return {};
    }
  }

  private activeSession(): StoredSession | null {
    const tenantId = localStorage.getItem(ACTIVE_TENANT_KEY);
    return tenantId ? this.readSessions()[tenantId] ?? null : null;
  }

  private publishState(): void {
    const session = this.activeSession();
    const claims = session ? this.decodeToken(session.accessToken) : null;
    this.userSubject.next(
      claims
        ? {
            id: claims.sub,
            tenantId: claims.tenantId,
            tenantName: claims.tenantName,
            displayName: claims.displayName,
            role: claims.role,
            readOnly: claims.readOnly === true
          }
        : null
    );
    this.isAuthenticatedSubject.next(this.hasValidToken());
  }

  private decodeToken(token: string): any {
    try {
      const base64Url = token.split('.')[1];
      const base64 = base64Url.replace(/-/g, '+').replace(/_/g, '/');
      const jsonPayload = decodeURIComponent(
        atob(base64)
          .split('')
          .map((c) => '%' + ('00' + c.charCodeAt(0).toString(16)).slice(-2))
          .join('')
      );
      return JSON.parse(jsonPayload);
    } catch (e) {
      return null;
    }
  }
}
