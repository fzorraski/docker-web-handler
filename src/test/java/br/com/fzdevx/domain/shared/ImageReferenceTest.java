package br.com.fzdevx.domain.shared;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ImageReferenceTest {

    @Test
    void repository_stripsTag() {
        assertEquals("nginx", ImageReference.repository("nginx:1.27"));
        assertEquals("nginx", ImageReference.repository("nginx"));
        assertEquals("myorg/myapp", ImageReference.repository("myorg/myapp:2.1"));
    }

    @Test
    void repository_stripsDigest() {
        assertEquals("nginx", ImageReference.repository("nginx@sha256:0123456789abcdef"));
        assertEquals("myorg/app", ImageReference.repository("myorg/app@sha256:deadbeef"));
    }

    @Test
    void repository_preservesRegistryPort() {
        // The ':' in localhost:5000 is a registry port, not a tag separator.
        assertEquals("localhost:5000/myapp", ImageReference.repository("localhost:5000/myapp:1.0"));
        assertEquals("localhost:5000/myapp", ImageReference.repository("localhost:5000/myapp"));
    }

    @Test
    void repository_trimsAndHandlesNull() {
        assertEquals("nginx", ImageReference.repository("  nginx:1  "));
        assertNull(ImageReference.repository(null));
    }

    @Test
    void shortName_returnsLastSegment() {
        assertEquals("myapp", ImageReference.shortName("registry.example.com/team/myapp:1.0"));
        assertEquals("myapp", ImageReference.shortName("myorg/myapp"));
        assertEquals("nginx", ImageReference.shortName("nginx:1.27"));
        assertNull(ImageReference.shortName(null));
    }
}
