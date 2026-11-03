import { Component, OnDestroy, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterOutlet, RouterLink, RouterLinkActive } from '@angular/router';
import { Subscription, of } from 'rxjs';
import { catchError, distinctUntilChanged, map, switchMap } from 'rxjs/operators';
import { AuthService } from './core/services/auth.service';
import { ThemeService } from './core/services/theme.service';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [CommonModule, RouterOutlet, RouterLink, RouterLinkActive],
  templateUrl: './app.component.html',
  styleUrls: ['./app.component.css']
})
export class AppComponent implements OnInit, OnDestroy {
  user$ = this.authService.user$;
  logo$ = this.themeService.logo$;

  private subscription?: Subscription;

  constructor(private authService: AuthService, private themeService: ThemeService) {}

  /**
   * The theme comes with the tenant (REQ-PERS-06): when the app opens for a tenant, and whenever the active tenant
   * changes, its own theme is read from its endpoint and applied; with nobody signed in the default look is back.
   * A theme that cannot be read leaves the default look and breaks nothing else.
   */
  ngOnInit() {
    this.subscription = this.authService.user$
      .pipe(
        map(user => user?.tenantId ?? null),
        distinctUntilChanged(),
        switchMap(tenantId => {
          if (!tenantId) {
            this.themeService.clear();
            return of(null);
          }
          return this.themeService.loadFor(tenantId).pipe(catchError(() => of(null)));
        })
      )
      .subscribe();
  }

  ngOnDestroy() {
    this.subscription?.unsubscribe();
  }
}
