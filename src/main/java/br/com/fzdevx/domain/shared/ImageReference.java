package br.com.fzdevx.domain.shared;

/**
 * Helpers for parsing Docker image references such as {@code nginx},
 * {@code nginx:1.27}, {@code registry.example.com:5000/team/app:1.0}, or
 * {@code repo@sha256:...}.
 */
public final class ImageReference {

    private ImageReference() {
    }

    /**
     * Returns the repository part of an image reference — stripping any {@code :tag}
     * or {@code @digest} suffix while preserving a {@code host:port/} registry prefix
     * (a colon before the last slash is a registry port, not a tag separator).
     *
     * @return the repository, or {@code null} if {@code image} is null.
     */
    public static String repository(String image) {
        if (image == null) {
            return null;
        }
        String ref = image.trim();
        int at = ref.indexOf('@');
        if (at > 0) {
            ref = ref.substring(0, at);
        }
        int colon = ref.lastIndexOf(':');
        int slash = ref.lastIndexOf('/');
        if (colon > slash) {
            ref = ref.substring(0, colon);
        }
        return ref;
    }

    /**
     * Returns the last path segment of the repository — e.g.
     * {@code registry/team/app:1} → {@code app}.
     *
     * @return the short name, or {@code null} if {@code image} is null.
     */
    public static String shortName(String image) {
        String repo = repository(image);
        if (repo == null) {
            return null;
        }
        int slash = repo.lastIndexOf('/');
        return slash >= 0 ? repo.substring(slash + 1) : repo;
    }
}
