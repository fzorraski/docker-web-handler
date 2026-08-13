import fetchWithAuth from './fetchWithAuth'

export interface ContainerEvent {
  type: 'INFO' | 'PROGRESS' | 'SUCCESS' | 'ERROR'
  step: string
  message: string
  progress?: number
  detail?: string
}

function streamSse(
  url: string,
  onEvent: (event: ContainerEvent) => void,
  onDone: (event: ContainerEvent) => void,
  onError: (message: string) => void,
): () => void {
  const es = new EventSource(url)

  es.onmessage = (e) => {
    try {
      const event: ContainerEvent = JSON.parse(e.data)
      onEvent(event)

      if (event.type === 'SUCCESS') {
        es.close()
        onDone(event)
      } else if (event.type === 'ERROR') {
        es.close()
        onError(event.message)
      }
    } catch {
      // ignore parse errors
    }
  }

  es.onerror = () => {
    es.close()
    onError('Connection lost')
  }

  return () => es.close()
}

export async function prepareRunContainer(body: {
  repository: string
  tag: string
  containerName: string
  envVars: string[]
  expiresAt?: string | null
  memoryMb?: number | null
  databaseName?: string | null
  deleteDatabaseOnExpiration?: boolean
  dumpId?: string | null
  snapshotId?: string | null
  createDatabase?: boolean
  selectedOptionalScripts?: string[]
  operationsPassword?: string | null
  migrationMode?: string | null
  migrationSql?: string | null
  migrationSourceVersion?: string | null
  migrationTargetVersion?: string | null
  webhookNotify?: boolean
  tenantId?: string
  noTenant?: boolean
  sharedWithTenants?: string[]
}): Promise<string> {
  const res = await fetchWithAuth('/api/containers/sse/run/prepare', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  if (!res.ok) {
    const data = await res.json().catch(() => ({}))
    throw new Error(data.error || res.statusText)
  }
  const data = await res.json()
  return data.ticket
}

export function streamRunContainer(
  ticket: string,
  onEvent: (event: ContainerEvent) => void,
  onDone: (event: ContainerEvent) => void,
  onError: (message: string) => void,
): () => void {
  return streamSse(`/api/containers/sse/run/${ticket}`, onEvent, onDone, onError)
}

export async function cancelRunContainer(ticket: string): Promise<boolean> {
  const res = await fetchWithAuth(`/api/containers/sse/run/cancel/${ticket}`, { method: 'POST' })
  if (!res.ok) return false
  const data = await res.json()
  return data.cancelled
}

export async function prepareRunMigration(body: {
  repository: string
  targetDatabase: string
  password: string
  migrationMode: string
  migrationSql?: string | null
  migrationSourceVersion?: string | null
  migrationTargetVersion?: string | null
}): Promise<string> {
  const res = await fetchWithAuth('/api/containers/sse/migration/prepare', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  if (!res.ok) {
    const data = await res.json().catch(() => ({}))
    throw new Error(data.error || res.statusText)
  }
  const data = await res.json()
  return data.ticket
}

export function streamRunMigration(
  ticket: string,
  onEvent: (event: ContainerEvent) => void,
  onDone: (event: ContainerEvent) => void,
  onError: (message: string) => void,
): () => void {
  return streamSse(`/api/containers/sse/migration/${ticket}`, onEvent, onDone, onError)
}

export async function cancelRunMigration(ticket: string): Promise<boolean> {
  const res = await fetchWithAuth(`/api/containers/sse/migration/cancel/${ticket}`, { method: 'POST' })
  if (!res.ok) return false
  const data = await res.json()
  return data.cancelled
}

export async function prepareUpgradeContainer(body: {
  containerId: string
  newTag?: string | null
  password: string
  migrationMode?: string | null
  migrationSql?: string | null
  migrationSourceVersion?: string | null
  migrationTargetVersion?: string | null
}): Promise<string> {
  const res = await fetchWithAuth('/api/containers/sse/upgrade/prepare', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  if (!res.ok) {
    const data = await res.json().catch(() => ({}))
    throw new Error(data.error || res.statusText)
  }
  const data = await res.json()
  return data.ticket
}

export function streamUpgradeContainer(
  ticket: string,
  onEvent: (event: ContainerEvent) => void,
  onDone: (event: ContainerEvent) => void,
  onError: (message: string) => void,
): () => void {
  return streamSse(`/api/containers/sse/upgrade/${ticket}`, onEvent, onDone, onError)
}

export async function cancelUpgradeContainer(ticket: string): Promise<boolean> {
  const res = await fetchWithAuth(`/api/containers/sse/upgrade/cancel/${ticket}`, { method: 'POST' })
  if (!res.ok) return false
  const data = await res.json()
  return data.cancelled
}

export async function preparePruneImages(body: {
  password: string
  minDays: number
}): Promise<string> {
  const res = await fetchWithAuth('/api/images/sse/prune/prepare', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  if (!res.ok) {
    const data = await res.json().catch(() => ({}))
    throw new Error(data.error || res.statusText)
  }
  const data = await res.json()
  return data.ticket
}

export function streamPruneImages(
  ticket: string,
  onEvent: (event: ContainerEvent) => void,
  onDone: (event: ContainerEvent) => void,
  onError: (message: string) => void,
): () => void {
  return streamSse(`/api/images/sse/prune/${ticket}`, onEvent, onDone, onError)
}

export function streamRemoveImage(
  imageId: string,
  onEvent: (event: ContainerEvent) => void,
  onDone: (event: ContainerEvent) => void,
  onError: (message: string) => void,
): () => void {
  return streamSse(`/api/images/sse/remove/${encodeURIComponent(imageId)}`, onEvent, onDone, onError)
}

export function streamContainerLogs(
  containerId: string,
  onEvent: (event: ContainerEvent) => void,
  onDone: (event: ContainerEvent) => void,
  onError: (message: string) => void,
): () => void {
  return streamSse(`/api/containers/sse/logs/${encodeURIComponent(containerId)}`, onEvent, onDone, onError)
}

export interface ContainerStats {
  cpuPercent: number
  memoryUsage: number
  memoryLimit: number
  memoryPercent: number
  networkRxBytes: number
  networkTxBytes: number
  networkRxRate: number
  networkTxRate: number
  blockReadBytes: number
  blockWriteBytes: number
  pids: number
  timestamp: string
}

export function streamContainerStats(
  containerId: string,
  onStats: (stats: ContainerStats) => void,
  onError: (message: string) => void,
): () => void {
  const es = new EventSource(`/api/containers/sse/stats/${encodeURIComponent(containerId)}`)
  es.onmessage = (e) => {
    try { onStats(JSON.parse(e.data)) } catch { /* ignore parse errors */ }
  }
  es.onerror = () => { es.close(); onError('Connection lost') }
  return () => es.close()
}

export function subscribeContainerUpdates(
  onRefresh: () => void,
  onLocking?: (ids: string[]) => void,
  onUnlocking?: (ids: string[]) => void,
): () => void {
  const es = new EventSource('/api/containers/sse/updates')
  es.addEventListener('refresh', () => onRefresh())
  if (onLocking) {
    es.addEventListener('locking', (e) => {
      try { onLocking(JSON.parse((e as MessageEvent).data)) } catch { /* ignore parse errors */ }
    })
  }
  if (onUnlocking) {
    es.addEventListener('unlocking', (e) => {
      try { onUnlocking(JSON.parse((e as MessageEvent).data)) } catch { /* ignore parse errors */ }
    })
  }
  es.onerror = () => {
    // EventSource auto-reconnects on error; no action needed
  }
  return () => es.close()
}

export function streamRemoveContainer(
  containerId: string,
  onEvent: (event: ContainerEvent) => void,
  onDone: (event: ContainerEvent) => void,
  onError: (message: string) => void,
): () => void {
  return streamSse(`/api/containers/sse/remove/${encodeURIComponent(containerId)}`, onEvent, onDone, onError)
}

export async function prepareRemoveContainer(body: {
  containerId: string
  deleteDatabase: boolean
  repository?: string | null
  databaseName?: string | null
  operationsPassword?: string | null
}): Promise<string> {
  const res = await fetchWithAuth('/api/containers/sse/remove/prepare', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  if (!res.ok) {
    const err = await res.json().catch(() => ({ error: res.statusText }))
    throw new Error(err.error || res.statusText)
  }
  const data = await res.json()
  return data.ticket
}

export function streamRemoveContainerWithTicket(
  ticket: string,
  onEvent: (event: ContainerEvent) => void,
  onDone: (event: ContainerEvent) => void,
  onError: (message: string) => void,
): () => void {
  return streamSse(`/api/containers/sse/remove/ticket/${encodeURIComponent(ticket)}`, onEvent, onDone, onError)
}

export async function prepareRestoreDump(body: {
  dumpId?: string
  snapshotId?: string
  repository: string
  targetDatabase: string
  createDatabase: boolean
  password: string
  selectedOptionalScripts?: string[]
  migrationMode?: string | null
  migrationSql?: string | null
  migrationSourceVersion?: string | null
  migrationTargetVersion?: string | null
  webhookNotify?: boolean
}): Promise<string> {
  const res = await fetchWithAuth('/api/database/dumps/sse/restore/prepare', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  if (!res.ok) {
    const data = await res.json().catch(() => ({}))
    throw new Error(data.error || res.statusText)
  }
  const data = await res.json()
  return data.ticket
}

export function streamRestoreDump(
  ticket: string,
  onEvent: (event: ContainerEvent) => void,
  onDone: (event: ContainerEvent) => void,
  onError: (message: string) => void,
): () => void {
  return streamSse(`/api/database/dumps/sse/restore/${ticket}`, onEvent, onDone, onError)
}

export async function prepareSnapshot(body: {
  repository: string
  sourceDatabaseName: string
  format: string
  label?: string
  description?: string
  expiresAt?: string
  password: string
  containerName?: string
  temporary?: boolean
  tenantId?: string
  noTenant?: boolean
  sharedWithTenants?: string[]
}): Promise<string> {
  const res = await fetchWithAuth('/api/database/snapshots/sse/create/prepare', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  if (!res.ok) {
    const data = await res.json().catch(() => ({}))
    throw new Error(data.error || res.statusText)
  }
  const data = await res.json()
  return data.ticket
}

export function streamSnapshot(
  ticket: string,
  onEvent: (event: ContainerEvent) => void,
  onDone: (event: ContainerEvent) => void,
  onError: (message: string) => void,
): () => void {
  return streamSse(`/api/database/snapshots/sse/create/${ticket}`, onEvent, onDone, onError)
}

// ---- Log Analysis ----

export async function prepareLogAnalysis(
  files: File[],
  options: Record<string, string | undefined>,
  onUploadProgress?: (percent: number) => void,
): Promise<string> {
  const form = new FormData()
  files.forEach((f) => form.append('files', f))
  for (const [key, value] of Object.entries(options)) {
    if (value != null) form.append(key, value)
  }

  if (onUploadProgress) {
    // Use XMLHttpRequest for upload progress tracking
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest()
      xhr.open('POST', '/api/logs/analyzer/sse/upload/prepare')
      xhr.upload.onprogress = (e) => {
        if (e.lengthComputable) onUploadProgress(Math.round((e.loaded / e.total) * 100))
      }
      xhr.onload = () => {
        if (xhr.status === 401) {
          window.dispatchEvent(new CustomEvent('auth:session-expired'))
          reject(new Error('Session expired'))
          return
        }
        if (xhr.status >= 200 && xhr.status < 300) {
          try {
            const data = JSON.parse(xhr.responseText)
            resolve(data.ticket)
          } catch { reject(new Error('Invalid response')) }
        } else {
          try {
            const data = JSON.parse(xhr.responseText)
            reject(new Error(data.error || xhr.statusText))
          } catch { reject(new Error(xhr.statusText)) }
        }
      }
      xhr.onerror = () => reject(new Error('Upload failed'))
      xhr.send(form)
    })
  }

  const res = await fetchWithAuth('/api/logs/analyzer/sse/upload/prepare', { method: 'POST', body: form })
  if (!res.ok) {
    const data = await res.json().catch(() => ({}))
    throw new Error(data.error || res.statusText)
  }
  const data = await res.json()
  return data.ticket
}

export function streamLogAnalysis(
  ticket: string,
  onEvent: (event: ContainerEvent) => void,
  onDone: (event: ContainerEvent) => void,
  onError: (message: string) => void,
): () => void {
  return streamSse(`/api/logs/analyzer/sse/upload/${ticket}`, onEvent, onDone, onError)
}

export async function cancelLogAnalysis(ticket: string): Promise<boolean> {
  const res = await fetchWithAuth(`/api/logs/analyzer/sse/upload/cancel/${ticket}`, { method: 'POST' })
  if (!res.ok) return false
  const data = await res.json()
  return data.cancelled
}

// ── Compose Analysis (SSE) ──────────────────────────────────────────────

export async function prepareComposeAnalysis(body: {
  ids: string[]
  preset?: string
  slowThresholdMs?: number
}): Promise<string> {
  const res = await fetchWithAuth('/api/logs/analyzer/sse/compose/prepare', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  if (!res.ok) {
    const data = await res.json().catch(() => ({}))
    throw new Error(data.error || res.statusText)
  }
  const data = await res.json()
  return data.ticket
}

export function streamComposeAnalysis(
  ticket: string,
  onEvent: (event: ContainerEvent) => void,
  onDone: (event: ContainerEvent) => void,
  onError: (message: string) => void,
): () => void {
  return streamSse(`/api/logs/analyzer/sse/compose/${ticket}`, onEvent, onDone, onError)
}

export async function cancelComposeAnalysis(ticket: string): Promise<boolean> {
  const res = await fetchWithAuth(`/api/logs/analyzer/sse/compose/cancel/${ticket}`, { method: 'POST' })
  if (!res.ok) return false
  const data = await res.json()
  return data.cancelled
}

export interface LogAnalysisEvent {
  user: string
  filenames: string
  analysisId?: string
}

export function subscribeLogAnalysisUpdates(
  onStarted: (event: LogAnalysisEvent) => void,
  onCompleted: (event: LogAnalysisEvent) => void,
  onDeleted: (event: LogAnalysisEvent) => void,
  onViewers: (counts: Record<string, number>) => void,
): () => void {
  const es = new EventSource('/api/logs/analyzer/sse/updates')
  es.addEventListener('analysis-started', (e) => {
    try { onStarted(JSON.parse((e as MessageEvent).data)) } catch { /* ignore */ }
  })
  es.addEventListener('analysis-completed', (e) => {
    try { onCompleted(JSON.parse((e as MessageEvent).data)) } catch { /* ignore */ }
  })
  es.addEventListener('analysis-deleted', (e) => {
    try { onDeleted(JSON.parse((e as MessageEvent).data)) } catch { /* ignore */ }
  })
  es.addEventListener('viewers', (e) => {
    try { onViewers(JSON.parse((e as MessageEvent).data)) } catch { /* ignore */ }
  })
  es.onerror = () => { /* EventSource auto-reconnects */ }
  return () => es.close()
}

export async function setLogAnalysisViewing(clientToken: string, analysisId: string | null): Promise<void> {
  await fetchWithAuth('/api/logs/analyzer/sse/viewing', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ clientToken, analysisId }),
  }).catch(() => {})
}
