package br.com.fzdevx.domain.model.auth;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TenantPaletteTest {

    @Test
    void colorsAreDistinctAndCanonical() {
        assertEquals(TenantPalette.COLORS.size(), Set.copyOf(TenantPalette.COLORS).size());
        assertTrue(TenantPalette.COLORS.stream().allMatch(c -> c.equals(TenantPalette.normalize(c))),
                "palette entries must already be in canonical form");
    }

    @Test
    void normalize_upperCasesAndTrims() {
        assertEquals("#7C4DFF", TenantPalette.normalize("  #7c4dff "));
    }

    @Test
    void normalize_treatsBlankAsAbsent() {
        assertNull(TenantPalette.normalize(null));
        assertNull(TenantPalette.normalize("   "));
    }

    @Test
    void normalize_rejectsNonHex() {
        assertThrows(IllegalArgumentException.class, () -> TenantPalette.normalize("red"));
        assertThrows(IllegalArgumentException.class, () -> TenantPalette.normalize("#ABC"));
        assertThrows(IllegalArgumentException.class, () -> TenantPalette.normalize("7C4DFF"));
    }

    @Test
    void pickUnused_avoidsColorsAlreadyTaken() {
        List<String> taken = TenantPalette.COLORS.subList(0, TenantPalette.COLORS.size() - 1);

        assertEquals(TenantPalette.COLORS.getLast(), TenantPalette.pickUnused(taken));
    }

    @Test
    void pickUnused_fallsBackToTheWholePaletteWhenExhausted() {
        assertTrue(TenantPalette.COLORS.contains(TenantPalette.pickUnused(TenantPalette.COLORS)));
    }

    @Test
    void pickUnused_handlesNoTenantsYet() {
        assertTrue(TenantPalette.COLORS.contains(TenantPalette.pickUnused(List.of())));
        assertTrue(TenantPalette.COLORS.contains(TenantPalette.pickUnused(null)));
    }

    @Test
    void pickUnused_isNotAlwaysTheSameColor() {
        // random, not round-robin: consecutive tenants must not come out as a gradient
        Set<String> seen = java.util.stream.IntStream.range(0, 60)
                .mapToObj(i -> TenantPalette.pickUnused(List.of()))
                .collect(Collectors.toSet());

        assertTrue(seen.size() > 1, "expected varied colours, always got " + seen);
    }

    @Test
    void colorFor_isStablePerTenantAndFromThePalette() {
        String first = TenantPalette.colorFor("tenant-a");

        assertEquals(first, TenantPalette.colorFor("tenant-a"));
        assertTrue(TenantPalette.COLORS.contains(first));
    }

    @Test
    void colorFor_survivesNegativeHashCodes() {
        // "polygenelubricants" is the classic negative-hash string; floorMod, not %
        assertTrue(TenantPalette.COLORS.contains(TenantPalette.colorFor("polygenelubricants")));
        assertFalse(TenantPalette.colorFor("polygenelubricants").isBlank());
    }

    @Test
    void resolve_prefersTheStoredColor() {
        assertEquals("#123456", TenantPalette.resolve("#123456", "tenant-a"));
        assertEquals(TenantPalette.colorFor("tenant-a"), TenantPalette.resolve(null, "tenant-a"));
        assertEquals(TenantPalette.colorFor("tenant-a"), TenantPalette.resolve("  ", "tenant-a"));
    }
}
