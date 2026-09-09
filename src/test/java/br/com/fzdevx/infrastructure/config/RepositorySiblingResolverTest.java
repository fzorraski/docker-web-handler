package br.com.fzdevx.infrastructure.config;

import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RepositorySiblingResolverTest {

    @Mock Config config;
    @Mock AllowedRepositoryResolver allowed;

    RepositorySiblingResolver resolver;

    @BeforeEach
    void setUp() {
        when(config.getOptionalValue(anyString(), eq(String.class))).thenReturn(Optional.empty());
        when(config.getOptionalValue(anyString(), eq(Integer.class))).thenReturn(Optional.empty());
        when(allowed.getAllowed()).thenReturn(List.of("prod", "qa", "dev", "nopg", "other"));
        host("prod", "DB.Example.com"); host("qa", "db.example.com"); port("qa", 5432);
        host("dev", "db.example.com"); port("dev", 5433);
        host("other", "elsewhere");
        resolver = new RepositorySiblingResolver(config, allowed);
    }

    private void host(String repo, String value) {
        when(config.getOptionalValue("repository.pg-host." + repo, String.class)).thenReturn(Optional.of(value));
    }

    private void port(String repo, int value) {
        when(config.getOptionalValue("repository.pg-port." + repo, Integer.class)).thenReturn(Optional.of(value));
    }

    @Test
    void serverKey_lowercasesHostAndDefaultsPort() {
        assertEquals(Optional.of("db.example.com:5432"), resolver.serverKey("prod"));
        assertEquals(Optional.of("db.example.com:5432"), resolver.serverKey("qa"));
        assertEquals(Optional.of("db.example.com:5433"), resolver.serverKey("dev"));
        assertEquals(Optional.empty(), resolver.serverKey("nopg"));
        assertEquals(Optional.empty(), resolver.serverKey(null));
    }

    @Test
    void serverKey_blankHostIsAbsent() {
        host("blank", "   ");
        assertEquals(Optional.empty(), resolver.serverKey("blank"));
    }

    @Test
    void siblings_sameHostAndPort_actingRepositoryFirst() {
        assertEquals(List.of("prod", "qa"), resolver.siblings("prod"));
        assertEquals(List.of("qa", "prod"), resolver.siblings("qa"));
    }

    @Test
    void siblings_differentPortOrHost_areNotSiblings() {
        assertEquals(List.of("dev"), resolver.siblings("dev"));
        assertEquals(List.of("other"), resolver.siblings("other"));
    }

    @Test
    void siblings_userIsIgnored() {
        when(config.getOptionalValue("repository.pg-user.prod", String.class)).thenReturn(Optional.of("alice"));
        when(config.getOptionalValue("repository.pg-user.qa", String.class)).thenReturn(Optional.of("bob"));
        assertEquals(List.of("prod", "qa"), resolver.siblings("prod"));
    }

    @Test
    void siblings_withoutPgConfig_isOnlyItself() {
        assertEquals(List.of("nopg"), resolver.siblings("nopg"));
        assertEquals(List.of("unknown"), resolver.siblings("unknown"));
    }

    @Test
    void siblings_nullOrBlank_isEmpty() {
        assertEquals(List.of(), resolver.siblings(null));
        assertEquals(List.of(), resolver.siblings("  "));
        assertEquals(List.of(), resolver.siblings(null, List.of("prod")));
    }

    @Test
    void siblings_includeKnownRepositoriesDroppedFromTheAllowedList() {
        host("legacy", "db.example.com");
        when(allowed.getAllowed()).thenReturn(List.of("qa"));

        assertEquals(List.of("qa"), resolver.siblings("qa"));
        assertEquals(List.of("qa", "legacy"), resolver.siblings("qa", List.of("legacy", "nopg", "QA")));
        assertEquals(List.of("db.example.com:5432"), List.copyOf(resolver.siblingGroups(List.of("legacy")).keySet()));
        assertEquals(List.of("qa", "legacy"), resolver.siblingGroups(List.of("legacy")).get("db.example.com:5432"));
    }

    @Test
    void siblingGroups_keyedByServer_inAllowedOrder() {
        Map<String, List<String>> groups = resolver.siblingGroups();
        assertEquals(List.of("prod", "qa"), groups.get("db.example.com:5432"));
        assertEquals(List.of("dev"), groups.get("db.example.com:5433"));
        assertEquals(List.of("other"), groups.get("elsewhere:5432"));
        assertEquals(3, groups.size());
    }
}
