import { Routes } from '@angular/router';
import { LoginComponent } from './features/auth/login/login.component';
import { RegisterComponent } from './features/auth/register/register.component';
import { SelectTenantComponent } from './features/auth/select-tenant/select-tenant.component';
import { NoOrganizationComponent } from './features/auth/no-organization/no-organization.component';
import { DashboardComponent } from './features/dashboard/dashboard.component';
import { BillingComponent } from './features/billing/billing.component';
import { InvitationsComponent } from './features/invitations/invitations.component';
import { JournalComponent } from './features/journal/journal.component';
import { NotificationsComponent } from './features/notifications/notifications.component';
import { ThemeEditorComponent } from './features/theme/theme-editor.component';
import { ModulesComponent } from './features/modules/modules.component';
import { LeaveComponent } from './features/leave/leave.component';
import { LeaveApprovalsComponent } from './features/leave/leave-approvals.component';
import { LeavePolicyComponent } from './features/leave/leave-policy.component';
import { LunchComponent } from './features/lunch/lunch.component';
import { AuthGuard } from './core/guards/auth.guard';
import { adminGuard } from './core/guards/role.guards';
import {
  billingGuard, invitationsGuard, journalGuard, leaveApprovalsGuard, leaveGuard, leavePolicyGuard, lunchGuard, modulesGuard
} from './core/guards/permission.guards';
import { identityGuard, pendingChoiceGuard } from './core/guards/onboarding.guards';

export const routes: Routes = [
  { path: '', redirectTo: 'dashboard', pathMatch: 'full' },
  { path: 'login', component: LoginComponent },
  { path: 'register', component: RegisterComponent },
  { path: 'select-tenant', component: SelectTenantComponent, canActivate: [pendingChoiceGuard] },
  { path: 'no-organization', component: NoOrganizationComponent, canActivate: [identityGuard] },
  { path: 'dashboard', component: DashboardComponent, canActivate: [AuthGuard] },
  { path: 'notifications', component: NotificationsComponent, canActivate: [AuthGuard] },
  { path: 'billing', component: BillingComponent, canActivate: [AuthGuard, billingGuard] },
  { path: 'invitations', component: InvitationsComponent, canActivate: [AuthGuard, invitationsGuard] },
  { path: 'journal', component: JournalComponent, canActivate: [AuthGuard, journalGuard] },
  { path: 'modules', component: ModulesComponent, canActivate: [AuthGuard, modulesGuard] },
  { path: 'leave', component: LeaveComponent, canActivate: [AuthGuard, leaveGuard] },
  { path: 'leave/approvals', component: LeaveApprovalsComponent, canActivate: [AuthGuard, leaveApprovalsGuard] },
  { path: 'leave/policy', component: LeavePolicyComponent, canActivate: [AuthGuard, leavePolicyGuard] },
  { path: 'lunch', component: LunchComponent, canActivate: [AuthGuard, lunchGuard] },
  { path: 'theme', component: ThemeEditorComponent, canActivate: [AuthGuard, adminGuard] },
  { path: '**', redirectTo: 'dashboard' }
];
