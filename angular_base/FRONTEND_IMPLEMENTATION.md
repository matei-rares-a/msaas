# Angular Frontend Integration - Complete

## Overview

This is a complete Angular 19 standalone components frontend for the Multi-Tenant SaaS platform. It includes full auth flows, organization management, billing integration, and a professional dashboard UI.

## Architecture

### Core Services

#### AuthService (`core/services/auth.service.ts`)
- **Login/Register**: Authentication endpoints
- **Token Management**: Access & refresh token handling
- **Auto-refresh**: Automatic token rotation when expired
- **JWT Parsing**: Extracts user claims from JWT payload
- **State Management**: BehaviorSubjects for reactive UI updates

**Key Methods:**
```typescript
login(request: LoginRequest): Observable<AuthResponse>
register(request: RegisterRequest): Observable<AuthResponse>
refresh(): Observable<AuthResponse>
forgotPassword(email: string): Observable<any>
resetPassword(token: string, password: string): Observable<any>
logout(): void
```

#### OrganizationService (`core/services/organization.service.ts`)
- Create, list, and manage organizations
- Member management and role assignment
- User invitations with email
- Multi-tenant filtering (organization_id)

**Key Methods:**
```typescript
create(request: CreateOrganizationRequest): Observable<Organization>
list(): Observable<Organization[]>
getById(id: string): Observable<Organization>
getMembers(orgId: string): Observable<Membership[]>
invite(orgId: string, request: InviteRequest): Observable<Invitation>
updateMemberRole(orgId: string, memberId: string, request: UpdateRoleRequest): Observable<Membership>
removeMember(orgId: string, memberId: string): Observable<void>
```

#### BillingService (`core/services/billing.service.ts`)
- Plan management and retrieval
- Subscription lifecycle management (TRIALING, ACTIVE, PAST_DUE, CANCELED)
- Invoice viewing and payment tracking
- Subscription cancellation

**Key Methods:**
```typescript
getPlans(): Observable<Plan[]>
getPlanById(id: string): Observable<Plan>
subscribe(request: SubscribeRequest): Observable<Subscription>
getSubscription(): Observable<Subscription>
cancelSubscription(): Observable<void>
getInvoices(): Observable<Invoice[]>
```

### Cross-Cutting Concerns

#### AuthInterceptor (`core/interceptors/auth.interceptor.ts`)
- Automatically adds JWT Bearer token to all HTTP requests
- Handles 401 errors with silent token refresh
- Queue management for concurrent requests during refresh
- Skips token injection for auth endpoints

#### AuthGuard (`core/guards/auth.guard.ts`)
- Route protection for authenticated pages
- Redirects unauthenticated users to login
- Pre-fills return URL for post-login redirect
- Validates token expiration

### Components

#### LoginComponent (`features/auth/login/`)
- Email & password form with validation
- Error message display
- Loading state during authentication
- Redirect on successful login
- Link to registration page

**Features:**
- Real-time form validation
- Password reset link
- Graceful error handling

#### RegisterComponent (`features/auth/register/`)
- Email & password registration
- Password confirmation validation
- Password match validator
- Success redirect to dashboard

**Features:**
- 8-character minimum password
- Email validation
- Confirmation password field

#### DashboardComponent (`features/dashboard/`)
- Sidebar with organization switcher
- User profile display
- Navigation menu
- Organization list with quick actions
- Responsive layout

**Features:**
- Organization switching
- Member management navigation
- Billing navigation
- Organization creation
- Quick stats display

#### OrganizationComponent (`features/organization/`)
- Member management interface
- Invite members with role selection
- Role update for existing members
- Member removal with confirmation
- Invitation tracking

**Features:**
- Email invitation form
- Role-based access control (ADMIN/MANAGER/MEMBER)
- Pending invitations display
- Member list with actions
- Success/error notifications

#### BillingComponent (`features/billing/`)
- Current subscription display with status
- Available plans grid with pricing
- Plan features list
- Invoice table with history
- Subscription cancellation

**Features:**
- Plan comparison
- Subscription status indicators
- Invoice PDF links (placeholder)
- Subscription lifecycle visualization
- Automatic cache invalidation

## Routing

```typescript
/                    → /dashboard (redirect)
/login              → LoginComponent
/register           → RegisterComponent
/dashboard          → DashboardComponent (protected)
/organization/:id   → OrganizationComponent (protected)
/billing/:id        → BillingComponent (protected)
/**                 → /dashboard (wildcard)
```

## Global Styles

Comprehensive CSS styling system with:
- Color variables for consistency
- Responsive grid layouts
- Component-scoped styles
- Mobile-first design
- Dark/light mode ready

**Key Classes:**
- `.btn`, `.btn-primary`, `.btn-danger`, etc. - Button styles
- `.card`, `.card-header`, `.card-body` - Card layouts
- `.form-group`, `.form-control` - Form styling
- `.alert`, `.alert-error`, `.alert-success` - Alert styles
- `.dashboard-container`, `.sidebar`, `.main-content` - Layout structure

## API Integration

### Gateway URL (Development)
Base: `http://localhost:8080`

### Service Endpoints (auto-routed by gateway)
- Auth: `/auth/**` → Auth Service (8081)
- Organization: `/organizations/**` → Org Service (8082)
- Billing: `/billing/**` → Billing Service (8083)
- Notification: `/notifications/**` → Notification Service (8084)

### Authentication Flow

