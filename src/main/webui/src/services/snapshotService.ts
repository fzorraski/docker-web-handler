import type { DatabaseSnapshot } from '../types'

const API = '/api/database/snapshots/'

export async function listSnapshots(): Promise<DatabaseSnapshot[]> {
  const res = await fetch(API + 'list')
  if (!res.ok) return []
  return res.json()
}

export async function deleteSnapshot(
  id: string,
  operationsPassword: string,
): Promise<{ success: boolean; error?: string }> {
  const res = await fetch(API + 'delete/' + encodeURIComponent(id), {
    method: 'DELETE',
    headers: { 'X-Dump-Password': operationsPassword },
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
  const res = await fetch(API + 'delete/bulk', {
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
  const res = await fetch(API + 'storage-info')
  if (!res.ok) return { totalBytes: 0, fileCount: 0, maxBytes: 0 }
  return res.json()
}

export async function getSnapshotRepositories(): Promise<string[]> {
  const res = await fetch(API + 'repositories')
  if (!res.ok) return []
  return res.json()
}

export interface ActiveSnapshot {
  repository: string
  sourceDatabaseName: string
}

export async function getActiveSnapshots(): Promise<ActiveSnapshot[]> {
  const res = await fetch(API + 'active')
  if (!res.ok) return []
  return res.json()
}

export async function cancelSnapshot(repository: string, sourceDatabaseName: string): Promise<boolean> {
  const res = await fetch(API + 'cancel', {
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
  const res = await fetch(API + 'metadata/' + encodeURIComponent(id), {
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
  const res = await fetch(API + 'expiration/' + encodeURIComponent(id), {
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

export function downloadSnapshotDirect(body: {
  repository: string
  sourceDatabaseName: string
  format: string
  label?: string
  password: string
  containerName?: string
}): void {
  const form = document.createElement('form')
  form.method = 'POST'
  form.action = API + 'download-direct'
  form.style.display = 'none'

  // We need to use fetch for JSON body, so use a hidden iframe approach
  // Actually, let's use fetch with blob download
  fetch(API + 'download-direct', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
    .then((res) => {
      if (!res.ok) throw new Error('Download failed')
      const disposition = res.headers.get('Content-Disposition')
      let filename = 'snapshot.dump'
      if (disposition) {
        const match = disposition.match(/filename="?([^"]+)"?/)
        if (match) filename = match[1]
      }
      return res.blob().then((blob) => ({ blob, filename }))
    })
    .then(({ blob, filename }) => {
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = filename
      document.body.appendChild(a)
      a.click()
      document.body.removeChild(a)
      URL.revokeObjectURL(url)
    })
    .catch((err) => {
      console.error('Download failed:', err)
    })
}

export async function cleanupIdleSnapshots(password: string, minDays: number): Promise<{ success: boolean; deleted?: number; error?: string }> {
  const res = await fetch(API + 'cleanup-idle', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ password, minDays }),
  })
  const data = await res.json().catch(() => ({}))
  if (!res.ok) return { success: false, error: data.error || res.statusText }
  return { success: true, deleted: data.deleted }
}
