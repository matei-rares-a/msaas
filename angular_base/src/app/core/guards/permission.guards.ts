import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { map } from 'rxjs/operators';
import { AccessService, Permissions } from '../services/access.service';
import { AuthService } from '../services/auth.service';
import { LEAVE_MODULE, LUNCH_MODULE, ModulesService } from '../services/modules.service';

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

/**
 * A page of a business module opens only while the module is on for the organization (REQ-MOD-01), and only for who may
 * use it. The module settings are read first when the app has not already: the page must not open on a guess.
 */
export function moduleGuard(module: string, allowed: (permissions: Permissions) => boolean = () => true): CanActivateFn {
  return () => {
    const router = inject(Router);
    const tenantId = inject(AuthService).currentUser?.tenantId;
    if (!tenantId || !allowed(inject(AccessService).permissions)) {
      return router.createUrlTree(['/dashboard']);
    }
    return inject(ModulesService).ensureLoaded(tenantId).pipe(map(state => (state?.modules[module] === true ? true : router.createUrlTree(['/dashboard']))));
  };
}

export const leaveGuard = moduleGuard(LEAVE_MODULE);
export const leaveApprovalsGuard = moduleGuard(LEAVE_MODULE, p => p.canApproveLeave);
export const leavePolicyGuard = moduleGuard(LEAVE_MODULE, p => p.canSetLeavePolicy);
export const lunchGuard = moduleGuard(LUNCH_MODULE);

/** The module settings page: an ADMIN with write access only. */
export const modulesGuard = permissionGuard(p => p.canManageModules);
