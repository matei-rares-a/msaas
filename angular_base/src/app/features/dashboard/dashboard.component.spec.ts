import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { DashboardComponent } from './dashboard.component';
import { fakeJwt } from '../../../testing/sessions';

function openAs(role: string, readOnly = false) {
  localStorage.clear();
  TestBed.resetTestingModule();
  const token = fakeJwt({ sub: 'u', tenantId: 't', tenantName: 'Acme', displayName: 'a@test.local', role, readOnly });
  localStorage.setItem('msaas.sessions', JSON.stringify({ t: { accessToken: token, refreshToken: 'r', expiresAt: Date.now() + 60_000 } }));
  localStorage.setItem('msaas.activeTenant', 't');
  TestBed.configureTestingModule({ imports: [DashboardComponent], providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])] });
  const fixture = TestBed.createComponent(DashboardComponent);
  fixture.detectChanges();
  return (id: string) => fixture.nativeElement.querySelector(`[data-testid="${id}"]`) as HTMLElement | null;
}

describe('REQ-ROLE-05 the home offers each role only what it may open', () => {
  afterEach(() => localStorage.clear());

  it('offers a USER only the notifications', () => {
    const el = openAs('USER');

    expect(el('role')!.textContent).toBe('USER');
    expect(el('tile-notifications')).not.toBeNull();
    for (const tile of ['tile-billing', 'tile-invitations', 'tile-journal', 'tile-theme']) {
      expect(el(tile)).withContext(tile).toBeNull();
    }
  });

  it('offers a MANAGER billing, invitations and the journal, not the theme', () => {
    const el = openAs('MANAGER');

    expect(el('tile-billing') && el('tile-invitations') && el('tile-journal')).toBeTruthy();
    expect(el('tile-theme')).toBeNull();
  });

  it('offers an ADMIN everything', () => {
    const el = openAs('ADMIN');

    for (const tile of ['tile-notifications', 'tile-billing', 'tile-invitations', 'tile-journal', 'tile-theme']) {
      expect(el(tile)).withContext(tile).not.toBeNull();
    }
  });
});

describe('REQ-ROLE-06 the home says when the access is read-only', () => {
  afterEach(() => localStorage.clear());

  it('shows the flag and the notice for a read-only member, and neither for one who can write', () => {
    const readOnly = openAs('ADMIN', true);
    expect(readOnly('readonly-flag')).not.toBeNull();
    expect(readOnly('readonly-notice')!.textContent).toContain('read-only');
    expect(readOnly('tile-theme')).toBeNull();

    const writer = openAs('ADMIN');
    expect(writer('readonly-flag')).toBeNull();
    expect(writer('readonly-notice')).toBeNull();
  });
});
