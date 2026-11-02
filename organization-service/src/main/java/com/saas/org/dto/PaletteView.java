package com.saas.org.dto;

import java.util.Map;

/** A palette as the UI needs to render it; the definition holds only #RRGGBB values (REQ-PERS-02). */
public record PaletteView(String id, String name, String kind, Map<String, Object> definition) {
}
