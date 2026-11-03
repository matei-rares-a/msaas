import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { AccessService } from '../../core/services/access.service';
import { AuthService } from '../../core/services/auth.service';
import { Balance, CalendarEntry, LeaveRequest, LeaveService } from '../../core/services/leave.service';

const iso = (date: Date) =>
  `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;

/**
 * Leave for the signed-in member (REQ-LEAVE-01, REQ-LEAVE-03, REQ-LEAVE-04, REQ-LEAVE-06): the balance, a request for
 * themselves, their own requests with the way to cancel one, and the team calendar, which says who is away and when and
 * nothing about why. The server counts the days, checks the balance and decides who may do what; this only asks.
 */
@Component({
  selector: 'app-leave',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule, RouterLink],
  templateUrl: './leave.component.html',
  styleUrls: ['./leave.component.css']
})
export class LeaveComponent implements OnInit {
  permissions$ = this.access.permissions$;
  form: FormGroup;
  balance: Balance | null = null;
  requests: LeaveRequest[] = [];
  calendar: CalendarEntry[] = [];
  loading = true;
  error: string | null = null;
  message: string | null = null;

  constructor(private access: AccessService, private auth: AuthService, private leave: LeaveService, formBuilder: FormBuilder) {
    this.form = formBuilder.group({
      type: ['ODIHNA', Validators.required],
      startDate: ['', Validators.required],
      endDate: ['', Validators.required],
      reason: ['', Validators.maxLength(500)]
    });
  }

  private get tenantId(): string {
    return this.auth.currentUser?.tenantId ?? '';
  }

  ngOnInit() {
    this.reload();
  }

  reload() {
    const today = new Date();
    const from = new Date(today.getFullYear(), today.getMonth(), 1);
    const to = new Date(today.getFullYear(), today.getMonth() + 2, 0);
    this.leave.balance(this.tenantId).subscribe({ next: balance => (this.balance = balance), error: () => (this.error = 'The balance could not be loaded.') });
    this.leave.list(this.tenantId, { scope: 'MINE' }).subscribe({
      next: page => {
        this.requests = page.items;
        this.loading = false;
      },
      error: () => {
        this.error = 'Your requests could not be loaded.';
        this.loading = false;
      }
    });
    this.leave.calendar(this.tenantId, iso(from), iso(to)).subscribe({ next: entries => (this.calendar = entries), error: () => undefined });
  }

  submit() {
    if (this.form.invalid || this.form.value.endDate < this.form.value.startDate) {
      this.form.markAllAsTouched();
      this.error = this.form.value.endDate < this.form.value.startDate ? 'The last day cannot be before the first day.' : null;
      return;
    }
    this.error = null;
    this.message = null;
    const { type, startDate, endDate, reason } = this.form.value;
    this.leave.create(this.tenantId, { type, startDate, endDate, reason: reason?.trim() || undefined }).subscribe({
      next: request => {
        this.message = request.autoApproved ? 'Your leave is approved.' : 'Your request is sent for a decision.';
        this.form.reset({ type: 'ODIHNA', startDate: '', endDate: '', reason: '' });
        this.reload();
      },
      error: error => (this.error = error.error?.message || 'The request could not be sent.')
    });
  }

  cancel(request: LeaveRequest) {
    this.error = null;
    this.leave.cancel(this.tenantId, request.id).subscribe({
      next: () => {
        this.message = 'The request is cancelled and its days are back.';
        this.reload();
      },
      error: error => (this.error = error.error?.message || 'The request could not be cancelled.')
    });
  }

  /** Cancelling is offered for what is still open or approved; the server refuses one that has begun. */
  canCancel(request: LeaveRequest): boolean {
    return request.status === 'PENDING' || request.status === 'APPROVED';
  }

  isMine(entry: CalendarEntry): boolean {
    return entry.userId === this.auth.currentUser?.id;
  }

  shortId(id: string): string {
    return id.substring(0, 8);
  }
}
