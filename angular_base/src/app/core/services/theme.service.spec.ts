import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Palette, Theme, ThemeService } from './theme.service';

const API = 'http://localhost:8080/organizations/tenant-a/theme';

const forest: Palette = {
  id: 'forest', name: 'Forest', kind: 'COLOR', definition: { primary: '#15803D', secondary: '#3F3F46', accent: '#EAB308' }
};
const ocean: Palette = {
  id: 'ocean', name: 'Ocean', kind: 'GRADIENT', definition: { primary: '#0369A1', secondary: '#134E4A', accent: '#F472B6', gradient: ['#0EA5E9', '#14B8A6'] }
};

function themeOf(palette: Palette | null, logoUrl: string | null = null): Theme {
  return { paletteId: palette?.id ?? 'default', palette, logoUrl };
}

const variable = (name: string) => document.documentElement.style.getPropertyValue(name);

describe('REQ-PERS-06 the theme is read for the tenant and applied', () => {
  let service: ThemeService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    service = TestBed.inject(ThemeService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    http.verify();
    service.clear();
  });

  it('reads it from the tenant\'s own theme endpoint and applies its colours as CSS variables', () => {
    service.loadFor('tenant-a').subscribe();
    http.expectOne(API).flush(themeOf(forest));

    expect(variable('--primary-color')).toBe('#15803D');
    expect(variable('--secondary-color')).toBe('#3F3F46');
    expect(variable('--accent-color')).toBe('#EAB308');
  });

  it('builds a gradient from a gradient palette, out of its two validated colours', () => {
    service.loadFor('tenant-a').subscribe();
    http.expectOne(API).flush(themeOf(ocean));

    expect(variable('--brand-gradient')).toBe('linear-gradient(135deg, #0EA5E9, #14B8A6)');
  });

  it('applies nothing that is not a #RRGGBB colour, so no CSS can come from a tenant', () => {
    const hostile: Palette = {
      id: 'x', name: 'x', kind: 'COLOR',
      definition: { primary: 'red;background:url(https://evil.example/x)', secondary: 'rgb(0,0,0)', accent: '#12345', gradient: ['#FFFFFF', 'url(x)'] }
    };

    service.loadFor('tenant-a').subscribe();
    http.expectOne(API).flush(themeOf(hostile));

    for (const name of ['--primary-color', '--secondary-color', '--accent-color', '--brand-gradient']) {
      expect(variable(name)).withContext(name).toBe('');
    }
  });

  it('replaces the colours of the last theme and clears them with nobody signed in', () => {
    service.loadFor('tenant-a').subscribe();
    http.expectOne(API).flush(themeOf(ocean));
    service.loadFor('tenant-a').subscribe();
    http.expectOne(API).flush(themeOf(forest));

    expect(variable('--brand-gradient')).toBe('');
    expect(variable('--primary-color')).toBe('#15803D');

    service.clear();
    expect(variable('--primary-color')).toBe('');
  });

  it('keeps the default look when the theme cannot be read', () => {
    let error: any;
    service.loadFor('tenant-a').subscribe({ error: e => (error = e) });
    http.expectOne(API).flush({}, { status: 500, statusText: 'Server Error' });

    expect(error).toBeTruthy();
    expect(variable('--primary-color')).toBe('');
  });
});

