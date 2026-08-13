package br.com.fzdevx.domain.model;

import java.util.Set;

/**
 * What kind of work an audit action represents, so activity reports can rank
 * users on what they actually did rather than on raw event counts.
 *
 * <p>Signing in is not an accomplishment: a user who logs in twenty times would
 * otherwise outrank one who upgraded three containers. {@link #AUTH} is kept
 * separate for exactly that reason - the ranking sums the operational
 * categories and reports authentication alongside, never inside, the score.</p>
 *
 * <p>Classification is by prefix, so an action added later lands in the right
 * bucket without touching this enum; anything unrecognised becomes
 * {@link #OTHER} rather than being dropped, since a report that silently omits
 * events is worse than one with an "other" slice.</p>
 */
public enum ActivityCategory {

    /** Sign-in, sign-out and self-service password changes. Never scored. */
    AUTH,
    /** Interactive shell sessions and file transfers into containers. */
    TERMINAL,
    /** Containers, images, schedules and expirations. */
    CONTAINER,
    /** Databases, dumps and snapshots. */
    DATABASE,
    /** Users, roles, tenants, settings - administration of the app itself. */
    ADMIN,
    /** Anything not recognised by the rules above. */
    OTHER;

    /**
     * Actions that belong to authentication despite not sharing a prefix with
     * each other. Matched exactly, so {@code USER_PASSWORD_RESET} - an admin
     * acting on someone else's account - stays administrative.
     */
    private static final Set<String> AUTH_ACTIONS =
            Set.of("LOGIN", "LOGIN_FAILED", "LOGOUT", "PASSWORD_CHANGE");

    /** The category of an audit action; null or blank reads as {@link #OTHER}. */
    public static ActivityCategory of(String action) {
        if (action == null || action.isBlank()) {
            return OTHER;
        }
        String name = action.trim().toUpperCase(java.util.Locale.ROOT);
        if (AUTH_ACTIONS.contains(name) || name.startsWith("SESSION")) {
            return AUTH;
        }
        if (name.startsWith("TERMINAL")) {
            return TERMINAL;
        }
        if (name.startsWith("CONTAINER") || name.startsWith("IMAGE")
                || name.startsWith("SCHEDULE") || name.startsWith("EXPIRATION")) {
            return CONTAINER;
        }
        if (name.startsWith("DATABASE") || name.startsWith("DUMP")
                || name.startsWith("SNAPSHOT") || name.startsWith("QUERY")) {
            return DATABASE;
        }
        if (name.startsWith("USER") || name.startsWith("ROLE") || name.startsWith("TENANT")
                || name.startsWith("SETTINGS") || name.startsWith("AUDIT")) {
            return ADMIN;
        }
        return OTHER;
    }

    /** Whether events in this category count towards a user's activity score. */
    public boolean operational() {
        return this != AUTH;
    }

    /**
     * Whether the action records something that did not succeed. Drives the
     * security panel, which is mostly about repeated failed sign-ins.
     */
    public static boolean failure(String action) {
        if (action == null) {
            return false;
        }
        String name = normalize(action);
        return name.endsWith("_FAILED") || name.contains("DENIED");
    }

    /**
     * A rejected sign-in. Normalized like every other predicate here, so a
     * variant-cased action can never pass {@link #failure} yet dodge the
     * sign-in handling that depends on this one.
     */
    public static boolean rejectedSignIn(String action) {
        return action != null && "LOGIN_FAILED".equals(normalize(action));
    }

    /** A successful sign-in. */
    public static boolean signIn(String action) {
        return action != null && "LOGIN".equals(normalize(action));
    }

    private static String normalize(String action) {
        return action.trim().toUpperCase(java.util.Locale.ROOT);
    }
}
