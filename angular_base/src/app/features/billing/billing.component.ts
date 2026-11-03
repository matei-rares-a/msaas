import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { AccessService } from '../../core/services/access.service';
import { BillingService, Invoice, Subscription } from '../../core/services/billing.service';

/**
 * Billing of the tenant (REQ-MGR-02). An ADMIN and a MANAGER see the subscription and the invoices; only an ADMIN with
 * write access is offered an action, and it is cancelling: the plan catalog has no endpoint, so choosing or changing a
 * plan is not offered here.
 */
@Component({
  selector: 'app-billing',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './billing.component.html',
  styleUrls: ['./billing.component.css']
})
export class BillingComponent implements OnInit {
  permissions$ = this.access.permissions$;
  subscription: Subscription | null = null;
  invoices: Invoice[] = [];
  loading = true;
  error: string | null = null;
  success: string | null = null;

  constructor(private billing: BillingService, private access: AccessService) {}

  ngOnInit() {
    this.billing.getSubscription().subscribe({
      next: subscription => (this.subscription = subscription),
      // a tenant with no subscription yet answers 404: that is a state, not a failure
      error: error => {
        if (error.status !== 404) {
          this.error = 'The subscription could not be loaded.';
        }
      }
    });
    this.billing.getInvoices().subscribe({
      next: invoices => {
        this.invoices = invoices;
        this.loading = false;
      },
      error: () => {
        this.error = this.error ?? 'The invoices could not be loaded.';
        this.loading = false;
      }
    });
  }

  cancel() {
    this.error = null;
    this.billing.cancelSubscription().subscribe({
      next: subscription => {
        this.subscription = subscription;
        this.success = 'The subscription is cancelled.';
      },
      error: error => (this.error = error.error?.message || 'The subscription could not be cancelled.')
    });
  }

  statusClass(status: string): string {
    return `status-${status.toLowerCase().replace(/_/g, '-')}`;
  }
}
