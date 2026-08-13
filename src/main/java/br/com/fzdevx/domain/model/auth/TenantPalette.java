package br.com.fzdevx.domain.model.auth;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * The colours a tenant badge can take.
 *
 * <p>A fixed palette rather than a free colour wheel: the badges have to stay
 * legible on both the dark and the light theme, and twelve hand-picked hues
 * spread around the wheel stay distinguishable from each other in a list -
 * neither property survives letting every admin type an arbitrary hex. A custom
 * value is still accepted (see {@link #normalize}); it just isn't the default
 * path.</p>
 */
public final class TenantPalette {

    /**
     * Twelve mid-tone hues, one per clock position. The first is the violet every
     * tenant badge used before colours existed, so an installation that never
     * touches the picker keeps the look it had.
     */
    public static final List<String> COLORS = List.of(
            "#7C4DFF", // violet
            "#2196F3", // blue
            "#00BCD4", // cyan
            "#009688", // teal
            "#4CAF50", // green
            "#9CCC65", // lime
            "#FFB300", // amber
            "#FF7043", // coral
            "#EC407A", // pink
            "#AB47BC", // purple
            "#5C6BC0", // indigo
            "#78909C"  // blue grey
    );

    private static final Pattern HEX = Pattern.compile("#[0-9A-Fa-f]{6}");

    private TenantPalette() {
    }

    /**
     * Canonical form of a caller-supplied colour, or null when none was given.
     *
     * @throws IllegalArgumentException if the value is not a 6-digit hex colour
     */
    public static String normalize(String color) {
        if (color == null || color.isBlank()) {
            return null;
        }
        String trimmed = color.trim();
        if (!HEX.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("Colour must be a hex value like #7C4DFF.");
        }
        return trimmed.toUpperCase();
    }

    /**
     * A random colour for a new tenant, drawn from the ones no tenant is using
     * yet. Random rather than round-robin so consecutive tenants do not come out
     * as an obvious gradient, but unused-first so two tenants only ever collide
     * once the palette is exhausted.
     */
    public static String pickUnused(Collection<String> usedColors) {
        Set<String> taken = usedColors == null ? Set.of() : Set.copyOf(usedColors);
        List<String> free = COLORS.stream().filter(color -> !taken.contains(color)).toList();
        List<String> candidates = free.isEmpty() ? COLORS : free;
        return candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
    }

    /**
     * The colour a tenant without a stored one falls back to, derived from its id
     * so it is stable across restarts and identical in every screen that renders
     * the badge. Covers tenants created before the palette existed.
     */
    public static String colorFor(String tenantId) {
        if (tenantId == null || tenantId.isEmpty()) {
            return COLORS.getFirst();
        }
        return COLORS.get(Math.floorMod(tenantId.hashCode(), COLORS.size()));
    }

    /** The stored colour when there is one, the id-derived fallback otherwise. */
    public static String resolve(String storedColor, String tenantId) {
        return storedColor == null || storedColor.isBlank() ? colorFor(tenantId) : storedColor;
    }
}