describe('REQ-PERS-06 the logo is fetched with the user\'s token, as a blob, from the tenant\'s own path only', () => {
  let service: ThemeService;
  let http: HttpTestingController;
  let created: string[];
  let revoked: string[];

  beforeEach(() => {
    created = [];
    revoked = [];
    spyOn(URL, 'createObjectURL').and.callFake(() => {
      const url = `blob:logo-${created.length + 1}`;
      created.push(url);
      return url;
    });
    spyOn(URL, 'revokeObjectURL').and.callFake((url: string) => void revoked.push(url));
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    service = TestBed.inject(ThemeService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    http.verify();
    service.clear();
  });

  it('asks for the logo as a blob and exposes a blob: URL, never the API URL', () => {
    let logo = null as string | null;
    service.logo$.subscribe(value => (logo = value));

    service.loadFor('tenant-a').subscribe();
    http.expectOne(API).flush(themeOf(forest, '/organizations/tenant-a/theme/logo?v=abc'));
    const request = http.expectOne('http://localhost:8080/organizations/tenant-a/theme/logo?v=abc');
    expect(request.request.responseType).toBe('blob');
    request.flush(new Blob(['png'], { type: 'image/png' }));

    expect(logo).toBe('blob:logo-1');
  });

  it('never sends the token to a path that is not this tenant\'s logo, whatever the server says', () => {
    for (const logoUrl of ['https://evil.example/logo.png', '/organizations/tenant-b/theme/logo?v=1', '//evil.example/x', '/auth/account']) {
      service.loadFor('tenant-a').subscribe();
      http.expectOne(API).flush(themeOf(forest, logoUrl));
      http.expectNone(r => r.url.includes('evil.example') || r.url.includes('tenant-b') || r.url.includes('/auth/account'));
    }
  });

  it('has no logo when the tenant has none, and when the logo cannot be read', () => {
    let logo = 'initial' as string | null;
    service.logo$.subscribe(value => (logo = value));

    service.loadFor('tenant-a').subscribe();
    http.expectOne(API).flush(themeOf(forest, null));
    expect(logo).toBeNull();

    service.loadFor('tenant-a').subscribe();
    http.expectOne(API).flush(themeOf(forest, '/organizations/tenant-a/theme/logo?v=1'));
    http.expectOne('http://localhost:8080/organizations/tenant-a/theme/logo?v=1').flush(new Blob(['x']), { status: 404, statusText: 'Not Found' });
    expect(logo).toBeNull();
  });

  it('frees the previous logo when a new one comes and when the theme is cleared', () => {
    service.loadFor('tenant-a').subscribe();
    http.expectOne(API).flush(themeOf(forest, '/organizations/tenant-a/theme/logo?v=1'));
    http.expectOne('http://localhost:8080/organizations/tenant-a/theme/logo?v=1').flush(new Blob(['1']));
    service.loadFor('tenant-a').subscribe();
    http.expectOne(API).flush(themeOf(forest, '/organizations/tenant-a/theme/logo?v=2'));
    http.expectOne('http://localhost:8080/organizations/tenant-a/theme/logo?v=2').flush(new Blob(['2']));

    expect(revoked).toEqual(['blob:logo-1']);
    service.clear();
    expect(revoked).toEqual(['blob:logo-1', 'blob:logo-2']);
  });
});

describe('REQ-PERS-01 changing the theme is a call the ADMIN makes, and its result applies at once', () => {
  let service: ThemeService;
  let http: HttpTestingController;

  beforeEach(() => {
    spyOn(URL, 'createObjectURL').and.returnValue('blob:new-logo');
    spyOn(URL, 'revokeObjectURL');
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    service = TestBed.inject(ThemeService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    http.verify();
    service.clear();
  });

  it('lists the palettes of the catalog', () => {
    let palettes: Palette[] = [];
    service.listPalettes('tenant-a').subscribe(p => (palettes = p));
    http.expectOne(`${API}/palettes`).flush([forest, ocean]);

    expect(palettes.map(p => p.id)).toEqual(['forest', 'ocean']);
  });

  it('saves only the palette id and applies the answer', () => {
    service.updatePalette('tenant-a', 'ocean').subscribe();
    const request = http.expectOne(API);
    expect(request.request.method).toBe('PUT');
    expect(request.request.body).toEqual({ paletteId: 'ocean' });
    request.flush(themeOf(ocean));

    expect(variable('--primary-color')).toBe('#0369A1');
  });

  it('a refused change (not an ADMIN) changes nothing here', () => {
    let error: any;
    service.updatePalette('tenant-a', 'ocean').subscribe({ error: e => (error = e) });
    http.expectOne(API).flush({ message: 'only an ADMIN' }, { status: 403, statusText: 'Forbidden' });

    expect(error.status).toBe(403);
    expect(variable('--primary-color')).toBe('');
  });

  it('uploads the logo as multipart in a part named "file", then fetches the new one', () => {
    let logo = null as string | null;
    service.logo$.subscribe(value => (logo = value));
    const file = new File([new Uint8Array([1, 2, 3])], 'logo.png', { type: 'image/png' });

    service.uploadLogo('tenant-a', file).subscribe();
    const upload = http.expectOne(`${API}/logo`);
    expect(upload.request.method).toBe('PUT');
    expect(upload.request.body instanceof FormData).toBeTrue();
    expect((upload.request.body as FormData).get('file')).toEqual(file);
    upload.flush({ logoUrl: '/organizations/tenant-a/theme/logo?v=9' });
    http.expectOne('http://localhost:8080/organizations/tenant-a/theme/logo?v=9').flush(new Blob(['x'], { type: 'image/png' }));

    expect(logo).toBe('blob:new-logo');
  });
});
