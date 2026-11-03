import { Permissions, permissionsOf } from './access.service';

const as = (role: string, readOnly = false) => permissionsOf({ role, readOnly });

describe('REQ-ROLE-05 a USER is offered basic access only: no management, no invitations, no billing', () => {
  it('offers a USER nothing that manages', () => {
    const p = as('USER');

    expect(p.role).toBe('USER');
    expect(p.canInvite).toBeFalse();
    expect(p.invitableRoles).toEqual([]);
    expect(p.canSeeBilling).toBeFalse();
    expect(p.canChangeBilling).toBeFalse();
    expect(p.canSeeJournal).toBeFalse();
    expect(p.canEditTheme).toBeFalse();
  });

  it('offers nothing to someone who is not signed in or whose role is not one the app knows', () => {
    for (const p of [permissionsOf(null), as('SUPERUSER'), as('')]) {
      expect(p.role).toBeNull();
      expect(p.canSeeBilling || p.canSeeJournal || p.canInvite || p.canEditTheme || p.canChangeBilling).toBeFalse();
    }
  });
});

describe('REQ-MGR-02 a MANAGER sees billing but is offered no financial action', () => {
  it('lets a MANAGER see billing and offers no change to it', () => {
    const p = as('MANAGER');

    expect(p.canSeeBilling).toBeTrue();
    expect(p.canChangeBilling).toBeFalse();
  });

  it('lets only an ADMIN with write access change billing', () => {
    expect(as('ADMIN').canChangeBilling).toBeTrue();
    expect(as('ADMIN', true).canChangeBilling).toBeFalse();
    expect(as('ADMIN', true).canSeeBilling).toBeTrue();
  });
});

describe('REQ-MGR-04 the journal is for ADMIN and MANAGER', () => {
  it('offers it to them, even when their access is read-only, and not to a USER', () => {
    expect(as('ADMIN').canSeeJournal).toBeTrue();
    expect(as('MANAGER').canSeeJournal).toBeTrue();
    expect(as('MANAGER', true).canSeeJournal).toBeTrue();
    expect(as('USER').canSeeJournal).toBeFalse();
  });
});

describe('REQ-ROLE-06 the read-only flag takes every write away, whatever the role', () => {
  it('offers no write to a read-only member of any role, and says so', () => {
    for (const role of ['ADMIN', 'MANAGER', 'USER']) {
      const p: Permissions = as(role, true);

      expect(p.readOnly).withContext(role).toBeTrue();
      expect(p.canInvite).withContext(role).toBeFalse();
      expect(p.invitableRoles).withContext(role).toEqual([]);
      expect(p.canChangeBilling).withContext(role).toBeFalse();
      expect(p.canEditTheme).withContext(role).toBeFalse();
    }
  });

  it('offers an ADMIN with write access everything', () => {
    const p = as('ADMIN');

    expect(p.readOnly).toBeFalse();
    expect(p.canInvite && p.canEditTheme && p.canChangeBilling && p.canSeeBilling && p.canSeeJournal).toBeTrue();
  });
});

describe('REQ-MGR-01 who may be invited with which role', () => {
  it('lets an ADMIN invite with any role and a MANAGER only with USER', () => {
    expect(as('ADMIN').invitableRoles.sort()).toEqual(['ADMIN', 'MANAGER', 'USER']);
    expect(as('MANAGER').invitableRoles).toEqual(['USER']);
  });
});
