package br.com.fzdevx.infrastructure.config;

import br.com.fzdevx.application.port.TenantRepository;
import br.com.fzdevx.domain.exception.InvalidInputException;
import br.com.fzdevx.domain.model.auth.Tenant;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The shared-with side of tenant visibility: normalising the list a caller
 * asked for, and checking that every id on it exists.
 *
 * <p>{@link TenantVisibility} answers "may this actor see it"; this answers
 * "what should be stored". They are separate because the store side runs once
 * at creation while the read side runs on every request.</p>
 *
 * <p>The comma-separated form is not a style choice: multipart fields carry no
 * type, and a docker label is a single string, so both containers and dump
 * uploads have to flatten the list.</p>
 */
@ApplicationScoped
public class TenantSharing {

    private static final String SEPARATOR = ",";

    /**
     * Far above any real tenant count, far below what makes the O(ids) work
     * here a lever - without it an authenticated caller could post arbitrarily
     * large arrays into the normalise/validate path.
     */
    static final int MAX_SHARE_TARGETS = 100;

    @Inject
    TenantRepository tenantRepository;

    /**
     * Trims, drops blanks and duplicates, and removes the owning tenant - which
     * already sees the resource, and whose id on the shared list would be stored
     * verbatim and outlive any later change of owner.
     */
    public List<String> normalize(List<String> tenantIds, String ownerTenantId) {
        if (tenantIds == null || tenantIds.isEmpty()) {
            return List.of();
        }
        if (tenantIds.size() > MAX_SHARE_TARGETS) {
            throw new InvalidInputException("Too many shared tenants.");
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String tenantId : tenantIds) {
            if (tenantId == null) continue;
            String trimmed = tenantId.trim();
            if (trimmed.isEmpty() || trimmed.equals(ownerTenantId)) continue;
            normalized.add(trimmed);
        }
        return List.copyOf(normalized);
    }

    /** As {@link #normalize}, from the comma-separated form. */
    public List<String> parse(String csv, String ownerTenantId) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return normalize(Arrays.asList(csv.split(SEPARATOR)), ownerTenantId);
    }

    /** The comma-separated form, or null when there is nothing to store. */
    public static String toCsv(List<String> tenantIds) {
        return tenantIds == null || tenantIds.isEmpty() ? null : String.join(SEPARATOR, tenantIds);
    }

    /** The raw JSON array as strings; everything else happens in {@link #normalize}. */
    public static List<String> asStrings(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        if (list.size() > MAX_SHARE_TARGETS) {
            throw new InvalidInputException("Too many shared tenants.");
        }
        return list.stream()
                .filter(java.util.Objects::nonNull)
                .map(Object::toString)
                .toList();
    }

    /**
     * The first id that does not reference an existing tenant, if any. One
     * repository read for the whole list - per-id lookups would re-read the
     * backing JSON file (or re-query Postgres) once per element.
     */
    public Optional<String> firstUnknown(List<String> tenantIds) {
        if (tenantIds == null || tenantIds.isEmpty()) {
            return Optional.empty();
        }
        Set<String> known = tenantRepository.findAll().stream()
                .map(Tenant::getId)
                .collect(Collectors.toSet());
        return tenantIds.stream()
                .filter(tenantId -> !known.contains(tenantId))
                .findFirst();
    }
}
