import { Injectable } from '@angular/core';
import { HttpInterceptor, HttpRequest, HttpHandler, HttpEvent, HttpErrorResponse } from '@angular/common/http';
import { Router } from '@angular/router';
import { Observable, throwError, BehaviorSubject } from 'rxjs';
import { catchError, filter, take, switchMap } from 'rxjs/operators';
import { AuthService } from '../services/auth.service';

/** Calls that carry no token: the user has none yet, or is getting one. */
const PUBLIC_PATHS = ['/auth/register', '/auth/login', '/auth/refresh', '/auth/forgot-password', '/auth/reset-password'];

/** The only calls a user without a tenant may make, with the identity token (REQ-AUTH-03). */
const IDENTITY_ROUTES = ['POST /organizations', 'POST /invitations/accept'];

@Injectable()
export class AuthInterceptor implements HttpInterceptor {
  private isRefreshing = false;
  private refreshTokenSubject: BehaviorSubject<any> = new BehaviorSubject<any>(null);

  constructor(private authService: AuthService, private router: Router) {}

  intercept(request: HttpRequest<any>, next: HttpHandler): Observable<HttpEvent<any>> {
    const path = this.pathOf(request);

    if (PUBLIC_PATHS.some(publicPath => path.startsWith(publicPath))) {
      return next.handle(request);
    }

    // the identity token goes to the two routes that accept it and to nothing else
    const identityToken = this.authService.getIdentityToken();
    if (identityToken && IDENTITY_ROUTES.includes(`${request.method} ${path}`)) {
      return next.handle(this.addToken(request, identityToken));
    }

    const token = this.authService.getAccessToken();
    if (token) {
      request = this.addToken(request, token);
    }

    return next.handle(request).pipe(
      catchError(error => {
        if (error instanceof HttpErrorResponse && error.status === 401 && token) {
          return this.handle401Error(request, next);
        }
        return throwError(() => error);
      })
    );
  }

  private pathOf(request: HttpRequest<any>): string {
    try {
      return new URL(request.url, 'http://localhost').pathname;
    } catch {
      return request.url;
    }
  }

  private addToken(request: HttpRequest<any>, token: string): HttpRequest<any> {
    return request.clone({
      setHeaders: {
        Authorization: `Bearer ${token}`
      }
    });
  }

  private handle401Error(request: HttpRequest<any>, next: HttpHandler): Observable<HttpEvent<any>> {
    if (!this.isRefreshing) {
      this.isRefreshing = true;
      this.refreshTokenSubject.next(null);

      return this.authService.refresh().pipe(
        switchMap((response: any) => {
          this.isRefreshing = false;
          this.refreshTokenSubject.next(response.accessToken);
          return next.handle(this.addToken(request, response.accessToken));
        }),
        catchError((err) => {
          this.isRefreshing = false;
          this.authService.logout();
          this.router.navigate(['/login']);
          return throwError(() => err);
        })
      );
    } else {
      return this.refreshTokenSubject.pipe(
        filter(token => token != null),
        take(1),
        switchMap(token => {
          return next.handle(this.addToken(request, token));
        })
      );
    }
  }
}
