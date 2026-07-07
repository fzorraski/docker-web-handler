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
    JsonFileUserRepository repo;

    @BeforeEach
    void setUp() {
        repo = new JsonFileUserRepository(tempDir.resolve("users.json").toString());
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
        assertEquals(BuiltInRoles.ADMIN_ID, found.getRoleId());
        assertTrue(found.isEnabled());
        assertNotNull(found.getCreatedAt());
        assertEquals(Instant.parse("2026-07-01T10:00:00Z"), found.getLastLoginAt());
    }

    @Test
    void save_updatesExistingUserById() {
        User user = new User("alice", "hash1", BuiltInRoles.VIEWER_ID);
        repo.save(user);
        user.setRoleId(BuiltInRoles.OPERATOR_ID);
        user.setEnabled(false);
        repo.save(user);

        assertEquals(1, repo.findAll().size());
        User found = repo.findById(user.getId()).orElseThrow();
        assertEquals(BuiltInRoles.OPERATOR_ID, found.getRoleId());
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
}
