import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AccessService, Permissions } from '../services/access.service';

/**
 * Pages only some members may open. This hides the page; the server is what refuses the calls (REQ-ROLE-05), so a user
 * who types the address still gets nothing. Whoever is turned away lands on the dashboard.
 */
export function permissionGuard(allowed: (permissions: Permissions) => boolean): CanActivateFn {
  return () => (allowed(inject(AccessService).permissions) ? true : inject(Router).createUrlTree(['/dashboard']));
}

export const billingGuard = permissionGuard(p => p.canSeeBilling);
export const journalGuard = permissionGuard(p => p.canSeeJournal);
export const invitationsGuard = permissionGuard(p => p.canSeeBilling);
