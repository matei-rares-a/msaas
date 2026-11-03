import { Component, OnInit } from '@angular/core';
import { FormBuilder, FormGroup, Validators, ReactiveFormsModule } from '@angular/forms';
import { Router, ActivatedRoute, RouterLink } from '@angular/router';
import { CommonModule } from '@angular/common';
import { AuthService } from '../../../core/services/auth.service';

@Component({
  selector: 'app-login',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule, RouterLink],
  templateUrl: './login.component.html',
  styleUrls: ['./login.component.css']
})
export class LoginComponent implements OnInit {
  form!: FormGroup;
  loading = false;
  submitted = false;
  error: string | null = null;
  notice: string | null = null;
  returnUrl: string = '';

  constructor(
    private formBuilder: FormBuilder,
    private authService: AuthService,
    private router: Router,
    private route: ActivatedRoute
  ) {}

  ngOnInit() {
    this.form = this.formBuilder.group({
      email: ['', [Validators.required, Validators.email]],
      password: ['', [Validators.required, Validators.minLength(6)]]
    });

    this.returnUrl = this.route.snapshot.queryParams['returnUrl'] || '/dashboard';
    this.notice = LoginComponent.NOTICES[this.route.snapshot.queryParams['notice']] ?? null;
  }

  private static readonly NOTICES: Record<string, string> = {
    registered: 'Your account is created. Log in to continue.',
    created: 'Your organization is created. Log in again to open it.',
    joined: 'You joined the organization. Log in again to open it.',
    'choice-expired': 'That choice expired. Please log in again.'
  };

  get f() {
    return this.form.controls;
  }

  onSubmit() {
    this.submitted = true;
    this.error = null;

    if (this.form.invalid) {
      return;
    }

    this.loading = true;
    this.authService.login(this.form.value).subscribe({
      next: (result) => {
        if (result.kind === 'signedIn') {
          this.router.navigateByUrl(this.returnUrl);
        } else if (result.kind === 'chooseTenant') {
          this.router.navigate(['/select-tenant']);
        } else {
          this.router.navigate(['/no-organization']);
        }
      },
      error: (error) => {
        this.error = error.error?.message || 'Login failed. Please try again.';
        this.loading = false;
      }
    });
  }
}
