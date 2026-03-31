import { Component, OnInit, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ReactiveFormsModule, FormBuilder, FormGroup, Validators } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { Subject } from 'rxjs';
import { takeUntil } from 'rxjs/operators';
import { OrganizationService, Membership, Organization, Invitation } from '../../core/services/organization.service';

@Component({
  selector: 'app-organization',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule],
  templateUrl: './organization.component.html',
  styleUrls: ['./organization.component.css']
})
export class OrganizationComponent implements OnInit, OnDestroy {
  organization: Organization | null = null;
  members: Membership[] = [];
  invitations: Invitation[] = [];
  inviteForm!: FormGroup;
  loading = true;
  error: string | null = null;
  success: string | null = null;
  showInviteForm = false;
  invitationLoading = false;

  private destroy$ = new Subject<void>();

  constructor(
    private organizationService: OrganizationService,
    private route: ActivatedRoute,
    private router: Router,
    private formBuilder: FormBuilder
  ) {}

  ngOnInit() {
    this.initializeForm();
    const orgId = this.route.snapshot.paramMap.get('id');
    if (orgId) {
      this.loadOrganization(orgId);
      this.loadMembers(orgId);
    }
  }

  ngOnDestroy() {
    this.destroy$.next();
    this.destroy$.complete();
  }

  initializeForm() {
    this.inviteForm = this.formBuilder.group({
      email: ['', [Validators.required, Validators.email]],
      role: ['MEMBER', Validators.required]
    });
  }

  loadOrganization(orgId: string) {
    this.organizationService.getById(orgId)
      .pipe(takeUntil(this.destroy$))
      .subscribe(
        (org) => {
          this.organization = org;
        },
        (error) => {
          this.error = 'Failed to load organization';
        }
      );
  }

  loadMembers(orgId: string) {
    this.loading = true;
    this.organizationService.getMembers(orgId)
      .pipe(takeUntil(this.destroy$))
      .subscribe(
        (members) => {
          this.members = members;
          this.loading = false;
        },
        (error) => {
          this.error = 'Failed to load members';
          this.loading = false;
        }
      );
  }

  inviteMember() {
    if (this.inviteForm.invalid || !this.organization) {
      return;
    }

    this.invitationLoading = true;
    this.organizationService.invite(this.organization.id, this.inviteForm.value)
      .pipe(takeUntil(this.destroy$))
      .subscribe(
        (invitation) => {
          this.invitations.push(invitation);
          this.success = `Invitation sent to ${invitation.email}`;
          this.inviteForm.reset();
          this.showInviteForm = false;
          this.invitationLoading = false;
          setTimeout(() => this.success = null, 5000);
        },
        (error) => {
          this.error = error.error?.message || 'Failed to send invitation';
          this.invitationLoading = false;
        }
      );
  }

  updateMemberRole(memberId: string, newRole: string) {
    if (!this.organization) return;

    this.organizationService.updateMemberRole(this.organization.id, memberId, { role: newRole as any })
      .pipe(takeUntil(this.destroy$))
      .subscribe(
        (updated) => {
          const index = this.members.findIndex(m => m.id === memberId);
          if (index > -1) {
            this.members[index] = updated;
          }
          this.success = 'Member role updated';
          setTimeout(() => this.success = null, 5000);
        },
        (error) => {
          this.error = 'Failed to update member role';
        }
      );
  }

  removeMember(memberId: string) {
    if (!this.organization || !confirm('Are you sure you want to remove this member?')) {
      return;
    }

    this.organizationService.removeMember(this.organization.id, memberId)
      .pipe(takeUntil(this.destroy$))
      .subscribe(
        () => {
          this.members = this.members.filter(m => m.id !== memberId);
          this.success = 'Member removed';
          setTimeout(() => this.success = null, 5000);
        },
        (error) => {
          this.error = 'Failed to remove member';
        }
      );
  }

  get f() {
    return this.inviteForm.controls;
  }

  navigateBack() {
    this.router.navigate(['/dashboard']);
  }
}
