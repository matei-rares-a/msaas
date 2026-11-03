import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { BehaviorSubject, Observable, of } from 'rxjs';
import { catchError, map, tap } from 'rxjs/operators';

export const LEAVE_MODULE = 'LEAVE';
export const LUNCH_MODULE = 'LUNCH_ROULETTE';

export interface ModulesState {
  modules: Record<string, boolean>;
  timezone: string;
}

/**
 * The business modules of the organization and its time zone (REQ-MOD-01). A module that is off does not exist for the
 * members: the app offers no link to it and its pages turn them away; the server answers 404 for it as well. Only an
 * ADMIN switches modules.
 */
@Injectable({
  providedIn: 'root'
})
export class ModulesService {
  private apiUrl = 'http://localhost:8080/organizations';

  private stateSubject = new BehaviorSubject<ModulesState | null>(null);
  public state$ = this.stateSubject.asObservable();

  constructor(private http: HttpClient) {}

  get state(): ModulesState | null {
    return this.stateSubject.value;
  }

  isEnabled(module: string): boolean {
    return this.stateSubject.value?.modules[module] === true;
  }

  /** Reads the settings of the tenant; a failure leaves every module off for the interface. */
  load(tenantId: string): Observable<ModulesState | null> {
    return this.http.get<ModulesState>(`${this.apiUrl}/${tenantId}/modules`).pipe(
      tap(state => this.stateSubject.next(state)),
      catchError(() => {
        this.stateSubject.next(null);
        return of(null);
      })
    );
  }

  /** The settings already read, or read now: for a page that must know before it opens. */
  ensureLoaded(tenantId: string): Observable<ModulesState | null> {
    const current = this.stateSubject.value;
    return current ? of(current) : this.load(tenantId);
  }

  clear(): void {
    this.stateSubject.next(null);
  }

  setEnabled(tenantId: string, module: string, enabled: boolean): Observable<ModulesState> {
    return this.http.put<ModulesState>(`${this.apiUrl}/${tenantId}/modules/${module}`, { enabled }).pipe(
      tap(state => this.stateSubject.next(state))
    );
  }

  setTimezone(tenantId: string, timezone: string): Observable<ModulesState> {
    return this.http.put<ModulesState>(`${this.apiUrl}/${tenantId}/timezone`, { timezone }).pipe(
      tap(state => this.stateSubject.next(state))
    );
  }

  enabled$(module: string): Observable<boolean> {
    return this.state$.pipe(map(state => state?.modules[module] === true));
  }
}
