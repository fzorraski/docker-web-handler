package br.com.fzdevx.infrastructure.docker;

import br.com.fzdevx.domain.shared.ImageReference;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;

import java.util.Arrays;
import java.util.List;

/**
 * Resolves the optional initial command(s) to auto-run when a terminal connects,
 * keyed by the container image's short name (last path segment, tag/digest stripped).
 *
 * <p>Configuration key: {@code terminal.init-command.<image-short-name>}. The value is a
 * sequence of commands separated by {@code ';'} that are run in order on connect. A
 * missing or empty value means no command is sent.
 */
@ApplicationScoped
public class TerminalInitCommandResolver {

    static final String KEY_PREFIX = "terminal.init-command.";

    @Inject
    Config config;

    /**
     * Returns the ordered list of commands to run for the given image, or an empty list
     * when none is configured.
     */
    public List<String> resolve(String image) {
        String shortName = ImageReference.shortName(image);
        if (shortName == null || shortName.isBlank()) {
            return List.of();
        }
        String raw = config.getOptionalValue(KEY_PREFIX + shortName, String.class).orElse("");
        return Arrays.stream(raw.split(";"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
