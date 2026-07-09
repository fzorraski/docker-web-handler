import type { DatabaseSnapshot } from '../types'
import fetchWithAuth from './fetchWithAuth'

const API = '/api/database/snapshots/'

export async function listSnapshots(): Promise<DatabaseSnapshot[]> {
  const res = await fetchWithAuth(API + 'list')
  if (!res.ok) return []
  return res.json()
}

export async function deleteSnapshot(
  id: string,
  operationsPassword: string,
): Promise<{ success: boolean; error?: string }> {
  const res = await fetchWithAuth(API + 'delete/' + encodeURIComponent(id), {
    method: 'DELETE',
    headers: { 'X-Dump-Password': operationsPassword },
  })

  if (!res.ok) {
    const data = await res.json().catch(() => ({}))
    return { success: false, error: data.error || res.statusText }
  }

  return { success: true }
}

export async function updateSnapshotSharing(
  id: string,
  sharedWithTenants: string[],
  tenantId: string | null | undefined,
): Promise<{ success: boolean; error?: string }> {
  const body: Record<string, unknown> = { sharedWithTenants }
  if (tenantId !== undefined) body.tenantId = tenantId
  const res = await fetchWithAuth(API + 'sharing/' + encodeURIComponent(id), {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  if (!res.ok) {
    const data = await res.json().catch(() => ({}))
    return { success: false, error: data.error || res.statusText }
  }
  return { success: true }
}

export async function deleteSnapshotsBulk(
  ids: string[],
  operationsPassword: string,
): Promise<{ success: boolean; deleted?: number; error?: string }> {
  const res = await fetchWithAuth(API + 'delete/bulk', {
    method: 'DELETE',
    headers: {
      'Content-Type': 'application/json',
      'X-Dump-Password': operationsPassword,
    },
    body: JSON.stringify(ids),
  })

  if (!res.ok) {
    const data = await res.json().catch(() => ({}))
    return { success: false, error: data.error || res.statusText }
  }

  const data = await res.json()
  return { success: true, deleted: data.deleted }
}

export async function getSnapshotStorageInfo(): Promise<{ totalBytes: number; fileCount: number; maxBytes: number }> {
  const res = await fetchWithAuth(API + 'storage-info')
  if (!res.ok) return { totalBytes: 0, fileCount: 0, maxBytes: 0 }
  return res.json()
}

export async function getSnapshotRepositories(): Promise<string[]> {
  const res = await fetchWithAuth(API + 'repositories')
  if (!res.ok) return []
  return res.json()
}

export interface ActiveSnapshot {
  repository: string
  sourceDatabaseName: string
}

export async function getActiveSnapshots(): Promise<ActiveSnapshot[]> {
  const res = await fetchWithAuth(API + 'active')
  if (!res.ok) return []
  return res.json()
}

export async function cancelSnapshot(repository: string, sourceDatabaseName: string): Promise<boolean> {
  const res = await fetchWithAuth(API + 'cancel', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ repository, sourceDatabaseName }),
  })
  return res.ok
}

export async function updateSnapshotMetadata(
  id: string,
  label: string,
  operationsPassword: string,
  description?: string,
): Promise<{ success: boolean; error?: string }> {
  const res = await fetchWithAuth(API + 'metadata/' + encodeURIComponent(id), {
    method: 'PUT',
    headers: {
      'Content-Type': 'application/json',
      'X-Dump-Password': operationsPassword,
    },
    body: JSON.stringify({ label, description }),
  })
  if (!res.ok) {
    const data = await res.json().catch(() => ({}))
    return { success: false, error: data.error || res.statusText }
  }
  return { success: true }
}

export async function updateSnapshotExpiration(
  id: string,
  expiresAt: string | null,
  operationsPassword: string,
): Promise<{ success: boolean; error?: string }> {
  const res = await fetchWithAuth(API + 'expiration/' + encodeURIComponent(id), {
    method: 'PUT',
    headers: {
      'Content-Type': 'application/json',
      'X-Dump-Password': operationsPassword,
    },
    body: JSON.stringify({ expiresAt }),
  })
  if (!res.ok) {
    const data = await res.json().catch(() => ({}))
    return { success: false, error: data.error || res.statusText }
  }
  return { success: true }
}

export async function cleanupIdleSnapshots(password: string, minDays: number): Promise<{ success: boolean; deleted?: number; error?: string }> {
  const res = await fetchWithAuth(API + 'cleanup-idle', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ password, minDays }),
  })
  const data = await res.json().catch(() => ({}))
  if (!res.ok) return { success: false, error: data.error || res.statusText }
  return { success: true, deleted: data.deleted }
}
