package br.com.fzdevx.infrastructure.persistence.jdbc;

import br.com.fzdevx.application.port.ManagedDatabaseRepository;
import br.com.fzdevx.domain.model.ManagedDatabase;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(SiblingPostgresProfile.class)
class PgSiblingRepositoriesTest {

    @Inject
    ManagedDatabaseRepository managedDatabases;

    @Inject
    PgManagedDatabaseRepository raw;

    @Test
    void injectedPort_sharesRowsBetweenSiblings() {
        String name = "db-" + UUID.randomUUID().toString().substring(0, 8);
        ManagedDatabase md = new ManagedDatabase("sib-a", name);
        md.setCreatedBy("alice");
        managedDatabases.save(md);

        assertTrue(managedDatabases.update("sib-b", name.toUpperCase(), stored -> stored.setProtectedFlag(true)));

        ManagedDatabase seenFromB = managedDatabases.find("sib-b", name).orElseThrow();
        assertTrue(seenFromB.isProtectedFlag());
        assertEquals("alice", seenFromB.getCreatedBy());
        assertEquals(1, raw.findCandidates(List.of("sib-a", "sib-b"), name).size());
        assertTrue(managedDatabases.find("solo", name).isEmpty());
    }

    @Test
    void rawFindByRepositories_matchesCaseInsensitively() {
        String name = "db-" + UUID.randomUUID().toString().substring(0, 8);
        raw.save(new ManagedDatabase("Sib-A", name));

        assertEquals(1, raw.findByRepositories(List.of("SIB-a")).stream().filter(m -> m.getName().equals(name)).count());
        assertEquals(1, raw.findCandidates(List.of("sib-a"), name.toUpperCase()).size());
        assertTrue(raw.findCandidates(List.of(), name).isEmpty());
    }
}
