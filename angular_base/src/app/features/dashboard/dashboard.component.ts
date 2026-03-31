import { Component, OnInit, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router, RouterLink } from '@angular/router';
import { Observable, Subject } from 'rxjs';
import { takeUntil } from 'rxjs/operators';
import { AuthService, User } from '../../core/services/auth.service';
import { OrganizationService, Organization } from '../../core/services/organization.service';

@Component({
  selector: 'app-dashboard',
  standalone: true,
  imports: [CommonModule, RouterLink],
  templateUrl: './dashboard.component.html',
  styleUrls: ['./dashboard.component.css']
})
export class DashboardComponent implements OnInit, OnDestroy {
  user: User | null = null;
  organizations: Organization[] = [];
  selectedOrg: Organization | null = null;
  loading = true;
  error: string | null = null;
  showOrgDropdown = false;

  private destroy$ = new Subject<void>();

  constructor(
    private authService: AuthService,
    private organizationService: OrganizationService,
    private router: Router
  ) {}

  ngOnInit() {
    this.authService.user$
      .pipe(takeUntil(this.destroy$))
      .subscribe(user => {
        this.user = user;
        if (user) {
          this.loadOrganizations();
        }
      });
  }

  ngOnDestroy() {
    this.destroy$.next();
    this.destroy$.complete();
  }

  loadOrganizations() {
    this.loading = true;
    this.organizationService.list().subscribe(
      (orgs: Organization[]) => {
        this.organizations = orgs;
        if (orgs.length > 0) {
          this.selectedOrg = orgs[0];
        }
        this.loading = false;
      },
      (error) => {
        this.error = 'Failed to load organizations';
        this.loading = false;
      }
    );
  }

  switchOrganization(org: Organization) {
    this.selectedOrg = org;
    this.showOrgDropdown = false;
  }

  createNewOrganization() {
    this.router.navigate(['/organization/create']);
  }

  logout() {
    this.authService.logout();
    this.router.navigate(['/login']);
  }

  navigateToOrganization() {
    if (this.selectedOrg) {
      this.router.navigate(['/organization', this.selectedOrg.id]);
    }
  }

  navigateToBilling() {
    if (this.selectedOrg) {
      this.router.navigate(['/billing', this.selectedOrg.id]);
    }
  }

  navigateToMembers() {
    if (this.selectedOrg) {
      this.router.navigate(['/organization', this.selectedOrg.id, 'members']);
    }
  }
}
