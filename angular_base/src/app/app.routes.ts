import { Routes } from '@angular/router';
import { LoginComponent } from './features/auth/login/login.component';
import { RegisterComponent } from './features/auth/register/register.component';
import { DashboardComponent } from './features/dashboard/dashboard.component';
import { OrganizationComponent } from './features/organization/organization.component';
import { BillingComponent } from './features/billing/billing.component';
import { AuthGuard } from './core/guards/auth.guard';
import { AdminComponent } from './pages/admin/admin.component';

export const routes: Routes = [
  { path: '', redirectTo: 'dashboard', pathMatch: 'full' },
  { path: 'login', component: LoginComponent },
  { path: 'register', component: RegisterComponent },
  { 
    path: 'dashboard', 
    component: DashboardComponent,
    canActivate: [AuthGuard]
  },
  { 
    path: 'organization/:id', 
    component: OrganizationComponent,
    canActivate: [AuthGuard]
  },
  {
    path: 'organization/:id/members',
    component: OrganizationComponent,
    canActivate: [AuthGuard]
  },
  { 
    path: 'billing/:id', 
    component: BillingComponent,
    canActivate: [AuthGuard]
  },
  {
    path: 'admin',
    component: AdminComponent,
    canActivate: [AuthGuard]
  },
  { path: '**', redirectTo: 'dashboard' }
];
