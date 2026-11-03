import { Component, OnInit } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { CommonModule } from '@angular/common';
import { AuthService, TenantOption } from '../../../core/services/auth.service';

/** More than one tenant: the user picks, and only then does a token for that one tenant exist (REQ-AUTH-02, REQ-AUTH-04). */
@Component({
  selector: 'app-select-tenant',
  standalone: true,
  imports: [CommonModule, RouterLink],
  templateUrl: './select-tenant.component.html',
  styleUrls: ['../login/login.component.css']
})
export class SelectTenantComponent implements OnInit {
  tenants: TenantOption[] = [];
  loading = false;
  error: string | null = null;

  constructor(private authService: AuthService, private router: Router) {}

  ngOnInit() {
    this.tenants = this.authService.getPendingChoice()?.tenants ?? [];
  }

  choose(tenant: TenantOption) {
    this.loading = true;
    this.error = null;
    this.authService.selectTenant(tenant.tenantId).subscribe({
      next: () => this.router.navigateByUrl('/dashboard'),
      // the login id works once: after a failure the user starts again
      error: () => this.router.navigate(['/login'], { queryParams: { notice: 'choice-expired' } })
    });
  }

  /** The list has ids and roles only; a short id tells two tenants apart. */
  shortId(tenant: TenantOption): string {
    return tenant.tenantId.substring(0, 8);
  }
}
