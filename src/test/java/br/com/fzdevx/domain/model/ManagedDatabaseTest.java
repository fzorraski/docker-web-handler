package br.com.fzdevx.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;


class ManagedDatabaseTest {

    // ---- constructor ----

    @Test
    void constructor_setsAllFields() {
        ManagedDatabase db = new ManagedDatabase("myrepo", "mydb");

        assertEquals("myrepo", db.getRepository());
        assertEquals("mydb", db.getName());
        assertFalse(db.isProtectedFlag());
        assertNotNull(db.getCreatedAt());
        assertNull(db.getAppLastUsedAt());
        assertNull(db.getDescription());
    }

    @Test
    void noArgConstructor_allFieldsNull() {
        ManagedDatabase db = new ManagedDatabase();

        assertNull(db.getRepository());
        assertNull(db.getName());
        assertFalse(db.isProtectedFlag());
        assertNull(db.getCreatedAt());
    }

    // ---- getters/setters ----

    @Test
    void setProtectedFlag_updatesValue() {
        ManagedDatabase db = new ManagedDatabase("repo", "db");
        assertFalse(db.isProtectedFlag());

        db.setProtectedFlag(true);
        assertTrue(db.isProtectedFlag());

        db.setProtectedFlag(false);
        assertFalse(db.isProtectedFlag());
    }

    @Test
    void setAppLastUsedAt_updatesValue() {
        ManagedDatabase db = new ManagedDatabase("repo", "db");
        Instant now = Instant.now();
        db.setAppLastUsedAt(now);

        assertEquals(now, db.getAppLastUsedAt());
    }

    @Test
    void setDescription_updatesValue() {
        ManagedDatabase db = new ManagedDatabase("repo", "db");
        assertNull(db.getDescription());

        db.setDescription("Test description");
        assertEquals("Test description", db.getDescription());

        db.setDescription(null);
        assertNull(db.getDescription());
    }

    // ---- equals/hashCode ----

    @Test
    void equals_sameRepositoryAndName_areEqual() {
        ManagedDatabase a = new ManagedDatabase("repo", "db");
        ManagedDatabase b = new ManagedDatabase("repo", "db");

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void equals_differentRepository_areNotEqual() {
        ManagedDatabase a = new ManagedDatabase("repo1", "db");
        ManagedDatabase b = new ManagedDatabase("repo2", "db");

        assertNotEquals(a, b);
    }

    @Test
    void equals_differentName_areNotEqual() {
        ManagedDatabase a = new ManagedDatabase("repo", "db1");
        ManagedDatabase b = new ManagedDatabase("repo", "db2");

        assertNotEquals(a, b);
    }

    @Test
    void equals_null_returnsFalse() {
        ManagedDatabase a = new ManagedDatabase("repo", "db");
        assertNotEquals(null, a);
    }

    @Test
    void equals_differentType_returnsFalse() {
        ManagedDatabase a = new ManagedDatabase("repo", "db");
        assertNotEquals("not a db", a);
    }

    @Test
    void equals_protectedFlagDifference_stillEqual() {
        ManagedDatabase a = new ManagedDatabase("repo", "db");
        ManagedDatabase b = new ManagedDatabase("repo", "db");
        b.setProtectedFlag(true);

        assertEquals(a, b);
    }
}
