import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { AuthService } from '../../core/services/auth.service';
import { Palette, Theme, ThemeService } from '../../core/services/theme.service';

/** The logo limits the server enforces (REQ-PERS-03); checked here only to save a round trip. */
const MAX_LOGO_BYTES = 2 * 1024 * 1024;
const LOGO_TYPES = ['image/png', 'image/jpeg'];

/** The theme editor for an ADMIN (REQ-PERS-01): pick a palette from the catalog and upload a PNG or JPG logo. */
@Component({
  selector: 'app-theme-editor',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './theme-editor.component.html',
  styleUrls: ['./theme-editor.component.css']
})
export class ThemeEditorComponent implements OnInit {
  palettes: Palette[] = [];
  theme: Theme | null = null;
  logo$ = this.themeService.logo$;
  loading = true;
  saving = false;
  error: string | null = null;
  message: string | null = null;

  constructor(private authService: AuthService, private themeService: ThemeService) {}

  private get tenantId(): string {
    return this.authService.currentUser?.tenantId ?? '';
  }

  ngOnInit() {
    this.themeService.theme$.subscribe(theme => (this.theme = theme));
    this.themeService.listPalettes(this.tenantId).subscribe({
      next: palettes => {
        this.palettes = palettes;
        this.loading = false;
      },
      error: () => {
        this.error = 'The palettes could not be loaded.';
        this.loading = false;
      }
    });
  }

  colors(palette: Palette) {
    return this.themeService.safeColors(palette);
  }

  choose(palette: Palette) {
    this.saving = true;
    this.error = null;
    this.message = null;
    this.themeService.updatePalette(this.tenantId, palette.id).subscribe({
      next: () => {
        this.message = `The theme is now ${palette.name}.`;
        this.saving = false;
      },
      error: (error) => {
        this.error = error.error?.message || 'The theme could not be changed.';
        this.saving = false;
      }
    });
  }

  onLogoSelected(event: Event) {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    this.error = null;
    this.message = null;
    if (!file) {
      return;
    }
    if (!LOGO_TYPES.includes(file.type)) {
      this.error = 'The logo must be a PNG or a JPG image.';
      input.value = '';
      return;
    }
    if (file.size > MAX_LOGO_BYTES) {
      this.error = 'The logo must be at most 2 MB.';
      input.value = '';
      return;
    }
    this.saving = true;
    this.themeService.uploadLogo(this.tenantId, file).subscribe({
      next: () => {
        this.message = 'The logo is updated.';
        this.saving = false;
        input.value = '';
      },
      error: (error) => {
        this.error = error.error?.message || 'The logo could not be uploaded.';
        this.saving = false;
        input.value = '';
      }
    });
  }
}
