export interface ContainerEvent {
  type: 'INFO' | 'PROGRESS' | 'SUCCESS' | 'ERROR'
  step: string
  message: string
  progress?: number
}

function streamSse(
  url: string,
  onEvent: (event: ContainerEvent) => void,
  onDone: () => void,
  onError: (message: string) => void,
): () => void {
  const es = new EventSource(url)

  es.onmessage = (e) => {
    try {
      const event: ContainerEvent = JSON.parse(e.data)
      onEvent(event)

      if (event.type === 'SUCCESS') {
        es.close()
        onDone()
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
}): Promise<string> {
  const res = await fetch('/api/containers/sse/run/prepare', {
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
  onDone: () => void,
  onError: (message: string) => void,
): () => void {
  return streamSse(`/api/containers/sse/run/${ticket}`, onEvent, onDone, onError)
}

export async function cancelRunContainer(ticket: string): Promise<boolean> {
  const res = await fetch(`/api/containers/sse/run/cancel/${ticket}`, { method: 'POST' })
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
  const res = await fetch('/api/containers/sse/migration/prepare', {
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
  onDone: () => void,
  onError: (message: string) => void,
): () => void {
  return streamSse(`/api/containers/sse/migration/${ticket}`, onEvent, onDone, onError)
}

export async function cancelRunMigration(ticket: string): Promise<boolean> {
  const res = await fetch(`/api/containers/sse/migration/cancel/${ticket}`, { method: 'POST' })
  if (!res.ok) return false
  const data = await res.json()
  return data.cancelled
}

export async function preparePruneImages(body: {
  password: string
  minDays: number
}): Promise<string> {
  const res = await fetch('/api/images/sse/prune/prepare', {
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
  onDone: () => void,
  onError: (message: string) => void,
): () => void {
  return streamSse(`/api/images/sse/prune/${ticket}`, onEvent, onDone, onError)
}

export function streamRemoveImage(
  imageId: string,
  onEvent: (event: ContainerEvent) => void,
  onDone: () => void,
  onError: (message: string) => void,
): () => void {
  return streamSse(`/api/images/sse/remove/${encodeURIComponent(imageId)}`, onEvent, onDone, onError)
}

export function streamContainerLogs(
  containerId: string,
  onEvent: (event: ContainerEvent) => void,
  onDone: () => void,
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

export function streamRemoveContainer(
  containerId: string,
  onEvent: (event: ContainerEvent) => void,
  onDone: () => void,
  onError: (message: string) => void,
): () => void {
  return streamSse(`/api/containers/sse/remove/${encodeURIComponent(containerId)}`, onEvent, onDone, onError)
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
  const res = await fetch('/api/database/dumps/sse/restore/prepare', {
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
  onDone: () => void,
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
}): Promise<string> {
  const res = await fetch('/api/database/snapshots/sse/create/prepare', {
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
  onDone: () => void,
  onError: (message: string) => void,
): () => void {
  return streamSse(`/api/database/snapshots/sse/create/${ticket}`, onEvent, onDone, onError)
}
