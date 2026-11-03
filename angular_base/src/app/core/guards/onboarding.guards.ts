import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from '../services/auth.service';

/** The tenant choice exists only right after a login that found more than one tenant. */
export const pendingChoiceGuard: CanActivateFn = () =>
  inject(AuthService).getPendingChoice() ? true : inject(Router).createUrlTree(['/login']);

/** The organization / invitation page exists only for a user holding an identity token (no tenant yet). */
export const identityGuard: CanActivateFn = () =>
  inject(AuthService).getIdentityToken() ? true : inject(Router).createUrlTree(['/login']);
