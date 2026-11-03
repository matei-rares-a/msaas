import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { AuthService } from '../../core/services/auth.service';
import { LeaveRequest, LeaveService } from '../../core/services/leave.service';

/**
 * The requests waiting for a decision, for a MANAGER or an ADMIN with write access (REQ-LEAVE-05). The reason is shown
 * here and only here. Nobody is offered a decision on their own request; the server refuses one in any case.
 */
@Component({
  selector: 'app-leave-approvals',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink],
  templateUrl: './leave-approvals.component.html',
  styleUrls: ['./leave.component.css']
})
export class LeaveApprovalsComponent implements OnInit {
  requests: LeaveRequest[] = [];
  comments: Record<string, string> = {};
  loading = true;
  error: string | null = null;
  message: string | null = null;

  constructor(private auth: AuthService, private leave: LeaveService) {}

  private get tenantId(): string {
    return this.auth.currentUser?.tenantId ?? '';
  }

  ngOnInit() {
    this.load();
  }

  load() {
    this.leave.list(this.tenantId, { scope: 'ALL', status: 'PENDING' }).subscribe({
      next: page => {
        this.requests = page.items;
        this.loading = false;
      },
      error: () => {
        this.error = 'The requests could not be loaded.';
        this.loading = false;
      }
    });
  }

  isOwn(request: LeaveRequest): boolean {
    return request.userId === this.auth.currentUser?.id;
  }

  approve(request: LeaveRequest) {
    this.decide(this.leave.approve(this.tenantId, request.id, this.comments[request.id]?.trim()), request, 'approved');
  }

  reject(request: LeaveRequest) {
    this.decide(this.leave.reject(this.tenantId, request.id, this.comments[request.id]?.trim()), request, 'rejected');
  }

  shortId(id: string): string {
    return id.substring(0, 8);
  }

  private decide(call: import('rxjs').Observable<LeaveRequest>, request: LeaveRequest, outcome: string) {
    this.error = null;
    this.message = null;
    call.subscribe({
      next: () => {
        this.requests = this.requests.filter(item => item.id !== request.id);
        this.message = `The request was ${outcome}.`;
      },
      error: error => (this.error = error.error?.message || 'The decision could not be saved.')
    });
  }
}
