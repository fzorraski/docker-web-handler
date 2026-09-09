/**
 * Returns a MUI color for a last-used date chip based on how recent the usage was.
 * @param lastUsedAt Date string in dd/MM/yyyy HH:mm:ss format, or ISO 8601, or null
 * @param isActive Whether the item is currently active/in use
 */
export type LastUsedColor = 'success' | 'info' | 'warning' | 'error' | 'secondary' | 'default'

export function getLastUsedColor(lastUsedAt: string | null | undefined, isActive?: boolean, fallbackInUse?: boolean): LastUsedColor {
  if (isActive) return 'success'
  // If a fallback signal says "currently in use" (e.g. has containers attached),
  // ignore the age of the last-used date — a database with running containers
  // should never render as red/warning just because pg_stat_activity hasn't
  // captured fresh traffic recently. Secondary (purple) is used for this state
  // so it cannot be confused with the "7-30 days" blue of the age scale.
  if (fallbackInUse) return 'secondary'
  if (!lastUsedAt) return 'default'

  let date: Date

  // Try dd/MM/yyyy HH:mm:ss format first
  const parts = lastUsedAt.match(/^(\d{2})\/(\d{2})\/(\d{4}) (\d{2}):(\d{2}):(\d{2})$/)
  if (parts) {
    date = new Date(+parts[3], +parts[2] - 1, +parts[1], +parts[4], +parts[5], +parts[6])
  } else {
    // Try ISO 8601 format
    date = new Date(lastUsedAt)
    if (isNaN(date.getTime())) return 'default'
  }

  const daysAgo = (Date.now() - date.getTime()) / (1000 * 60 * 60 * 24)

  if (daysAgo < 7) return 'success'
  if (daysAgo < 30) return 'info'
  if (daysAgo < 60) return 'warning'
  return 'error'
}

/**
 * Returns a display label for a last-used date.
 *
 * @param fallback Optional pair of (flag, label). When `lastUsedAt` would
 *                 otherwise be `neverLabel`, if `fallback.condition` is true the
 *                 `fallback.label` is returned instead. Used by the databases
 *                 tab to say "In use by container" when a database has an
 *                 associated container even though no PG activity has been
 *                 captured yet.
 */
export function getLastUsedLabel(
  lastUsedAt: string | null | undefined,
  isActive: boolean,
  activeLabel: string,
  neverLabel: string,
  fallback?: { condition: boolean, label: string }
): string {
  if (isActive) return activeLabel
  if (!lastUsedAt) {
    if (fallback?.condition) return fallback.label
    return neverLabel
  }

  // If ISO 8601, format to local display
  if (lastUsedAt.includes('T') || lastUsedAt.includes('Z')) {
    const d = new Date(lastUsedAt)
    if (!isNaN(d.getTime())) {
      return d.toLocaleDateString() + ' ' + d.toLocaleTimeString()
    }
  }

  return lastUsedAt
}
