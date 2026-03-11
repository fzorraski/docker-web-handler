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
}): Promise<string> {
  const res = await fetch('/containers/sse/run/prepare', {
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
  return streamSse(`/containers/sse/run/${ticket}`, onEvent, onDone, onError)
}

export function streamRemoveImage(
  imageId: string,
  onEvent: (event: ContainerEvent) => void,
  onDone: () => void,
  onError: (message: string) => void,
): () => void {
  return streamSse(`/images/sse/remove/${encodeURIComponent(imageId)}`, onEvent, onDone, onError)
}

export function streamRemoveContainer(
  containerId: string,
  onEvent: (event: ContainerEvent) => void,
  onDone: () => void,
  onError: (message: string) => void,
): () => void {
  return streamSse(`/containers/sse/remove/${encodeURIComponent(containerId)}`, onEvent, onDone, onError)
}
