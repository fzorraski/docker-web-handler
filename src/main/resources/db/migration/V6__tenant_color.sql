-- Badge colour per tenant. Every tenant chip used to render in the same violet,
-- which made the tenant column unreadable at a glance once more than one team
-- existed; the colour is what distinguishes them now.
ALTER TABLE tenant ADD COLUMN color text;

-- Existing tenants get spread across the palette instead of all landing on the
-- same hue. Ordered by creation so the assignment is deterministic, and kept in
-- step with TenantPalette.COLORS - a tenant with a NULL colour would otherwise
-- fall back to an id-derived hue that the edit dialog cannot show as selected.
WITH palette(slot, hex) AS (
    VALUES (0, '#7C4DFF'), (1, '#2196F3'), (2, '#00BCD4'), (3, '#009688'),
           (4, '#4CAF50'), (5, '#9CCC65'), (6, '#FFB300'), (7, '#FF7043'),
           (8, '#EC407A'), (9, '#AB47BC'), (10, '#5C6BC0'), (11, '#78909C')
),
numbered AS (
    SELECT id, (row_number() OVER (ORDER BY created_at NULLS LAST, id) - 1) % 12 AS slot
    FROM tenant
)
UPDATE tenant t
SET color = palette.hex
FROM numbered, palette
WHERE t.id = numbered.id
  AND palette.slot = numbered.slot;
