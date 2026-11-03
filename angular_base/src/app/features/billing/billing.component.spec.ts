import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { BillingComponent } from './billing.component';
import { fakeJwt } from '../../../testing/sessions';

const subscription = { id: 's1', planId: 'p1', status: 'ACTIVE', startDate: '2026-11-01T00:00:00Z', endDate: null, pastDueSince: null };
const invoice = { id: 'i1', subscriptionId: 's1', amountEur: 9.99, status: 'PAID', issuedAt: '2026-11-01T00:00:00Z', paidAt: '2026-11-01T00:00:00Z' };

function openAs(role: string, readOnly = false, subscriptionAnswer: object | 404 = subscription) {
  localStorage.clear();
  TestBed.resetTestingModule();
  const token = fakeJwt({ sub: 'u', tenantId: 't', tenantName: 'T', displayName: 'd', role, readOnly });
  localStorage.setItem('msaas.sessions', JSON.stringify({ t: { accessToken: token, refreshToken: 'r', expiresAt: Date.now() + 60_000 } }));
  localStorage.setItem('msaas.activeTenant', 't');
  TestBed.configureTestingModule({ imports: [BillingComponent], providers: [provideHttpClient(), provideHttpClientTesting()] });
  const http = TestBed.inject(HttpTestingController);
  const fixture: ComponentFixture<BillingComponent> = TestBed.createComponent(BillingComponent);
  fixture.detectChanges();
  const request = http.expectOne('http://localhost:8080/billing/subscription');
  if (subscriptionAnswer === 404) {
    request.flush({}, { status: 404, statusText: 'Not Found' });
  } else {
    request.flush(subscriptionAnswer as object);
  }
  http.expectOne('http://localhost:8080/billing/invoices').flush([invoice]);
  fixture.detectChanges();
  return { fixture, http, el: (id: string) => fixture.nativeElement.querySelector(`[data-testid="${id}"]`) as HTMLElement | null };
}

describe('REQ-MGR-02 billing: a MANAGER sees it and has no financial action', () => {
  afterEach(() => localStorage.clear());

  it('shows a MANAGER the subscription and the invoices and no cancel button, with a note that changes are the administrator\'s', () => {
    const { el, fixture } = openAs('MANAGER');

    expect(el('subscription-status')?.textContent).toContain('ACTIVE');
    expect(fixture.nativeElement.querySelectorAll('[data-testid=invoice]').length).toBe(1);
    expect(el('cancel-subscription')).toBeNull();
    expect(el('billing-readonly')?.textContent).toContain('administrator');
  });

  it('shows an ADMIN with write access the cancel button, and no read-only note', () => {
    const { el } = openAs('ADMIN');

    expect(el('cancel-subscription')).not.toBeNull();
    expect(el('billing-readonly')).toBeNull();
  });

  it('shows a read-only ADMIN what a MANAGER sees', () => {
    const { el } = openAs('ADMIN', true);

    expect(el('subscription-status')).not.toBeNull();
    expect(el('cancel-subscription')).toBeNull();
  });

  it('cancels with the one call and shows the new state', () => {
    const { el, http, fixture } = openAs('ADMIN');

    el('cancel-subscription')!.click();
    const request = http.expectOne('http://localhost:8080/billing/subscriptions/cancel');
    expect(request.request.method).toBe('POST');
    request.flush({ ...subscription, status: 'CANCELED' });
    fixture.detectChanges();

    expect(el('billing-success')).not.toBeNull();
    expect(el('subscription-status')?.textContent).toContain('CANCELED');
    expect(el('cancel-subscription')).toBeNull();
  });

  it('says so when the server refuses the cancellation', () => {
    const { el, http, fixture } = openAs('ADMIN');

    el('cancel-subscription')!.click();
    http.expectOne('http://localhost:8080/billing/subscriptions/cancel').flush({ message: 'only an ADMIN' }, { status: 403, statusText: 'Forbidden' });
    fixture.detectChanges();

    expect(el('billing-error')?.textContent).toContain('only an ADMIN');
  });

  it('treats a tenant with no subscription as a state, not an error', () => {
    const { el } = openAs('MANAGER', false, 404);

    expect(el('no-subscription')).not.toBeNull();
    expect(el('billing-error')).toBeNull();
  });
});
