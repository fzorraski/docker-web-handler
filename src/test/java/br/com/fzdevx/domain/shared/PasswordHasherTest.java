package br.com.fzdevx.domain.shared;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PasswordHasherTest {

    @Test
    void hash_producesSelfDescribingFormat() {
        String encoded = PasswordHasher.hash("s3cret");
        String[] parts = encoded.split("\\$");
        assertEquals(4, parts.length);
        assertEquals("pbkdf2-sha256", parts[0]);
        assertTrue(Integer.parseInt(parts[1]) >= 210_000);
    }

    @Test
    void hash_generatesUniqueSaltPerCall() {
        assertNotEquals(PasswordHasher.hash("s3cret"), PasswordHasher.hash("s3cret"));
    }

    @Test
    void verify_acceptsCorrectPassword() {
        String encoded = PasswordHasher.hash("correct horse battery staple");
        assertTrue(PasswordHasher.verify("correct horse battery staple", encoded));
    }

    @Test
    void verify_rejectsWrongPassword() {
        String encoded = PasswordHasher.hash("s3cret");
        assertFalse(PasswordHasher.verify("wrong", encoded));
    }

    @Test
    void verify_rejectsNullAndEmptyInput() {
        String encoded = PasswordHasher.hash("s3cret");
        assertFalse(PasswordHasher.verify(null, encoded));
        assertFalse(PasswordHasher.verify("", encoded));
        assertFalse(PasswordHasher.verify("s3cret", null));
        assertFalse(PasswordHasher.verify("s3cret", ""));
    }

    @Test
    void verify_rejectsMalformedEncodedValues() {
        assertFalse(PasswordHasher.verify("s3cret", "not-a-hash"));
        assertFalse(PasswordHasher.verify("s3cret", "bcrypt$10$abc$def"));
        assertFalse(PasswordHasher.verify("s3cret", "pbkdf2-sha256$oops$c2FsdA==$aGFzaA=="));
        assertFalse(PasswordHasher.verify("s3cret", "pbkdf2-sha256$210000$!!!$aGFzaA=="));
    }

    @Test
    void verify_rejectsExcessiveIterationCount() {
        assertFalse(PasswordHasher.verify("s3cret", "pbkdf2-sha256$99999999$c2FsdA==$aGFzaA=="));
    }

    @Test
    void hash_rejectsBlankPassword() {
        assertThrows(IllegalArgumentException.class, () -> PasswordHasher.hash(null));
        assertThrows(IllegalArgumentException.class, () -> PasswordHasher.hash("  "));
    }
}