```
1. User submits email + password
2. AuthService calls POST /auth/login
3. Gateway validates JWT and returns tokens
4. AuthService stores accessToken & refreshToken in localStorage
5. AuthService decodes JWT and emits User$ subject
6. AuthInterceptor adds Bearer token to subsequent requests
7. AuthGuard checks token validity for route access
8. On 401 error, AuthInterceptor automatically refreshes token
```

## State Management

Using RxJS Subjects for reactive state:

```typescript
// AuthService
user$ = BehaviorSubject<User | null>
isAuthenticated$ = BehaviorSubject<boolean>

// Component Usage
constructor(private authService: AuthService) {}

ngOnInit() {
  this.authService.user$.subscribe(user => {
    this.user = user;
  });
}
```

## Error Handling

Global error handling with user-friendly messages:

```typescript
try {
  this.authService.login(credentials).subscribe(
    (response) => {
      // Success
    },
    (error) => {
      this.error = error.error?.message || 'Login failed';
    }
  );
}
```

## Form Validation

Reactive forms with built-in validators:

```typescript
form = this.fb.group({
  email: ['', [Validators.required, Validators.email]],
  password: ['', [Validators.required, Validators.minLength(8)]],
  confirmPassword: ['', Validators.required]
}, { validators: this.passwordMatchValidator });
```

## Environment Configuration

For different environments, update API base URL:

**Development** (`src/environments/environment.ts`):
```typescript
export const environment = {
  apiUrl: 'http://localhost:8080'
};
```

**Production** (`src/environments/environment.prod.ts`):
```typescript
export const environment = {
  apiUrl: 'https://api.example.com'
};
```

Then inject in services:
```typescript
constructor(private http: HttpClient, private env: environment) {
  this.apiUrl = this.env.apiUrl;
}
```

## Setup & Installation

### Prerequisites
- Node.js 18+ 
- npm or yarn
- Angular CLI 19

### Steps

```bash
# Navigate to angular project
cd angular_base

# Install dependencies
npm install

# Start development server
npm run dev

# Build for production
npm build

# Run tests
npm test
```

### Development Server
- Runs on `http://localhost:4200`
- Hot reload enabled
- Opens automatically with `npm run dev`

## Features Implemented

✅ **Authentication**
- User registration with email validation
- Login with credentials
- JWT token management
- Auto-refresh on expiration
- Password reset flow
- Logout functionality

✅ **Organization Management**
- Create organizations
- Switch between orgs
- Invite members
- Manage roles (ADMIN/MANAGER/MEMBER)
- Remove members
- View members list

✅ **Billing**
- View available plans
- Subscribe to plans
- Cancel subscriptions
- View invoice history
- Track subscription status

✅ **UI/UX**
- Responsive dashboard layout
- Organization switcher sidebar
- Professional styling
- Loading states
- Error notifications
- Success confirmations

✅ **Security**
- JWT authentication
- Token refresh mechanism
- Protected routes with AuthGuard
- Automatic token injection
- 401 error handling

## Testing

Run unit tests:
```bash
npm test
```

Run E2E tests:
```bash
npm run e2e
```

## Performance Optimizations

1. **Lazy Loading**: Load feature modules on demand
2. **Change Detection**: OnPush strategy in standalone components
3. **RxJS**: Unsubscribe with `takeUntil` during component destruction
4. **HTTP Caching**: BillingService uses caching decorators
5. **Bundle Size**: Tree-shaking with standalone components

## Common Issues & Solutions

### Issue: Token expiration during user session
**Solution**: AuthInterceptor automatically handles 401 errors and refreshes token

### Issue: CORS errors
**Solution**: Gateway handles CORS. Ensure backends allow gateway origin.

### Issue: Blank pages after login
**Solution**: Check browser console for errors. Verify AuthGuard is properly configured.

### Issue: npm install fails
**Solution**: Clear npm cache and lock file:
```bash
rm package-lock.json
npm cache clean --force
npm install
```

## Future Enhancements

- [ ] Role-based component visibility (ADMIN/MANAGER/MEMBER)
- [ ] Settings page for user preferences
- [ ] Profile editing
- [ ] Invoice PDF export
- [ ] Dark mode toggle
- [ ] Multi-language support (i18n)
- [ ] Unit tests for services
- [ ] E2E tests with Cypress
- [ ] State management with NgRx
- [ ] WebSocket for real-time updates
- [ ] File upload support
- [ ] Advanced search & filtering

## File Structure

```
angular_base/
├── src/
│   ├── app/
│   │   ├── core/
│   │   │   ├── guards/
│   │   │   │   └── auth.guard.ts
│   │   │   ├── interceptors/
│   │   │   │   └── auth.interceptor.ts
│   │   │   └── services/
│   │   │       ├── auth.service.ts
│   │   │       ├── organization.service.ts
│   │   │       └── billing.service.ts
│   │   ├── features/
│   │   │   ├── auth/
│   │   │   │   ├── login/
│   │   │   │   └── register/
│   │   │   ├── dashboard/
│   │   │   ├── organization/
│   │   │   └── billing/
│   │   ├── app.config.ts
│   │   ├── app.routes.ts
│   │   └── app.component.ts
│   ├── styles.css
│   └── main.ts
├── angular.json
├── tsconfig.json
└── package.json
```

## Support

For issues or questions:
1. Check the backend README for API documentation
2. Review network tab in browser dev tools
3. Check console for error messages
4. Verify backend services are running (docker-compose up -d)

## License

MIT - Part of Multi-Tenant SaaS Platform
