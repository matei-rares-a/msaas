import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { AuthService } from '../../core/services/auth.service';
import { LEAVE_MODULE, LUNCH_MODULE, ModulesService, ModulesState } from '../../core/services/modules.service';

interface ModuleRow {
  name: string;
  label: string;
  description: string;
}

/**
 * Switching the business modules of the organization and its time zone (REQ-MOD-01), for an ADMIN with write access. A module
 * is off until the ADMIN turns it on, and turning it off keeps its data.
 */
@Component({
  selector: 'app-modules',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './modules.component.html',
  styleUrls: ['./modules.component.css']
})
export class ModulesComponent implements OnInit {
  rows: ModuleRow[] = [
    { name: LEAVE_MODULE, label: 'Leave requests', description: 'Members ask for leave, managers decide, the team calendar shows who is away.' },
    { name: LUNCH_MODULE, label: 'Lunch roulette', description: 'A weekly draw puts the members who joined into small lunch groups.' }
  ];
  state: ModulesState | null = null;
  timezone = '';
  error: string | null = null;
  message: string | null = null;
  busy = false;

  constructor(private auth: AuthService, private modules: ModulesService) {}

  private get tenantId(): string {
    return this.auth.currentUser?.tenantId ?? '';
  }

  ngOnInit() {
    this.modules.load(this.tenantId).subscribe(state => {
      this.state = state;
      this.timezone = state?.timezone ?? '';
      if (!state) {
        this.error = 'The module settings could not be loaded.';
      }
    });
  }

  enabled(name: string): boolean {
    return this.state?.modules[name] === true;
  }

  toggle(row: ModuleRow) {
    const enabled = !this.enabled(row.name);
    this.run(this.modules.setEnabled(this.tenantId, row.name, enabled), `${row.label} is now ${enabled ? 'on' : 'off'}.`);
  }

  saveTimezone() {
    this.run(this.modules.setTimezone(this.tenantId, this.timezone.trim()), 'The time zone is saved.');
  }

  private run(call: import('rxjs').Observable<ModulesState>, message: string) {
    this.busy = true;
    this.error = null;
    this.message = null;
    call.subscribe({
      next: state => {
        this.state = state;
        this.timezone = state.timezone;
        this.message = message;
        this.busy = false;
      },
      error: error => {
        this.error = error.error?.message || 'That could not be saved.';
        this.busy = false;
      }
    });
  }
}
