package br.com.fzdevx.infrastructure.docker;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContainerVisibilityServiceTest {

    private ContainerVisibilityService service;

    @BeforeEach
    void setUp() {
        service = new ContainerVisibilityService();
        service.hiddenImages = Optional.empty();
    }

    private void configure(String... images) {
        service.hiddenImages = Optional.of(List.of(images));
    }

    @Test
    void disabledByDefault() {
        assertFalse(service.isEnabled());
        assertFalse(service.isHiddenImage("postgres:17-alpine"));
    }

    @Test
    void onlyBlankEntries_disabled() {
        configure("", "   ");
        assertFalse(service.isEnabled());
        assertFalse(service.isHiddenImage("postgres:17-alpine"));
    }

    @Test
    void taggedEntry_hidesOnlyThatTag() {
        configure("postgres:17-alpine");
        assertTrue(service.isHiddenImage("postgres:17-alpine"));
        assertFalse(service.isHiddenImage("postgres:16"));
        assertFalse(service.isHiddenImage("postgres"));
    }

    @Test
    void untaggedEntry_hidesEveryTag() {
        configure("postgres");
        assertTrue(service.isHiddenImage("postgres:17-alpine"));
        assertTrue(service.isHiddenImage("postgres:16"));
    }

    @Test
    void namespacedImage_matchesByShortName() {
        configure("myapp");
        assertTrue(service.isHiddenImage("registry.example.com/team/myapp:1.0"));
        assertTrue(service.isHiddenImage("localhost:5000/myapp:1.0"));
    }

    @Test
    void registryPortEntry_withoutTag_hidesEveryTag() {
        // the ':' in registry.example.com:5000 is a port, not a tag separator
        configure("registry.example.com:5000/postgres");
        assertTrue(service.isHiddenImage("registry.example.com:5000/postgres:17"));
        assertTrue(service.isHiddenImage("registry.example.com:5000/postgres"));
    }

    @Test
    void anyRepoTagHidden_hidesTheImage() {
        configure("postgres:17-alpine");
        // RepoTags order is not guaranteed: the hidden tag may not be first
        assertTrue(service.isHiddenAnyTag(new String[]{"postgres:latest", "postgres:17-alpine"}));
        assertFalse(service.isHiddenAnyTag(new String[]{"postgres:latest", "postgres:16"}));
    }

    @Test
    void repoTagsNullOrEmpty_notHidden() {
        configure("postgres:17-alpine");
        assertFalse(service.isHiddenAnyTag(null));
        assertFalse(service.isHiddenAnyTag(new String[]{}));
    }

    @Test
    void unrelatedImage_notHidden() {
        configure("postgres:17-alpine");
        assertFalse(service.isHiddenImage("mywms-spk:1.0"));
        assertFalse(service.isHiddenImage(null));
        assertFalse(service.isHiddenImage("  "));
    }
}
