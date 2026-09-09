package br.com.fzdevx.infrastructure.config;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Groups repositories by the PostgreSQL server they point at. Two repositories whose
 * {@code repository.pg-host.<repo>} and {@code repository.pg-port.<repo>} match are
 * "siblings": they list the same physical databases, so managed-database metadata
 * (protection, creator, tenant, ...) must be shared between them. The PG user is
 * deliberately ignored: a different login on the same server is still the same server.
 *
 * <p>Derived live from configuration on every call, like {@link AllowedRepositoryResolver},
 * so dev-mode reloads and environment changes are picked up without caching logic.</p>
 */
@ApplicationScoped
public class RepositorySiblingResolver {

    @Inject
    Config config;

    @Inject
    AllowedRepositoryResolver allowedRepositoryResolver;

    public RepositorySiblingResolver() {}

    public RepositorySiblingResolver(Config config, AllowedRepositoryResolver allowedRepositoryResolver) {
        this.config = config;
        this.allowedRepositoryResolver = allowedRepositoryResolver;
    }

    /** {@code host:port} with the host lowercased, or empty when the repository has no PG host. */
    public Optional<String> serverKey(String repository) {
        if (repository == null || repository.isBlank()) return Optional.empty();
        Optional<String> host = config.getOptionalValue("repository.pg-host." + repository, String.class)
                .map(String::trim)
                .filter(h -> !h.isEmpty());
        if (host.isEmpty()) return Optional.empty();
        int port = config.getOptionalValue("repository.pg-port." + repository, Integer.class).orElse(5432);
        return Optional.of(host.get().toLowerCase(Locale.ROOT) + ":" + port);
    }

    /**
     * The acting repository first, then every other allowed repository on the same server.
     * A repository without a PG host is its own only sibling; a null repository has none.
     */
    public List<String> siblings(String repository) {
        return siblings(repository, List.of());
    }

    /**
     * Like {@link #siblings(String)}, also considering {@code knownRepositories}: repositories
     * that still have a PG host configured but were dropped from the allowed list keep sharing
     * the rows they hold, so metadata is not orphaned by that config edit.
     */
    public List<String> siblings(String repository, Collection<String> knownRepositories) {
        if (repository == null || repository.isBlank()) return List.of();
        Optional<String> key = serverKey(repository);
        if (key.isEmpty()) return List.of(repository);
        List<String> result = new ArrayList<>();
        result.add(repository);
        for (String other : candidates(knownRepositories)) {
            if (other.equalsIgnoreCase(repository) || result.stream().anyMatch(other::equalsIgnoreCase)) continue;
            if (key.equals(serverKey(other))) result.add(other);
        }
        return List.copyOf(result);
    }

    /** Server key to the repositories on it, allowed ones first in {@code allowed.run.repositories} order. */
    public Map<String, List<String>> siblingGroups() {
        return siblingGroups(List.of());
    }

    public Map<String, List<String>> siblingGroups(Collection<String> knownRepositories) {
        Map<String, List<String>> groups = new LinkedHashMap<>();
        for (String repository : candidates(knownRepositories)) {
            serverKey(repository).ifPresent(key -> groups.computeIfAbsent(key, k -> new ArrayList<>()).add(repository));
        }
        return groups;
    }

    private List<String> candidates(Collection<String> knownRepositories) {
        List<String> result = new ArrayList<>(allowedRepositoryResolver.getAllowed());
        if (knownRepositories != null) {
            for (String known : knownRepositories) {
                if (known != null && !known.isBlank() && result.stream().noneMatch(known::equalsIgnoreCase)) {
                    result.add(known);
                }
            }
        }
        return result;
    }
}
