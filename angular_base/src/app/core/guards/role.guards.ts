import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from '../services/auth.service';

/**
 * Pages only an ADMIN with write access can use. This hides the page; the server is what refuses the calls
 * (REQ-ROLE-01, REQ-ROLE-06), so a user who reaches the URL by hand still changes nothing.
 */
export const adminGuard: CanActivateFn = () => {
  const user = inject(AuthService).currentUser;
  return user && user.role === 'ADMIN' && !user.readOnly ? true : inject(Router).createUrlTree(['/dashboard']);
};
