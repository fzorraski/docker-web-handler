import dayjs from 'dayjs'
import type { DatabaseDump } from '../types'

export function formatBytes(bytes: number): string {
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / (1024 * 1024)).toFixed(2) + ' MB'
}

export function formatDate(iso: string): string {
  const d = dayjs(iso)
  return d.isValid() ? d.format('L LT') : iso
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
