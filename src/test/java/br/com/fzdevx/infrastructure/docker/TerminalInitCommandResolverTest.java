package br.com.fzdevx.infrastructure.docker;

import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TerminalInitCommandResolverTest {

    @Mock Config config;

    private TerminalInitCommandResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new TerminalInitCommandResolver();
        resolver.config = config;
    }

    private void configure(String shortName, String value) {
        when(config.getOptionalValue(TerminalInitCommandResolver.KEY_PREFIX + shortName, String.class))
                .thenReturn(Optional.of(value));
    }

    @Test
    void singleCommand() {
        configure("myimage", "support-repl");
        assertEquals(List.of("support-repl"), resolver.resolve("registry/team/myimage:1.2"));
    }

    @Test
    void multipleCommands_runInOrder_trimmed() {
        configure("myimage", "clear ; support-repl ; ls -la");
        assertEquals(List.of("clear", "support-repl", "ls -la"), resolver.resolve("myimage:latest"));
    }

    @Test
    void blankSegmentsDropped() {
        configure("myimage", ";clear;; ;support-repl;");
        assertEquals(List.of("clear", "support-repl"), resolver.resolve("myimage"));
    }

    @Test
    void keyDerivedFromImageShortName() {
        // tag/digest stripped, registry/namespace dropped -> short name "app"
        configure("app", "clear");
        assertEquals(List.of("clear"), resolver.resolve("registry.example.com:5000/team/app:1.0"));
    }

    @Test
    void noConfig_emptyList() {
        when(config.getOptionalValue(anyString(), eq(String.class))).thenReturn(Optional.empty());
        assertTrue(resolver.resolve("nginx:latest").isEmpty());
    }

    @Test
    void emptyValue_emptyList() {
        configure("nginx", "   ");
        assertTrue(resolver.resolve("nginx").isEmpty());
    }

    @Test
    void nullImage_emptyList() {
        assertTrue(resolver.resolve(null).isEmpty());
        verifyNoInteractions(config);
    }
}
