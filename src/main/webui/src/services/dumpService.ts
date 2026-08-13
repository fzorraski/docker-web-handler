import type { DatabaseDump } from '../types'
import fetchWithAuth from './fetchWithAuth'

const API = '/api/database/dumps/'

export async function isDumpEnabled(): Promise<boolean> {
  const res = await fetchWithAuth(API + 'enabled')
  if (!res.ok) return false
  return res.json()
}

export async function listDumps(): Promise<DatabaseDump[]> {
  const res = await fetchWithAuth(API + 'list')
  if (!res.ok) return []
  return res.json()
}

export function uploadDump(
  file: File,
  uploadPassword: string,
  options?: { databaseName?: string; version?: string; expiresAt?: string; description?: string; tenantId?: string; noTenant?: boolean; sharedWithTenants?: string[] },
  onProgress?: (percent: number) => void,
): Promise<{ success: boolean; dump?: DatabaseDump; error?: string }> {
  return new Promise((resolve) => {
    const formData = new FormData()
    formData.append('file', file)
    formData.append('password', uploadPassword)
    if (options?.databaseName) formData.append('databaseName', options.databaseName)
    if (options?.version) formData.append('version', options.version)
    if (options?.expiresAt) formData.append('expiresAt', options.expiresAt)
    if (options?.description) formData.append('description', options.description)
    if (options?.tenantId) formData.append('tenantId', options.tenantId)
    if (options?.noTenant) formData.append('noTenant', 'true')
    if (options?.sharedWithTenants?.length) formData.append('sharedWithTenants', options.sharedWithTenants.join(','))

    const xhr = new XMLHttpRequest()

    xhr.upload.addEventListener('progress', (e) => {
      if (e.lengthComputable && onProgress) {
        onProgress(Math.round((e.loaded / e.total) * 100))
      }
    })

    xhr.addEventListener('load', () => {
      try {
        const data = JSON.parse(xhr.responseText)
        if (xhr.status >= 200 && xhr.status < 300) {
          resolve({ success: true, dump: data })
        } else {
          resolve({ success: false, error: data.error || 'Upload failed' })
        }
      } catch {
        resolve({ success: false, error: 'Upload failed' })
      }
    })

    xhr.addEventListener('error', () => {
      resolve({ success: false, error: 'Network error during upload' })
    })

    xhr.addEventListener('abort', () => {
      resolve({ success: false, error: 'Upload cancelled' })
    })

    xhr.open('POST', API + 'upload')
    xhr.send(formData)
  })
}

export async function deleteDump(
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

export async function deleteDumpsBulk(
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

export async function getStorageInfo(): Promise<{ totalBytes: number; fileCount: number; maxBytes: number }> {
  const res = await fetchWithAuth(API + 'storage-info')
  if (!res.ok) return { totalBytes: 0, fileCount: 0, maxBytes: 0 }
  return res.json()
}

export interface ActiveRestore {
  repository: string
  targetDatabase: string
  dumpFilename: string
  /** Username that triggered it, 'system' for a scheduled run, null with RBAC off. */
  startedBy: string | null
  /** Owning tenant, or null when the restore belongs to none. */
  tenantId: string | null
}

export async function getActiveRestores(): Promise<ActiveRestore[]> {
  const res = await fetchWithAuth(API + 'restore/active')
  if (!res.ok) return []
  return res.json()
}

export async function cancelRestore(repository: string, targetDatabase: string): Promise<boolean> {
  const res = await fetchWithAuth(API + 'restore/cancel', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ repository, targetDatabase }),
  })
  return res.ok
}

export async function getDumpRepositories(): Promise<string[]> {
  const res = await fetchWithAuth(API + 'repositories')
  if (!res.ok) return []
  return res.json()
}

export interface PostRestoreScriptInfo {
  filename: string
  sortOrder: number
  fileSize: number
}

export interface PostRestoreScriptsResponse {
  enabled: boolean
  mandatory: PostRestoreScriptInfo[]
  optional: PostRestoreScriptInfo[]
  onFailure: string
}

export async function updateDumpExpiration(
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

export async function updateDumpMetadata(
  id: string,
  version: string,
  databaseName: string,
  operationsPassword: string,
  description?: string,
): Promise<{ success: boolean; error?: string }> {
  const res = await fetchWithAuth(API + 'metadata/' + encodeURIComponent(id), {
    method: 'PUT',
    headers: {
      'Content-Type': 'application/json',
      'X-Dump-Password': operationsPassword,
    },
    body: JSON.stringify({ version, databaseName, description }),
  })
  if (!res.ok) {
    const data = await res.json().catch(() => ({}))
    return { success: false, error: data.error || res.statusText }
  }
  return { success: true }
}

export async function updateDumpSharing(
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

export async function cleanupIdleDumps(password: string, minDays: number): Promise<{ success: boolean; deleted?: number; error?: string }> {
  const res = await fetchWithAuth(API + 'cleanup-idle', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ password, minDays }),
  })
  const data = await res.json().catch(() => ({}))
  if (!res.ok) return { success: false, error: data.error || res.statusText }
  return { success: true, deleted: data.deleted }
}

export async function getPostRestoreScripts(repository: string): Promise<PostRestoreScriptsResponse> {
  const res = await fetchWithAuth(API + 'post-restore-scripts?repository=' + encodeURIComponent(repository))
  if (!res.ok) return { enabled: false, mandatory: [], optional: [], onFailure: 'stop' }
  return res.json()
}
