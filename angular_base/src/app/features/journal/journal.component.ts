import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormBuilder, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { ActivityLogFilter, ActivityLogPage, JournalError, JournalService } from '../../core/services/journal.service';

/** The activity journal of the tenant for an ADMIN or a MANAGER (REQ-MGR-04): filters, paging, newest first. */
@Component({
  selector: 'app-journal',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule],
  templateUrl: './journal.component.html',
  styleUrls: ['./journal.component.css']
})
export class JournalComponent implements OnInit {
  form: FormGroup;
  result: ActivityLogPage | null = null;
  loading = false;
  error: string | null = null;

  constructor(private journal: JournalService, formBuilder: FormBuilder) {
    this.form = formBuilder.group({ from: [''], to: [''], eventTypes: [''], actorUserId: [''] });
  }

  ngOnInit() {
    this.load(0);
  }

  search() {
    this.load(0);
  }

  load(page: number) {
    this.loading = true;
    this.error = null;
    this.journal.read(this.filter(), page).subscribe({
      next: result => {
        this.result = result;
        this.loading = false;
      },
      error: error => {
        this.error = error instanceof JournalError && error.forbidden
          ? 'Only administrators and managers can read the journal.'
          : error.message && error instanceof JournalError ? error.message : 'The journal could not be read.';
        this.loading = false;
      }
    });
  }

  previous() {
    if (this.result && this.result.page > 0) {
      this.load(this.result.page - 1);
    }
  }

  next() {
    if (this.result && this.result.page + 1 < this.result.totalPages) {
      this.load(this.result.page + 1);
    }
  }

  private filter(): ActivityLogFilter {
    const value = this.form.value;
    return {
      from: value.from ? new Date(value.from).toISOString() : undefined,
      to: value.to ? new Date(value.to).toISOString() : undefined,
      eventTypes: (value.eventTypes as string).split(',').map(type => type.trim()).filter(type => type.length > 0),
      actorUserId: (value.actorUserId as string).trim() || undefined
    };
  }
}
