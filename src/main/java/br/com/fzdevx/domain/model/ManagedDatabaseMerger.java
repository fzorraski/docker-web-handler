package br.com.fzdevx.domain.model;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * Folds several metadata records for the same physical database into one. Records fork
 * when sibling repositories (same PostgreSQL server) each wrote their own row.
 *
 * <p>Rule: the single record that knows its creator wins the descriptive fields; with no
 * or several creators the oldest record wins. Protection is OR-ed so a flag set through any
 * repository holds. The latest usage and the earliest creation time are kept, and a field
 * the winner does not know is filled from the others without ever overriding the winner.</p>
 */
public final class ManagedDatabaseMerger {

    private ManagedDatabaseMerger() {}

    /**
     * The record whose identity and descriptive fields prevail: any record that knows its
     * creator beats one that does not, then the oldest wins; ties keep list order.
     */
    public static ManagedDatabase winner(List<ManagedDatabase> rows) {
        if (rows == null || rows.isEmpty()) throw new IllegalArgumentException("no rows to merge");
        Comparator<ManagedDatabase> order = Comparator
                .comparing((ManagedDatabase r) -> !notBlank(r.getCreatedBy()))
                .thenComparing(ManagedDatabase::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()));
        return rows.stream().min(order).orElseThrow();
    }

    /** A new record combining {@code rows}; inputs are left untouched. */
    public static ManagedDatabase merge(List<ManagedDatabase> rows) {
        ManagedDatabase winner = winner(rows);
        ManagedDatabase out = copy(winner);
        for (ManagedDatabase other : rows) {
            // by identity: two rows can share repository+name (equals) and still both matter
            if (other == winner) continue;
            fold(out, other);
        }
        return out;
    }

    /**
     * Applies {@link #merge} in place on {@code stored}, keeping its identity. Used when a
     * write for a database lands on the row a sibling repository already holds.
     */
    public static void mergeInto(ManagedDatabase stored, ManagedDatabase incoming) {
        ManagedDatabase merged = merge(List.of(stored, incoming));
        stored.setProtectedFlag(merged.isProtectedFlag());
        stored.setAppLastUsedAt(merged.getAppLastUsedAt());
        stored.setCreatedAt(merged.getCreatedAt());
        stored.setDescription(merged.getDescription());
        stored.setLastRestoredFrom(merged.getLastRestoredFrom());
        stored.setLastRestoredAt(merged.getLastRestoredAt());
        stored.setLastRestoredBy(merged.getLastRestoredBy());
        stored.setCreatedBy(merged.getCreatedBy());
        stored.setTenantId(merged.getTenantId());
    }

    private static void fold(ManagedDatabase out, ManagedDatabase other) {
        out.setProtectedFlag(out.isProtectedFlag() || other.isProtectedFlag());
        out.setAppLastUsedAt(latest(out.getAppLastUsedAt(), other.getAppLastUsedAt()));
        out.setCreatedAt(earliest(out.getCreatedAt(), other.getCreatedAt()));
        if (!notBlank(out.getDescription())) out.setDescription(other.getDescription());
        if (!notBlank(out.getTenantId())) out.setTenantId(other.getTenantId());
        if (!notBlank(out.getCreatedBy())) out.setCreatedBy(other.getCreatedBy());
        if (!notBlank(out.getLastRestoredFrom()) && out.getLastRestoredAt() == null) {
            out.setLastRestoredFrom(other.getLastRestoredFrom());
            out.setLastRestoredAt(other.getLastRestoredAt());
            out.setLastRestoredBy(other.getLastRestoredBy());
        }
    }

    private static ManagedDatabase copy(ManagedDatabase source) {
        ManagedDatabase out = new ManagedDatabase();
        out.setRepository(source.getRepository());
        out.setName(source.getName());
        out.setProtectedFlag(source.isProtectedFlag());
        out.setAppLastUsedAt(source.getAppLastUsedAt());
        out.setCreatedAt(source.getCreatedAt());
        out.setDescription(source.getDescription());
        out.setLastRestoredFrom(source.getLastRestoredFrom());
        out.setLastRestoredAt(source.getLastRestoredAt());
        out.setLastRestoredBy(source.getLastRestoredBy());
        out.setCreatedBy(source.getCreatedBy());
        out.setTenantId(source.getTenantId());
        return out;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static Instant latest(Instant a, Instant b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.isAfter(b) ? a : b;
    }

    private static Instant earliest(Instant a, Instant b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.isBefore(b) ? a : b;
    }
}
