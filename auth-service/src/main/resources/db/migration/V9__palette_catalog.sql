-- Global, non-sharded catalog of the visual palettes a tenant ADMIN can pick from (REQ-PERS-02,
-- REQ-DATA-05). A tenant stores only a palette id; the colours live here, so no tenant can inject
-- raw CSS. Like plans, palettes are never deleted, only deactivated (REQ-DATA-12), because tenant
-- settings reference them by id with no foreign key across databases (REQ-DATA-10).
CREATE TABLE global.palettes (
    id         VARCHAR(50) PRIMARY KEY CHECK (id ~ '^[a-z0-9-]+$'),
    name       VARCHAR(100) NOT NULL,
    kind       VARCHAR(20) NOT NULL CHECK (kind IN ('COLOR', 'GRADIENT')),
    -- primary/secondary/accent as #RRGGBB; a GRADIENT also has gradient = [#RRGGBB, #RRGGBB, ...]
    definition JSONB NOT NULL,
    active     BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE FUNCTION global.reject_palette_deletion() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'palettes are never deleted, only deactivated (REQ-DATA-12)';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER no_palette_deletion
    BEFORE DELETE ON global.palettes
    FOR EACH ROW EXECUTE FUNCTION global.reject_palette_deletion();

-- 'default' is the theme every new tenant starts with (REQ-PERS-05); organization-service refers to it
-- through saas.theme.default-palette-id.
INSERT INTO global.palettes (id, name, kind, definition) VALUES
    ('default',  'Classic blue', 'COLOR',    '{"primary":"#1D4ED8","secondary":"#475569","accent":"#F59E0B"}'),
    ('forest',   'Forest',       'COLOR',    '{"primary":"#15803D","secondary":"#3F3F46","accent":"#EAB308"}'),
    ('rose',     'Rose',         'COLOR',    '{"primary":"#BE185D","secondary":"#4B5563","accent":"#14B8A6"}'),
    ('midnight', 'Midnight',     'COLOR',    '{"primary":"#312E81","secondary":"#1E293B","accent":"#22D3EE"}'),
    ('sunset',   'Sunset',       'GRADIENT', '{"primary":"#EA580C","secondary":"#7C2D12","accent":"#FACC15","gradient":["#F97316","#DB2777"]}'),
    ('ocean',    'Ocean',        'GRADIENT', '{"primary":"#0369A1","secondary":"#134E4A","accent":"#F472B6","gradient":["#0EA5E9","#14B8A6"]}');
