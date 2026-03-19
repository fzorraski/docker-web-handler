import dayjs from 'dayjs'
import i18n from '../i18n'
import type { DatabaseDump, DatabaseSnapshot } from '../types'

export function formatBytes(bytes: number): string {
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / (1024 * 1024)).toFixed(2) + ' MB'
}

const dateTimeFormat = (locale: string) =>
  new Intl.DateTimeFormat(locale, {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  })

/** Format an ISO date string (e.g. from dump/snapshot timestamps) */
export function formatDate(iso: string): string {
  const d = new Date(iso)
  if (isNaN(d.getTime())) return iso
  return dateTimeFormat(i18n.language).format(d)
}

/** Format a backend date string in dd/MM/yyyy HH:mm:ss format */
export function formatBackendDate(raw: string): string {
  const d = dayjs(raw, 'DD/MM/YYYY HH:mm:ss')
  if (!d.isValid()) return raw
  return dateTimeFormat(i18n.language).format(d.toDate())
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

// eslint-disable-next-line @typescript-eslint/no-explicit-any
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
