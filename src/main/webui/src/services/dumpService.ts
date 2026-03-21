import type { DatabaseDump } from '../types'

const API = '/api/database/dumps/'

export async function isDumpEnabled(): Promise<boolean> {
  const res = await fetch(API + 'enabled')
  if (!res.ok) return false
  return res.json()
}

export async function listDumps(): Promise<DatabaseDump[]> {
  const res = await fetch(API + 'list')
  if (!res.ok) return []
  return res.json()
}

export function uploadDump(
  file: File,
  uploadPassword: string,
  options?: { databaseName?: string; version?: string; expiresAt?: string; description?: string },
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

export async function deleteDumpsBulk(
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

export async function getStorageInfo(): Promise<{ totalBytes: number; fileCount: number; maxBytes: number }> {
  const res = await fetch(API + 'storage-info')
  if (!res.ok) return { totalBytes: 0, fileCount: 0, maxBytes: 0 }
  return res.json()
}

export interface ActiveRestore {
  repository: string
  targetDatabase: string
  dumpFilename: string
}

export async function getActiveRestores(): Promise<ActiveRestore[]> {
  const res = await fetch(API + 'restore/active')
  if (!res.ok) return []
  return res.json()
}

export async function cancelRestore(repository: string, targetDatabase: string): Promise<boolean> {
  const res = await fetch(API + 'restore/cancel', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ repository, targetDatabase }),
  })
  return res.ok
}

export async function getDumpRepositories(): Promise<string[]> {
  const res = await fetch(API + 'repositories')
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

export async function updateDumpMetadata(
  id: string,
  version: string,
  databaseName: string,
  operationsPassword: string,
  description?: string,
): Promise<{ success: boolean; error?: string }> {
  const res = await fetch(API + 'metadata/' + encodeURIComponent(id), {
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

export async function cleanupIdleDumps(password: string, minDays: number): Promise<{ success: boolean; deleted?: number; error?: string }> {
  const res = await fetch(API + 'cleanup-idle', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ password, minDays }),
  })
  const data = await res.json().catch(() => ({}))
  if (!res.ok) return { success: false, error: data.error || res.statusText }
  return { success: true, deleted: data.deleted }
}

export async function getPostRestoreScripts(repository: string): Promise<PostRestoreScriptsResponse> {
  const res = await fetch(API + 'post-restore-scripts?repository=' + encodeURIComponent(repository))
  if (!res.ok) return { enabled: false, mandatory: [], optional: [], onFailure: 'stop' }
  return res.json()
}
