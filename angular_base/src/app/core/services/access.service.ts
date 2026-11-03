import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { map } from 'rxjs/operators';
import { AuthService, User } from './auth.service';

export type Role = 'ADMIN' | 'MANAGER' | 'USER';

/** What the signed-in user may be offered in the interface. The server decides what they may do; this only avoids offering the rest. */
export interface Permissions {
  role: Role | null;
  /** The membership's read-only flag: nothing that writes is offered (REQ-ROLE-06). */
  readOnly: boolean;
  /** ADMIN and MANAGER may look at billing (REQ-MGR-02); a USER has no access to it at all (REQ-ROLE-05). */
  canSeeBilling: boolean;
  /** Only an ADMIN with write access acts on the subscription (REQ-MGR-02). */
  canChangeBilling: boolean;
  /** ADMIN and MANAGER read the activity journal (REQ-MGR-04). */
  canSeeJournal: boolean;
  /** A USER cannot invite (REQ-ROLE-05); a read-only member cannot write anything (REQ-ROLE-06). */
  canInvite: boolean;
  /** An ADMIN invites with any role, a MANAGER only with USER (REQ-MGR-01). */
  invitableRoles: Role[];
  canEditTheme: boolean;
}

const NOBODY: Permissions = {
  role: null, readOnly: false, canSeeBilling: false, canChangeBilling: false, canSeeJournal: false, canInvite: false,
  invitableRoles: [], canEditTheme: false
};

export function permissionsOf(user: Pick<User, 'role' | 'readOnly'> | null): Permissions {
  if (!user) {
    return NOBODY;
  }
  const role = (['ADMIN', 'MANAGER', 'USER'] as const).find(known => known === user.role) ?? null;
  if (!role) {
    return NOBODY;
  }
  const staff = role === 'ADMIN' || role === 'MANAGER';
  const canWrite = !user.readOnly;
  return {
    role,
    readOnly: user.readOnly,
    canSeeBilling: staff,
    canChangeBilling: role === 'ADMIN' && canWrite,
    canSeeJournal: staff,
    canInvite: staff && canWrite,
    invitableRoles: staff && canWrite ? (role === 'ADMIN' ? ['USER', 'MANAGER', 'ADMIN'] : ['USER']) : [],
    canEditTheme: role === 'ADMIN' && canWrite
  };
}

@Injectable({
  providedIn: 'root'
})
export class AccessService {
  permissions$: Observable<Permissions> = this.auth.user$.pipe(map(permissionsOf));

  constructor(private auth: AuthService) {}

  get permissions(): Permissions {
    return permissionsOf(this.auth.currentUser);
  }
}
