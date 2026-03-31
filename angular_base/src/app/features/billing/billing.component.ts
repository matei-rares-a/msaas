import { Component, OnInit, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, Router } from '@angular/router';
import { Subject } from 'rxjs';
import { takeUntil } from 'rxjs/operators';
import { BillingService, Plan, Subscription, Invoice } from '../../core/services/billing.service';

@Component({
  selector: 'app-billing',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './billing.component.html',
  styleUrls: ['./billing.component.css']
})
export class BillingComponent implements OnInit, OnDestroy {
  plans: Plan[] = [];
  subscription: Subscription | null = null;
  invoices: Invoice[] = [];
  loading = true;
  error: string | null = null;
  success: string | null = null;
  selectingPlanId: string | null = null;
  organizationId: string | null = null;

  private destroy$ = new Subject<void>();

  constructor(
    private billingService: BillingService,
    private route: ActivatedRoute,
    private router: Router
  ) {}

  ngOnInit() {
    this.organizationId = this.route.snapshot.paramMap.get('id');
    this.loadData();
  }

  ngOnDestroy() {
    this.destroy$.next();
    this.destroy$.complete();
  }

  loadData() {
    this.loading = true;
    this.billingService.getPlans()
      .pipe(takeUntil(this.destroy$))
      .subscribe(
        (plans) => {
          this.plans = plans;
          this.loadSubscription();
        },
        (error) => {
          this.error = 'Failed to load billing information';
          this.loading = false;
        }
      );
  }

  loadSubscription() {
    this.billingService.getSubscription()
      .pipe(takeUntil(this.destroy$))
      .subscribe(
        (subscription) => {
          this.subscription = subscription;
          this.loadInvoices();
        },
        (error) => {
          // Subscription may not exist yet
          this.loadInvoices();
        }
      );
  }

  loadInvoices() {
    this.billingService.getInvoices()
      .pipe(takeUntil(this.destroy$))
      .subscribe(
        (invoices) => {
          this.invoices = invoices;
          this.loading = false;
        },
        (error) => {
          this.loading = false;
        }
      );
  }

  subscribe(planId: string) {
    this.selectingPlanId = planId;
    this.billingService.subscribe({ planId })
      .pipe(takeUntil(this.destroy$))
      .subscribe(
        (subscription) => {
          this.subscription = subscription;
          this.success = 'Successfully subscribed to plan!';
          this.selectingPlanId = null;
          setTimeout(() => this.success = null, 5000);
        },
        (error) => {
          this.error = error.error?.message || 'Failed to subscribe';
          this.selectingPlanId = null;
        }
      );
  }

  cancelSubscription() {
    if (!confirm('Are you sure you want to cancel your subscription?')) {
      return;
    }

    this.billingService.cancelSubscription()
      .pipe(takeUntil(this.destroy$))
      .subscribe(
        () => {
          this.subscription = null;
          this.success = 'Subscription cancelled';
          setTimeout(() => this.success = null, 5000);
        },
        (error) => {
          this.error = 'Failed to cancel subscription';
        }
      );
  }

  getStatusClass(status: string): string {
    switch (status) {
      case 'ACTIVE':
        return 'status-active';
      case 'TRIALING':
        return 'status-trialing';
      case 'PAST_DUE':
        return 'status-past-due';
      case 'CANCELED':
        return 'status-canceled';
      default:
        return '';
    }
  }

  navigateBack() {
    this.router.navigate(['/dashboard']);
  }
}
