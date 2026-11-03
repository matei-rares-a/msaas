import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ModulesComponent } from './modules.component';
import { signInAs } from '../../../testing/sessions';

const API = 'http://localhost:8080/organizations/t/modules';
const state = (leave: boolean, lunch: boolean, timezone = 'Europe/Bucharest') => ({ modules: { LEAVE: leave, LUNCH_ROULETTE: lunch }, timezone });

describe('REQ-MOD-01 the ADMIN switches the modules of the organization', () => {
  let fixture: ComponentFixture<ModulesComponent>;
  let http: HttpTestingController;
  const el = (id: string) => fixture.nativeElement.querySelector(`[data-testid="${id}"]`) as HTMLElement | null;

  beforeEach(async () => {
    signInAs('ADMIN');
    TestBed.configureTestingModule({ imports: [ModulesComponent], providers: [provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(ModulesComponent);
    fixture.detectChanges();
    http.expectOne(API).flush(state(true, false));
    fixture.detectChanges();
    await fixture.whenStable(); // ngModel writes the value after the first change detection
    fixture.detectChanges();
  });

  afterEach(() => {
    http.verify();
    localStorage.clear();
  });

  it('shows which modules are on and which are off', () => {
    expect(el('toggle-LEAVE')!.textContent).toContain('On');
    expect(el('toggle-LUNCH_ROULETTE')!.textContent).toContain('Off');
    expect((el('timezone') as HTMLInputElement).value).toBe('Europe/Bucharest');
  });

  it('turns a module on with the one call and says so', () => {
    el('toggle-LUNCH_ROULETTE')!.click();
    const request = http.expectOne(`${API}/LUNCH_ROULETTE`);
    expect(request.request.method).toBe('PUT');
    expect(request.request.body).toEqual({ enabled: true });
    request.flush(state(true, true));
    fixture.detectChanges();

    expect(el('toggle-LUNCH_ROULETTE')!.textContent).toContain('On');
    expect(el('modules-message')!.textContent).toContain('Lunch roulette is now on');
  });

  it('turns a module off', () => {
    el('toggle-LEAVE')!.click();
    const request = http.expectOne(`${API}/LEAVE`);
    expect(request.request.body).toEqual({ enabled: false });
    request.flush(state(false, false));
    fixture.detectChanges();

    expect(el('toggle-LEAVE')!.textContent).toContain('Off');
  });

  it('shows why a switch was refused and leaves the state as it was', () => {
    el('toggle-LUNCH_ROULETTE')!.click();
    http.expectOne(`${API}/LUNCH_ROULETTE`).flush({ message: 'only an ADMIN' }, { status: 403, statusText: 'Forbidden' });
    fixture.detectChanges();

    expect(el('modules-error')!.textContent).toContain('only an ADMIN');
    expect(el('toggle-LUNCH_ROULETTE')!.textContent).toContain('Off');
  });

  it('saves the time zone', () => {
    const input = el('timezone') as HTMLInputElement;
    input.value = 'Europe/Paris';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    el('save-timezone')!.click();
    const request = http.expectOne('http://localhost:8080/organizations/t/timezone');
    expect(request.request.body).toEqual({ timezone: 'Europe/Paris' });
    request.flush(state(true, false, 'Europe/Paris'));
    fixture.detectChanges();

    expect(el('modules-message')!.textContent).toContain('time zone');
  });
});
