import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { BehaviorSubject, Observable, of } from 'rxjs';
import { catchError, map, switchMap, tap } from 'rxjs/operators';

export interface Palette {
  id: string;
  name: string;
  kind: string;
  definition: { primary?: string; secondary?: string; accent?: string; gradient?: string[] };
}

export interface Theme {
  paletteId: string;
  palette: Palette | null;
  logoUrl: string | null;
}

const HEX_COLOR = /^#[0-9A-Fa-f]{6}$/;

/** The variables the stylesheet reads; a palette sets them, and clearing removes them so the defaults show again. */
const VARIABLES = ['--primary-color', '--secondary-color', '--accent-color', '--brand-gradient'];

/**
 * A tenant's theme (REQ-PERS-01, REQ-PERS-06): read from its own endpoint when the app opens for a tenant, applied as CSS
 * variables, never with CSS that came from a tenant - a palette is only ever three colours and an optional gradient,
 * each checked to be #RRGGBB before it is used. The logo is fetched as a blob with the user's token, because an
 * {@code <img src>} cannot send one, and only from this tenant's own logo path, so the token goes nowhere else.
 */
@Injectable({
  providedIn: 'root'
})
export class ThemeService {
  private apiUrl = 'http://localhost:8080';

  private themeSubject = new BehaviorSubject<Theme | null>(null);
  public theme$ = this.themeSubject.asObservable();

  private logoSubject = new BehaviorSubject<string | null>(null);
  /** A blob: URL of the logo, or null when the tenant has none. */
  public logo$ = this.logoSubject.asObservable();

  constructor(private http: HttpClient) {}

  /** Reads the tenant's theme, applies it and brings its logo; a failure leaves the default look. */
  loadFor(tenantId: string): Observable<Theme> {
    return this.http.get<Theme>(`${this.apiUrl}/organizations/${tenantId}/theme`).pipe(
      tap(theme => this.show(theme)),
      switchMap(theme => this.loadLogo(tenantId, theme.logoUrl).pipe(map(() => theme)))
    );
  }

  listPalettes(tenantId: string): Observable<Palette[]> {
    return this.http.get<Palette[]>(`${this.apiUrl}/organizations/${tenantId}/theme/palettes`);
  }

  /** ADMIN only (the server decides); the new palette is applied as soon as it is saved. */
  updatePalette(tenantId: string, paletteId: string): Observable<Theme> {
    return this.http.put<Theme>(`${this.apiUrl}/organizations/${tenantId}/theme`, { paletteId }).pipe(
      tap(theme => this.show(theme))
    );
  }

  /** multipart, one part named "file"; the logo is fetched again afterwards so every page shows the new one. */
  uploadLogo(tenantId: string, file: File): Observable<void> {
    const form = new FormData();
    form.append('file', file);
    return this.http.put<{ logoUrl: string }>(`${this.apiUrl}/organizations/${tenantId}/theme/logo`, form).pipe(
      tap(result => {
        const current = this.themeSubject.value;
        if (current) {
          this.themeSubject.next({ ...current, logoUrl: result.logoUrl });
        }
      }),
      switchMap(result => this.loadLogo(tenantId, result.logoUrl))
    );
  }
  /** Back to the default look, with no logo (nobody is signed in, or the tenant changed). */
  clear(): void {
    this.removeVariables();
    this.setLogo(null);
    this.themeSubject.next(null);
  }

  /** The colours of a palette that may be shown or applied: only well-formed #RRGGBB values. */
  safeColors(palette: Palette | null | undefined): { primary?: string; secondary?: string; accent?: string; gradient?: string[] } {
    const definition = palette?.definition ?? {};
    const pick = (value: unknown) => (typeof value === 'string' && HEX_COLOR.test(value) ? value : undefined);
    const gradient = Array.isArray(definition.gradient) ? definition.gradient.map(pick) : [];
    return {
      primary: pick(definition.primary),
      secondary: pick(definition.secondary),
      accent: pick(definition.accent),
      gradient: gradient.length >= 2 && gradient.every(color => !!color) ? (gradient as string[]) : undefined
    };
  }

  private show(theme: Theme): void {
    this.themeSubject.next(theme);
    this.applyColors(theme.palette);
  }

  private applyColors(palette: Palette | null): void {
    this.removeVariables();
    const colors = this.safeColors(palette);
    const root = document.documentElement.style;
    if (colors.primary) {
      root.setProperty('--primary-color', colors.primary);
    }
    if (colors.secondary) {
      root.setProperty('--secondary-color', colors.secondary);
    }
    if (colors.accent) {
      root.setProperty('--accent-color', colors.accent);
    }
    if (colors.gradient) {
      root.setProperty('--brand-gradient', `linear-gradient(135deg, ${colors.gradient.join(', ')})`);
    }
  }

  private removeVariables(): void {
    VARIABLES.forEach(name => document.documentElement.style.removeProperty(name));
  }

  private loadLogo(tenantId: string, logoUrl: string | null): Observable<void> {
    // the token goes only to this tenant's own logo path, whatever the server's answer says
    const expectedPrefix = `/organizations/${tenantId}/theme/logo`;
    if (!logoUrl || !logoUrl.startsWith(expectedPrefix)) {
      this.setLogo(null);
      return of(undefined);
    }
    return this.http.get(`${this.apiUrl}${logoUrl}`, { responseType: 'blob' }).pipe(
      tap(blob => this.setLogo(URL.createObjectURL(blob))),
      map(() => undefined),
      catchError(() => {
        this.setLogo(null);
        return of(undefined);
      })
    );
  }

  private setLogo(objectUrl: string | null): void {
    const previous = this.logoSubject.value;
    if (previous) {
      URL.revokeObjectURL(previous);
    }
    this.logoSubject.next(objectUrl);
  }
}
