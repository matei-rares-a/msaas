import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { AccessService, Role } from '../../core/services/access.service';
import { AuthService } from '../../core/services/auth.service';
import { Invitation, InvitationService } from '../../core/services/invitation.service';

/**
 * Invitations (REQ-MGR-01): an ADMIN invites with any role, a MANAGER only with USER, and nobody with a read-only
 * membership invites at all. The roles offered here are only the ones the server will accept.
 */
@Component({
  selector: 'app-invitations',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule],
  templateUrl: './invitations.component.html',
  styleUrls: ['./invitations.component.css']
})
export class InvitationsComponent implements OnInit {
  permissions$ = this.access.permissions$;
  invitations: Invitation[] = [];
  form: FormGroup;
  loading = true;
  error: string | null = null;
  message: string | null = null;

  constructor(
    private access: AccessService,
    private auth: AuthService,
    private service: InvitationService,
    formBuilder: FormBuilder
  ) {
    this.form = formBuilder.group({ email: ['', [Validators.required, Validators.email]], role: ['USER', Validators.required] });
  }

  private get tenantId(): string {
    return this.auth.currentUser?.tenantId ?? '';
  }

  ngOnInit() {
    this.service.list(this.tenantId).subscribe({
      next: invitations => {
        this.invitations = invitations;
        this.loading = false;
      },
      error: () => {
        this.error = 'The invitations could not be loaded.';
        this.loading = false;
      }
    });
  }

  invite() {
    const role = this.form.value.role as Role;
    if (this.form.invalid || !this.access.permissions.invitableRoles.includes(role)) {
      this.form.markAllAsTouched();
      return;
    }
    this.error = null;
    this.message = null;
    this.service.invite(this.tenantId, this.form.value.email.trim(), role).subscribe({
      next: invitation => {
        this.invitations = [invitation, ...this.invitations];
        this.message = `Invitation sent to ${invitation.email}.`;
        this.form.reset({ email: '', role: 'USER' });
      },
      error: error => (this.error = error.error?.message || 'The invitation could not be sent.')
    });
  }

  revoke(invitation: Invitation) {
    this.service.revoke(this.tenantId, invitation.id).subscribe({
      next: () => (this.invitations = this.invitations.filter(item => item.id !== invitation.id)),
      error: error => (this.error = error.error?.message || 'The invitation could not be revoked.')
    });
  }
}
