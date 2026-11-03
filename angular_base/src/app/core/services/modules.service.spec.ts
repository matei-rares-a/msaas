import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Router, UrlTree, ActivatedRouteSnapshot, RouterStateSnapshot } from '@angular/router';
import { Observable, isObservable, firstValueFrom } from 'rxjs';
import { LEAVE_MODULE, LUNCH_MODULE, ModulesService } from './modules.service';
import { leaveApprovalsGuard, leaveGuard, leavePolicyGuard, lunchGuard, modulesGuard } from '../guards/permission.guards';
import { fakeJwt } from '../../../testing/sessions';

const API = 'http://localhost:8080/organizations/t/modules';

describe('REQ-MOD-01 a module that is off does not exist for the members', () => {
  let http: HttpTestingController;
  let modules: ModulesService;

  function setUp(role: string | null, readOnly = false) {
    localStorage.clear();
    TestBed.resetTestingModule();
    if (role) {
      const token = fakeJwt({ sub: 'u', tenantId: 't', tenantName: 'T', displayName: 'd', role, readOnly });
      localStorage.setItem('msaas.sessions', JSON.stringify({ t: { accessToken: token, refreshToken: 'r', expiresAt: Date.now() + 60_000 } }));
      localStorage.setItem('msaas.activeTenant', 't');
    }
    const router = jasmine.createSpyObj<Router>('Router', ['createUrlTree']);
    router.createUrlTree.and.returnValue({ redirect: '/dashboard' } as unknown as UrlTree);
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting(), { provide: Router, useValue: router }] });
    http = TestBed.inject(HttpTestingController);
    modules = TestBed.inject(ModulesService);
  }

  afterEach(() => {
    http.verify();
    localStorage.clear();
  });

  const run = (guard: typeof leaveGuard) =>
    TestBed.runInInjectionContext(() => guard({} as ActivatedRouteSnapshot, {} as RouterStateSnapshot)) as boolean | UrlTree | Observable<boolean | UrlTree>;
  const resolved = async (guard: typeof leaveGuard, answer?: object | number) => {
    const result = run(guard);
    if (!isObservable(result)) {
      return result;
    }
    const pending = firstValueFrom(result);
    if (answer !== undefined) {
      const request = http.expectOne(API);
      typeof answer === 'number' ? request.flush({}, { status: answer, statusText: 'Error' }) : request.flush(answer);
    }
    return pending;
  };
  const state = (leave: boolean, lunch: boolean) => ({ modules: { LEAVE: leave, LUNCH_ROULETTE: lunch }, timezone: 'Europe/Bucharest' });
  const redirect = { redirect: '/dashboard' } as unknown as UrlTree;

  it('reads the settings of the tenant and says which module is on', () => {
    setUp('USER');

    modules.load('t').subscribe();
    http.expectOne(API).flush(state(true, false));

    expect(modules.isEnabled(LEAVE_MODULE)).toBeTrue();
    expect(modules.isEnabled(LUNCH_MODULE)).toBeFalse();
  });

  it('has every module off when the settings cannot be read, and forgets them when cleared', () => {
    setUp('USER');
    modules.load('t').subscribe();
    http.expectOne(API).flush({}, { status: 500, statusText: 'Server Error' });
    expect(modules.isEnabled(LEAVE_MODULE)).toBeFalse();

    modules.load('t').subscribe();
    http.expectOne(API).flush(state(true, true));
    modules.clear();
    expect(modules.state).toBeNull();
  });

  it('opens the page of a module that is on, and turns away from one that is off', async () => {
    setUp('USER');
    expect(await resolved(leaveGuard, state(true, false))).toBeTrue();
    expect(modules.state).not.toBeNull();
    expect(await resolved(lunchGuard)).toEqual(redirect);
  });

  it('turns away when the settings cannot be read, instead of opening on a guess', async () => {
    setUp('USER');

    expect(await resolved(leaveGuard, 500)).toEqual(redirect);
  });

  it('does not read the settings again when the app already has them', async () => {
    setUp('MANAGER');
    modules.load('t').subscribe();
    http.expectOne(API).flush(state(true, true));

    expect(await resolved(leaveGuard)).toBeTrue();
    expect(await resolved(lunchGuard)).toBeTrue();
  });

  it('turns away whoever is not signed in without asking anything', async () => {
    setUp(null);

    expect(await resolved(leaveGuard)).toEqual(redirect);
  });

  it('keeps the approvals for MANAGER and ADMIN with write access, the policy for the ADMIN, whatever the module', async () => {
    setUp('USER');
    modules.load('t').subscribe();
    http.expectOne(API).flush(state(true, true));
    expect(await resolved(leaveApprovalsGuard)).toEqual(redirect);
    expect(await resolved(leavePolicyGuard)).toEqual(redirect);

    setUp('MANAGER');
    modules.load('t').subscribe();
    http.expectOne(API).flush(state(true, true));
    expect(await resolved(leaveApprovalsGuard)).toBeTrue();
    expect(await resolved(leavePolicyGuard)).toEqual(redirect);

    setUp('ADMIN');
    modules.load('t').subscribe();
    http.expectOne(API).flush(state(false, false));
    expect(await resolved(leaveApprovalsGuard)).toEqual(redirect); // off: not even for the ADMIN
    setUp('ADMIN');
    modules.load('t').subscribe();
    http.expectOne(API).flush(state(true, true));
    expect(await resolved(leavePolicyGuard)).toBeTrue();
  });

  it('opens the module settings only for an ADMIN who can write', () => {
    setUp('ADMIN');
    expect(run(modulesGuard)).toBeTrue();
    setUp('ADMIN', true);
    expect(run(modulesGuard)).toEqual(redirect);
    setUp('MANAGER');
    expect(run(modulesGuard)).toEqual(redirect);
  });

  it('switches a module and keeps what the server answers', () => {
    setUp('ADMIN');
    let result: any;

    modules.setEnabled('t', LUNCH_MODULE, true).subscribe(s => (result = s));
    const request = http.expectOne(`${API}/${LUNCH_MODULE}`);
    expect(request.request.method).toBe('PUT');
    expect(request.request.body).toEqual({ enabled: true });
    request.flush(state(false, true));

    expect(result.modules.LUNCH_ROULETTE).toBeTrue();
    expect(modules.isEnabled(LUNCH_MODULE)).toBeTrue();
  });

  it('sets the time zone', () => {
    setUp('ADMIN');

    modules.setTimezone('t', 'Europe/Paris').subscribe();
    const request = http.expectOne('http://localhost:8080/organizations/t/timezone');
    expect(request.request.body).toEqual({ timezone: 'Europe/Paris' });
    request.flush({ modules: {}, timezone: 'Europe/Paris' });

    expect(modules.state?.timezone).toBe('Europe/Paris');
  });
});
