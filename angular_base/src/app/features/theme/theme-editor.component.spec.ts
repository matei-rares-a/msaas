import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ThemeEditorComponent } from './theme-editor.component';
import { ThemeService } from '../../core/services/theme.service';
import { storeActiveSession } from '../../../testing/sessions';

const API = 'http://localhost:8080/organizations/tenant-a/theme';
const palettes = [
  { id: 'default', name: 'Classic blue', kind: 'COLOR', definition: { primary: '#1D4ED8', secondary: '#475569', accent: '#F59E0B' } },
  { id: 'forest', name: 'Forest', kind: 'COLOR', definition: { primary: '#15803D', secondary: '#3F3F46', accent: '#EAB308' } }
];

describe('REQ-PERS-01 the ADMIN picks a palette and uploads a logo', () => {
  let fixture: ComponentFixture<ThemeEditorComponent>;
  let http: HttpTestingController;

  beforeEach(() => {
    localStorage.clear();
    storeActiveSession('tenant-a');
    TestBed.configureTestingModule({ imports: [ThemeEditorComponent], providers: [provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(ThemeEditorComponent);
    fixture.detectChanges();
    http.expectOne(`${API}/palettes`).flush(palettes);
    fixture.detectChanges();
  });

  afterEach(() => {
    http.verify();
    TestBed.inject(ThemeService).clear();
    localStorage.clear();
  });

  const field = (name: string) => fixture.nativeElement.querySelector(`[data-testid="${name}"]`) as HTMLElement | null;

  it('offers every palette of the catalog', () => {
    expect(field('palette-default')?.textContent).toContain('Classic blue');
    expect(field('palette-forest')?.textContent).toContain('Forest');
  });

  it('saves the palette that is clicked and says so', () => {
    field('palette-forest')!.click();
    const request = http.expectOne(API);
    expect(request.request.body).toEqual({ paletteId: 'forest' });
    request.flush({ paletteId: 'forest', palette: palettes[1], logoUrl: null });
    fixture.detectChanges();

    expect(field('theme-message')?.textContent).toContain('Forest');
    expect(field('palette-forest')?.classList).toContain('current');
  });

  it('shows why a change was refused', () => {
    field('palette-forest')!.click();
    http.expectOne(API).flush({ message: 'only an ADMIN with write access can change the theme' }, { status: 403, statusText: 'Forbidden' });
    fixture.detectChanges();

    expect(field('theme-error')?.textContent).toContain('only an ADMIN');
  });

  function choose(file: File) {
    const input = field('logo-input') as HTMLInputElement;
    const transfer = new DataTransfer();
    transfer.items.add(file);
    input.files = transfer.files;
    input.dispatchEvent(new Event('change'));
    fixture.detectChanges();
  }

  it('does not upload a file that is not a PNG or a JPG', () => {
    choose(new File(['<svg/>'], 'logo.svg', { type: 'image/svg+xml' }));

    http.expectNone(`${API}/logo`);
    expect(field('theme-error')?.textContent).toContain('PNG or a JPG');
  });

  it('does not upload a file over 2 MB', () => {
    choose(new File([new Uint8Array(2 * 1024 * 1024 + 1)], 'big.png', { type: 'image/png' }));

    http.expectNone(`${API}/logo`);
    expect(field('theme-error')?.textContent).toContain('2 MB');
  });

  it('uploads a PNG as the part "file" and says so', () => {
    spyOn(URL, 'createObjectURL').and.returnValue('blob:new');
    choose(new File([new Uint8Array([1, 2, 3])], 'logo.png', { type: 'image/png' }));

    const upload = http.expectOne(`${API}/logo`);
    expect((upload.request.body as FormData).get('file')).toBeTruthy();
    upload.flush({ logoUrl: '/organizations/tenant-a/theme/logo?v=1' });
    http.expectOne('http://localhost:8080/organizations/tenant-a/theme/logo?v=1').flush(new Blob(['x']));
    fixture.detectChanges();

    expect(field('theme-message')?.textContent).toContain('logo is updated');
    expect(field('logo-preview')?.getAttribute('src')).toBe('blob:new');
  });
});
