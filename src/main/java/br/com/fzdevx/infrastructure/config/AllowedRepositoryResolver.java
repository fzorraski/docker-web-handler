package br.com.fzdevx.infrastructure.config;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

// ✦ CLEAN — extracted duplicated allowed-repositories parsing from 4 classes
@ApplicationScoped
public class AllowedRepositoryResolver {

    @Inject
    @ConfigProperty(name = "allowed.run.repositories")
    Optional<String> allowedRunRepositories;

    public List<String> getAllowed() {
        return allowedRunRepositories
                .filter(s -> !s.isBlank())
                .map(s -> Arrays.stream(s.split(","))
                        .map(String::trim)
                        .filter(t -> !t.isEmpty())
                        .toList())
                .orElse(Collections.emptyList());
    }

    public boolean isAllowed(String repository) {
        return getAllowed().contains(repository);
    }
}
