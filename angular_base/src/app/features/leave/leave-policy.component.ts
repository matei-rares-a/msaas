import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { AuthService } from '../../core/services/auth.service';
import { Holiday, LeaveService } from '../../core/services/leave.service';

/**
 * The leave policy of the organization for an ADMIN with write access (REQ-LEAVE-02, REQ-LEAVE-03): the default yearly
 * allowance, the allowance of one member, and the days off that are not counted as leave.
 */
@Component({
  selector: 'app-leave-policy',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink],
  templateUrl: './leave-policy.component.html',
  styleUrls: ['./leave.component.css']
})
export class LeavePolicyComponent implements OnInit {
  allowance: number | null = null;
  overrideUserId = '';
  overrideAllowance: number | null = null;
  year = new Date().getFullYear();
  holidays: Holiday[] = [];
  holidayDate = '';
  holidayName = '';
  error: string | null = null;
  message: string | null = null;

  constructor(private auth: AuthService, private leave: LeaveService) {}

  private get tenantId(): string {
    return this.auth.currentUser?.tenantId ?? '';
  }

  ngOnInit() {
    this.leave.getPolicy(this.tenantId).subscribe({ next: policy => (this.allowance = policy.defaultAllowance), error: () => (this.error = 'The policy could not be loaded.') });
    this.loadHolidays();
  }

  loadHolidays() {
    this.leave.holidays(this.tenantId, this.year).subscribe({ next: holidays => (this.holidays = holidays), error: () => (this.error = 'The days off could not be loaded.') });
  }

  savePolicy() {
    if (this.allowance === null || this.allowance < 0) {
      this.error = 'Give the number of days as zero or more.';
      return;
    }
    this.run(this.leave.setPolicy(this.tenantId, this.allowance), 'The default allowance is saved. It applies to everyone without an allowance of their own.');
  }

  saveOverride() {
    if (!this.overrideUserId.trim() || this.overrideAllowance === null || this.overrideAllowance < 0) {
      this.error = 'Give the member and a number of days of zero or more.';
      return;
    }
    this.run(this.leave.setAllowance(this.tenantId, this.overrideUserId.trim(), this.overrideAllowance), 'The allowance of that member is saved.');
  }

  clearOverride() {
    if (!this.overrideUserId.trim()) {
      this.error = 'Give the member.';
      return;
    }
    this.run(this.leave.clearAllowance(this.tenantId, this.overrideUserId.trim()), 'That member is back on the default allowance.');
  }

  addHoliday() {
    if (!this.holidayDate || !this.holidayName.trim()) {
      this.error = 'Give the date and a name.';
      return;
    }
    this.run(this.leave.addHoliday(this.tenantId, { date: this.holidayDate, name: this.holidayName.trim() }), 'The day off is added.', () => {
      this.holidayDate = '';
      this.holidayName = '';
      this.loadHolidays();
    });
  }

  removeHoliday(holiday: Holiday) {
    this.run(this.leave.removeHoliday(this.tenantId, holiday.date), 'The day off is removed.', () => this.loadHolidays());
  }

  private run(call: import('rxjs').Observable<unknown>, message: string, then?: () => void) {
    this.error = null;
    this.message = null;
    call.subscribe({
      next: () => {
        this.message = message;
        then?.();
      },
      error: error => (this.error = error.error?.message || 'That could not be saved.')
    });
  }
}
