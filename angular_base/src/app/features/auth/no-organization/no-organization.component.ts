import { Component } from '@angular/core';
import { FormBuilder, FormGroup, Validators, ReactiveFormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { CommonModule } from '@angular/common';
import { Observable } from 'rxjs';
import { AuthService } from '../../../core/services/auth.service';
import { OnboardingService } from '../../../core/services/onboarding.service';

/**
 * A user with no organization (REQ-AUTH-03) can create one, becoming its ADMIN, or accept an invitation. Both calls
 * carry the identity token, which the interceptor attaches to these two routes only. Neither gives a token for the
 * tenant, so the user is sent to log in again.
 */
@Component({
  selector: 'app-no-organization',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule, RouterLink],
  templateUrl: './no-organization.component.html',
  styleUrls: ['../login/login.component.css']
})
export class NoOrganizationComponent {
  createForm: FormGroup;
  acceptForm: FormGroup;
  loading = false;
  error: string | null = null;

  constructor(
    formBuilder: FormBuilder,
    private authService: AuthService,
    private onboarding: OnboardingService,
    private router: Router
  ) {
    this.createForm = formBuilder.group({ name: ['', [Validators.required, Validators.maxLength(100)]] });
    this.acceptForm = formBuilder.group({ token: ['', [Validators.required]] });
  }

  createOrganization() {
    if (this.createForm.invalid) {
      this.createForm.markAllAsTouched();
      return;
    }
    this.run(this.onboarding.createOrganization(this.createForm.value.name.trim()), 'created');
  }

  acceptInvitation() {
    if (this.acceptForm.invalid) {
      this.acceptForm.markAllAsTouched();
      return;
    }
    this.run(this.onboarding.acceptInvitation(this.acceptForm.value.token.trim()), 'joined');
  }

  private run(call: Observable<unknown>, outcome: 'created' | 'joined') {
    this.loading = true;
    this.error = null;
    call.subscribe({
      next: () => {
        this.authService.endIdentitySession();
        this.router.navigate(['/login'], { queryParams: { notice: outcome } });
      },
      error: (error) => {
        this.error = error.error?.message || 'That did not work. Please check it and try again.';
        this.loading = false;
      }
    });
  }
}
