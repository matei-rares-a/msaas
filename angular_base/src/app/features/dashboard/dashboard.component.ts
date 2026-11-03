import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router, RouterLink } from '@angular/router';
import { AccessService } from '../../core/services/access.service';
import { AuthService } from '../../core/services/auth.service';

/** The home of a signed-in user: who they are in this organization and only what their role and access let them open. */
@Component({
  selector: 'app-dashboard',
  standalone: true,
  imports: [CommonModule, RouterLink],
  templateUrl: './dashboard.component.html',
  styleUrls: ['./dashboard.component.css']
})
export class DashboardComponent {
  user$ = this.auth.user$;
  permissions$ = this.access.permissions$;

  constructor(private auth: AuthService, private access: AccessService, private router: Router) {}

  logout() {
    this.auth.logout();
    this.router.navigate(['/login']);
  }
}
