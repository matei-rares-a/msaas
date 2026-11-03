import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { AccessService } from '../../core/services/access.service';
import { AuthService } from '../../core/services/auth.service';
import { LunchConfig, LunchRound, LunchRoundDetail, LunchService, WEEKDAYS } from '../../core/services/lunch.service';

/**
 * The lunch roulette of the week (REQ-LUNCH-01..04): join and withdraw while the round is open, the participant's own
 * group once it is formed, every group for a MANAGER or an ADMIN, and for them the settings and the way to cancel the
 * round. The groups are lists of ids: the service keeps no names, so the page shows who is with you by a short id.
 */
@Component({
  selector: 'app-lunch',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule],
  templateUrl: './lunch.component.html',
  styleUrls: ['./lunch.component.css']
})
export class LunchComponent implements OnInit {
  permissions$ = this.access.permissions$;
  weekdays = WEEKDAYS;
  round: LunchRound | null = null;
  /** The latest round that was formed, which is no longer the current one once its joining closed. */
  formed: LunchRound | null = null;
  detail: LunchRoundDetail | null = null;
  config: LunchConfig | null = null;
  form: FormGroup;
  loading = true;
  error: string | null = null;
  message: string | null = null;

  constructor(private access: AccessService, private auth: AuthService, private lunch: LunchService, formBuilder: FormBuilder) {
    this.form = formBuilder.group({
      day: ['THURSDAY', Validators.required],
      cutoff: ['11:00', [Validators.required, Validators.pattern(/^([01]\d|2[0-3]):[0-5]\d$/)]],
      groupSize: [2, [Validators.required, Validators.min(2), Validators.max(4)]]
    });
  }

  private get tenantId(): string {
    return this.auth.currentUser?.tenantId ?? '';
  }

  ngOnInit() {
    this.reload();
    this.loadFormed(this.access.permissions.canSeeAllLunchGroups);
    this.lunch.config(this.tenantId).subscribe({
      next: config => {
        this.config = config;
        this.form.patchValue({ day: config.day, cutoff: config.cutoff, groupSize: config.groupSize });
      },
      error: () => (this.error = 'The settings could not be loaded.')
    });
  }

  reload() {
    this.lunch.current(this.tenantId).subscribe({
      next: round => {
        this.round = round;
        this.loading = false;
      },
      error: () => {
        this.error = 'The round could not be loaded.';
        this.loading = false;
      }
    });
  }

  /**
   * Once a round's joining closes it is no longer the current one, so the groups are looked for in the latest formed round:
   * the participant sees their own group, an approver all of them (REQ-LUNCH-04). Someone who was not in it sees nothing.
   */
  private loadFormed(canSeeAll: boolean) {
    this.lunch.rounds(this.tenantId, 0, 5).subscribe({
      next: page => {
        this.formed = page.items.find(item => item.status === 'FORMED' && !!item.id && (item.enrolled || canSeeAll)) ?? null;
        this.detail = null;
        if (this.formed?.id) {
          this.lunch.round(this.tenantId, this.formed.id).subscribe({ next: detail => (this.detail = detail), error: () => undefined });
        }
      },
      error: () => undefined
    });
  }

  get isOpen(): boolean {
    return this.round?.status === 'OPEN';
  }

  enroll() {
    this.change(this.lunch.enroll(this.tenantId), 'You are in this week\'s round.');
  }

  withdraw() {
    this.change(this.lunch.withdraw(this.tenantId), 'You are out of this week\'s round.');
  }

  cancelRound() {
    this.change(this.lunch.cancelCurrent(this.tenantId), 'The round is cancelled.');
  }

  saveConfig() {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    this.error = null;
    this.message = null;
    const { day, cutoff, groupSize } = this.form.value;
    this.lunch.setConfig(this.tenantId, { day, cutoff, groupSize: Number(groupSize) }).subscribe({
      next: config => {
        this.config = config;
        this.message = 'The settings are saved.';
        this.reload();
      },
      error: error => (this.error = error.error?.message || 'The settings could not be saved.')
    });
  }

  shortId(id: string): string {
    return id.substring(0, 8);
  }

  isMe(id: string): boolean {
    return id === this.auth.currentUser?.id;
  }

  private change(call: import('rxjs').Observable<LunchRound>, message: string) {
    this.error = null;
    this.message = null;
    call.subscribe({
      next: round => {
        this.round = round;
        this.message = message;
      },
      error: error => (this.error = error.error?.message || 'That could not be done.')
    });
  }
}
