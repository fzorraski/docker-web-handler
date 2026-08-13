package br.com.fzdevx.infrastructure.config;

import br.com.fzdevx.application.port.TenantRepository;
import br.com.fzdevx.domain.model.auth.Tenant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TenantSharingTest {

    @Mock
    TenantRepository tenantRepository;

    private TenantSharing sharing() {
        return TestTenantSharing.with(tenantRepository);
    }

    // ---- normalize ----

    @Test
    void normalize_dropsTheOwner() {
        // the owner already sees it, and a stored copy of its id would outlive
        // any later change of owner
        assertEquals(List.of("t2"), sharing().normalize(List.of("t1", "t2"), "t1"));
    }

    @Test
    void normalize_dropsDuplicatesBlanksAndNulls() {
        assertEquals(List.of("t2", "t3"),
                sharing().normalize(Arrays.asList("t2", " ", null, "t3", "t2", ""), "t1"));
    }

    @Test
    void normalize_trimsIds() {
        assertEquals(List.of("t2"), sharing().normalize(List.of("  t2  "), "t1"));
    }

    @Test
    void normalize_keepsOrder() {
        assertEquals(List.of("t3", "t2"), sharing().normalize(List.of("t3", "t2"), "t1"));
    }

    @Test
    void normalize_nullOrEmpty_isEmpty() {
        assertTrue(sharing().normalize(null, "t1").isEmpty());
        assertTrue(sharing().normalize(List.of(), "t1").isEmpty());
    }

    @Test
    void normalize_noOwner_keepsEverything() {
        assertEquals(List.of("t1", "t2"), sharing().normalize(List.of("t1", "t2"), null));
    }

    // ---- parse / toCsv ----

    @Test
    void parse_splitsTrimsAndDropsTheOwner() {
        assertEquals(List.of("t2", "t3"), sharing().parse("t1, t2 ,t3,,t2", "t1"));
    }

    @Test
    void parse_blankOrNull_isEmpty() {
        assertTrue(sharing().parse(null, "t1").isEmpty());
        assertTrue(sharing().parse("   ", "t1").isEmpty());
    }

    @Test
    void toCsv_roundTripsThroughParse() {
        String csv = TenantSharing.toCsv(List.of("t2", "t3"));
        assertEquals("t2,t3", csv);
        assertEquals(List.of("t2", "t3"), sharing().parse(csv, "t1"));
    }

    @Test
    void toCsv_emptyMeansNoLabel() {
        // null, so the caller writes no docker label at all
        assertNull(TenantSharing.toCsv(List.of()));
        assertNull(TenantSharing.toCsv(null));
    }

    // ---- firstUnknown ----

    @Test
    void firstUnknown_reportsTheFirstMissingTenant() {
        Tenant known = new Tenant("Known", null);
        known.setId("t2");
        when(tenantRepository.findAll()).thenReturn(List.of(known));

        assertEquals(Optional.of("nope"), sharing().firstUnknown(List.of("t2", "nope")));
    }

    @Test
    void firstUnknown_allKnown_singleRepositoryRead() {
        Tenant a = new Tenant("A", null);
        a.setId("t2");
        Tenant b = new Tenant("B", null);
        b.setId("t3");
        when(tenantRepository.findAll()).thenReturn(List.of(a, b));

        assertTrue(sharing().firstUnknown(List.of("t2", "t3")).isEmpty());
        // one snapshot for the whole list - per-id lookups re-read the backing
        // file (or re-query Postgres) once per element
        org.mockito.Mockito.verify(tenantRepository, org.mockito.Mockito.times(1)).findAll();
    }

    @Test
    void normalize_rejectsOversizedLists() {
        List<String> huge = java.util.stream.IntStream.range(0, 101)
                .mapToObj(i -> "t" + i).toList();
        org.junit.jupiter.api.Assertions.assertThrows(
                br.com.fzdevx.domain.exception.InvalidInputException.class,
                () -> sharing().normalize(huge, null));
    }

    @Test
    void asStrings_coercesAndCaps() {
        assertEquals(List.of("a", "1"), TenantSharing.asStrings(java.util.Arrays.asList("a", null, 1)));
        assertTrue(TenantSharing.asStrings("not-a-list").isEmpty());
        List<Object> huge = java.util.stream.IntStream.range(0, 101)
                .mapToObj(i -> (Object) ("t" + i)).toList();
        org.junit.jupiter.api.Assertions.assertThrows(
                br.com.fzdevx.domain.exception.InvalidInputException.class,
                () -> TenantSharing.asStrings(huge));
    }

    @Test
    void firstUnknown_emptyList_neverTouchesTheRepository() {
        // why TestTenantSharing.withoutRepository() is safe in fixtures
        assertTrue(TestTenantSharing.withoutRepository().firstUnknown(List.of()).isEmpty());
        assertTrue(TestTenantSharing.withoutRepository().firstUnknown(null).isEmpty());
    }
}
