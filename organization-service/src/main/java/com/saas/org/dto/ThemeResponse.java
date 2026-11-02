package com.saas.org.dto;

/** What the application reads on load/refresh to theme itself (REQ-PERS-06); logoUrl is filled in by P8.2. */
public record ThemeResponse(String paletteId, PaletteView palette, String logoUrl) {
}
