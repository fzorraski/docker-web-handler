package br.com.fzdevx.domain.shared;

import java.util.List;

/**
 * Matches an image reference against a configured list of image entries, as
 * used by {@code protected.images} and {@code hidden.images}.
 *
 * <p>An entry without a tag (e.g. {@code nginx}) matches every tag of that
 * image; an entry with a tag (e.g. {@code nginx:1.27}) matches only that exact
 * tag. A registry/namespace-qualified image also matches by its short
 * (last-segment) name, mirroring the {@code repository.port-paths} fallback.</p>
 */
public final class ImageMatcher {

    private ImageMatcher() {
    }

    /** Whether the list holds at least one non-blank entry. */
    public static boolean hasEntries(List<String> entries) {
        return entries != null && entries.stream().anyMatch(s -> s != null && !s.isBlank());
    }

    /** Whether {@code image} matches any entry of the list. */
    public static boolean matches(List<String> entries, String image) {
        if (entries == null || image == null || image.isBlank()) {
            return false;
        }
        String img = image.trim();
        String imgRepo = repository(img);
        String imgShort = shortName(img);
        for (String raw : entries) {
            if (raw == null) continue;
            String entry = raw.trim();
            if (entry.isEmpty()) continue;
            if (img.equals(entry)) {
                return true; // exact match including tag
            }
            // "has no tag" must not be a bare contains(':') test — a colon also
            // appears in a registry port (registry.example.com:5000/app)
            if (entry.equals(repository(entry))) {
                // entry has no tag — match any tag of this repository (full or short name)
                if (entry.equals(imgRepo) || entry.equals(imgShort)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String repository(String image) {
        String repo = ImageReference.repository(image);
        return repo == null ? "" : repo;
    }

    private static String shortName(String image) {
        String name = ImageReference.shortName(image);
        return name == null ? "" : name;
    }
}
