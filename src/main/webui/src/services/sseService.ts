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
  createDatabase?: boolean
}): Promise<string> {
  const res = await fetch('/api/containers/sse/run/prepare', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  if (!res.ok) throw new Error(res.statusText)
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

export function streamRemoveImage(
  imageId: string,
  onEvent: (event: ContainerEvent) => void,
  onDone: () => void,
  onError: (message: string) => void,
): () => void {
  return streamSse(`/api/images/sse/remove/${encodeURIComponent(imageId)}`, onEvent, onDone, onError)
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
  dumpId: string
  repository: string
  targetDatabase: string
  createDatabase: boolean
  password: string
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
