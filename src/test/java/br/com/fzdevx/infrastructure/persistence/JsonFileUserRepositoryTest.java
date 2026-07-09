package br.com.fzdevx.infrastructure.persistence;

import br.com.fzdevx.domain.model.auth.BuiltInRoles;
import br.com.fzdevx.domain.model.auth.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class JsonFileUserRepositoryTest {

    @TempDir Path tempDir;
    Path file;
    JsonFileUserRepository repo;

    @BeforeEach
    void setUp() {
        file = tempDir.resolve("users.json");
        repo = new JsonFileUserRepository(file.toString());
    }

    @Test
    void save_thenFindById_roundTripsAllFields() {
        User user = new User("alice", "pbkdf2-sha256$210000$c2FsdA==$aGFzaA==", BuiltInRoles.ADMIN_ID);
        user.setLastLoginAt(Instant.parse("2026-07-01T10:00:00Z"));
        repo.save(user);

        // fresh instance to force a file re-read
        JsonFileUserRepository reloaded =
                new JsonFileUserRepository(tempDir.resolve("users.json").toString());
        User found = reloaded.findById(user.getId()).orElseThrow();
        assertEquals("alice", found.getUsername());
        assertEquals("pbkdf2-sha256$210000$c2FsdA==$aGFzaA==", found.getPasswordHash());
        assertEquals(java.util.List.of(BuiltInRoles.ADMIN_ID), found.getRoleIds());
        assertTrue(found.isEnabled());
        assertNotNull(found.getCreatedAt());
        assertEquals(Instant.parse("2026-07-01T10:00:00Z"), found.getLastLoginAt());
    }

    @Test
    void save_updatesExistingUserById() {
        User user = new User("alice", "hash1", BuiltInRoles.VIEWER_ID);
        repo.save(user);
        user.setRoleIds(java.util.List.of(BuiltInRoles.OPERATOR_ID));
        user.setEnabled(false);
        repo.save(user);

        assertEquals(1, repo.findAll().size());
        User found = repo.findById(user.getId()).orElseThrow();
        assertEquals(java.util.List.of(BuiltInRoles.OPERATOR_ID), found.getRoleIds());
        assertFalse(found.isEnabled());
    }

    @Test
    void findByUsername_isCaseInsensitive() {
        repo.save(new User("Alice", "hash", BuiltInRoles.VIEWER_ID));
        assertTrue(repo.findByUsername("alice").isPresent());
        assertTrue(repo.findByUsername("ALICE").isPresent());
        assertEquals(Optional.empty(), repo.findByUsername("bob"));
        assertEquals(Optional.empty(), repo.findByUsername(null));
    }

    @Test
    void delete_removesUser() {
        User user = new User("alice", "hash", BuiltInRoles.VIEWER_ID);
        repo.save(user);
        repo.delete(user.getId());
        assertTrue(repo.findAll().isEmpty());
    }

    @Test
    void count_reflectsNumberOfUsers() {
        assertEquals(0, repo.count());
        repo.save(new User("alice", "hash", BuiltInRoles.VIEWER_ID));
        repo.save(new User("bob", "hash", BuiltInRoles.VIEWER_ID));
        assertEquals(2, repo.count());
    }

    @Test
    void read_legacySingleRoleFile_migratesToRoleIds() throws Exception {
        // users.json written before roles became a list
        java.nio.file.Files.writeString(file,
                "[{\"id\":\"u1\",\"username\":\"legacy\",\"passwordHash\":\"hash\","
                        + "\"roleId\":\"" + BuiltInRoles.OPERATOR_ID + "\",\"enabled\":true}]");

        User found = repo.findByUsername("legacy").orElseThrow();
        assertEquals(java.util.List.of(BuiltInRoles.OPERATOR_ID), found.getRoleIds());

        // saving rewrites the file in the new format, without the legacy key
        repo.save(found);
        String rewritten = java.nio.file.Files.readString(file);
        assertTrue(rewritten.contains("roleIds"));
        assertFalse(rewritten.contains("\"roleId\""));
    }

    @Test
    void update_appliesFieldChangeToFreshState() {
        User user = new User("alice", "hash", BuiltInRoles.OPERATOR_ID);
        repo.save(user);

        // two admins edit different fields; each atomic update re-reads the
        // stored state, so neither change rolls the other one back
        assertTrue(repo.update(user.getId(), u -> u.setTenantIds(java.util.List.of("tenant-1"))));
        assertTrue(repo.update(user.getId(), u -> u.setEnabled(false)));

        User result = repo.findById(user.getId()).orElseThrow();
        assertFalse(result.isEnabled());
        assertEquals(java.util.List.of("tenant-1"), result.getTenantIds());
    }

    @Test
    void update_unknownUser_returnsFalseAndWritesNothing() {
        assertFalse(repo.update("no-such-id", u -> u.setEnabled(false)));
        assertFalse(java.nio.file.Files.exists(file));
    }
}
