/**
 * Returns a MUI color for a last-used date chip based on how recent the usage was.
 * @param lastUsedAt Date string in dd/MM/yyyy HH:mm:ss format, or ISO 8601, or null
 * @param isActive Whether the item is currently active/in use
 */
export function getLastUsedColor(lastUsedAt: string | null | undefined, isActive?: boolean): 'success' | 'info' | 'warning' | 'error' | 'default' {
  if (isActive) return 'success'
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
 */
export function getLastUsedLabel(lastUsedAt: string | null | undefined, isActive: boolean, activeLabel: string, neverLabel: string): string {
  if (isActive) return activeLabel
  if (!lastUsedAt) return neverLabel

  // If ISO 8601, format to local display
  if (lastUsedAt.includes('T') || lastUsedAt.includes('Z')) {
    const d = new Date(lastUsedAt)
    if (!isNaN(d.getTime())) {
      return d.toLocaleDateString() + ' ' + d.toLocaleTimeString()
    }
  }

  return lastUsedAt
}
