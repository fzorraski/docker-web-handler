import dayjs from 'dayjs'
import i18n from '../i18n'
import type { DatabaseDump, DatabaseSnapshot } from '../types'

/**
 * Compares two version/tag strings in descending order (higher versions first).
 * Splits by '.' or '-', compares segments numerically when possible, lexically otherwise.
 */
export function compareTagsDesc(a: string, b: string): number {
  const partsA = a.split(/[.\-]/)
  const partsB = b.split(/[.\-]/)
  const len = Math.max(partsA.length, partsB.length)
  for (let i = 0; i < len; i++) {
    const na = Number(partsA[i] ?? '')
    const nb = Number(partsB[i] ?? '')
    if (!isNaN(na) && !isNaN(nb)) {
      if (nb !== na) return nb - na
    } else {
      const cmp = (partsA[i] ?? '').localeCompare(partsB[i] ?? '')
      if (cmp !== 0) return cmp
    }
  }
  return 0
}

/**
 * Ellipsis-truncation for table cells that can receive unbounded values
 * (an image tag may be a full digest). Returns the value untouched when it
 * fits, so callers can use the result to decide whether a tooltip is needed.
 */
export function truncate(value: string, max: number): string {
  return value.length <= max ? value : value.slice(0, max - 1) + '\u2026'
}

export function formatBytes(bytes: number): string {
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  if (bytes < 1024 * 1024 * 1024) return (bytes / (1024 * 1024)).toFixed(2) + ' MB'
  return (bytes / (1024 * 1024 * 1024)).toFixed(2) + ' GB'
}

export function formatBytesRate(bytesPerSec: number): string {
  return formatBytes(bytesPerSec) + '/s'
}

const _fmtCache = new Map<string, Intl.DateTimeFormat>()
function cachedFormat(key: string, locale: string, options: Intl.DateTimeFormatOptions): Intl.DateTimeFormat {
  const cacheKey = `${key}:${locale}`
  let fmt = _fmtCache.get(cacheKey)
  if (!fmt) {
    fmt = new Intl.DateTimeFormat(locale, options)
    _fmtCache.set(cacheKey, fmt)
  }
  return fmt
}

const dateTimeOpts: Intl.DateTimeFormatOptions = {
  year: 'numeric', month: '2-digit', day: '2-digit',
  hour: '2-digit', minute: '2-digit',
}

const logTimestampOpts = {
  year: 'numeric', month: '2-digit', day: '2-digit',
  hour: '2-digit', minute: '2-digit', second: '2-digit',
  fractionalSecondDigits: 3,
} as Intl.DateTimeFormatOptions

const logTimestampShortOpts: Intl.DateTimeFormatOptions = {
  year: 'numeric', month: '2-digit', day: '2-digit',
  hour: '2-digit', minute: '2-digit', second: '2-digit',
}

/** Format an ISO date string (e.g. from dump/snapshot timestamps) */
export function formatDate(iso: string): string {
  const d = new Date(iso)
  if (isNaN(d.getTime())) return iso
  return cachedFormat('dt', i18n.language, dateTimeOpts).format(d)
}

/**
 * Format a log timestamp (ISO-like, e.g. "2026-04-15T07:02:02.018") using the current locale.
 * Includes seconds and milliseconds for log precision.
 */
export function formatLogTimestamp(iso: string | null | undefined): string {
  if (!iso) return ''
  const d = new Date(iso)
  if (isNaN(d.getTime())) return iso
  return cachedFormat('logTs', i18n.language, logTimestampOpts).format(d)
}

/** Same as formatLogTimestamp but without milliseconds — for shorter displays. */
export function formatLogTimestampShort(iso: string | null | undefined): string {
  if (!iso) return ''
  const d = new Date(iso)
  if (isNaN(d.getTime())) return iso
  return cachedFormat('logTsShort', i18n.language, logTimestampShortOpts).format(d)
}

/**
 * Parse the backend's display-format date ('dd/MM/yyyy HH:mm:ss'). The single
 * definition of that format contract - sorting, idle-day math and display all
 * go through here, so a backend format change breaks one function, not three
 * scattered copies.
 */
export function parseBackendDate(raw: string): dayjs.Dayjs {
  return dayjs(raw, 'DD/MM/YYYY HH:mm:ss')
}

/** Format a backend date string in dd/MM/yyyy HH:mm:ss format */
export function formatBackendDate(raw: string): string {
  const d = parseBackendDate(raw)
  if (!d.isValid()) return raw
  return cachedFormat('dt', i18n.language, dateTimeOpts).format(d.toDate())
}

export function buildTargetDbName(dump: DatabaseDump): string {
  const parts: string[] = []
  if (dump.databaseName) parts.push(dump.databaseName)
  if (dump.version) parts.push(dump.version)
  if (dump.uploadedAt) {
    const d = new Date(dump.uploadedAt)
    if (!isNaN(d.getTime())) {
      parts.push(d.toISOString().slice(0, 10))
    }
  }
  const raw = parts.length > 0 ? parts.join('_') : ''
  return raw.replace(/[^a-zA-Z0-9_-]/g, '_')
}

export function buildSnapshotTargetDbName(snapshot: DatabaseSnapshot): string {
  const suffix = dayjs().format('YYYYMMDDHHmmss')
  const base = snapshot.sourceDatabaseName.replace(/[^a-zA-Z0-9_-]/g, '_')
  return `${base}_restore_${suffix}`
}

export function formatScriptSize(bytes: number): string {
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / (1024 * 1024)).toFixed(1) + ' MB'
}

// eslint-disable-next-line @typescript-eslint/no-explicit-any -- i18next TFunction has complex overloads that don't match simple callable signatures
export function formatMigrationSummary(
  config: { mode: string; sql?: string; sourceVersion?: string; targetVersion?: string },
  t: (...args: any[]) => string,
  section: string,
): string {
  const versionInfo = config.sourceVersion && config.targetVersion
    ? ` (${config.sourceVersion} \u2192 ${config.targetVersion})` : ''
  return config.mode === 'MANUAL'
    ? t(`${section}.migrationManualMode`, { chars: (config.sql?.length ?? 0).toString() }) + versionInfo
    : t(`${section}.migrationApiMode`) + versionInfo
}

/**
 * Relative time for audit/event feeds ("2 minutes ago"); falls back to the
 * absolute date beyond 7 days. Pair with a tooltip showing formatDate(iso).
 */
export function formatRelative(iso: string | null | undefined): string {
  if (!iso) return '-'
  const d = new Date(iso)
  if (isNaN(d.getTime())) return iso
  const seconds = Math.round((Date.now() - d.getTime()) / 1000)
  if (seconds < 0 || seconds > 7 * 24 * 3600) return formatDate(iso)
  const rtf = new Intl.RelativeTimeFormat(i18n.language, { numeric: 'auto' })
  if (seconds < 60) return rtf.format(-seconds, 'second')
  if (seconds < 3600) return rtf.format(-Math.round(seconds / 60), 'minute')
  if (seconds < 24 * 3600) return rtf.format(-Math.round(seconds / 3600), 'hour')
  return rtf.format(-Math.round(seconds / (24 * 3600)), 'day')
}
