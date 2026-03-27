import fetchWithAuth from './fetchWithAuth'

const API = '/api/containers/'

export async function authorizeTerminal(
  containerId: string,
  password: string,
): Promise<{ ticket?: string; error?: string }> {
  const res = await fetchWithAuth(API + 'terminal/authorize', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ containerId, password }),
  })
  const data = await res.json().catch(() => ({}))
  if (!res.ok) return { error: data.error || 'Authorization failed.' }
  return { ticket: data.ticket }
}

export function uploadFileToContainer(
  containerId: string,
  file: File,
  remotePath: string,
  password: string,
  onProgress?: (percent: number) => void,
): Promise<{ success: boolean; filename?: string; remotePath?: string; error?: string }> {
  return new Promise((resolve) => {
    const formData = new FormData()
    formData.append('file', file)
    formData.append('remotePath', remotePath)
    formData.append('password', password)

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
          resolve({ success: true, filename: data.filename, remotePath: data.remotePath })
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

    xhr.open('POST', API + encodeURIComponent(containerId) + '/upload')
    xhr.send(formData)
  })
}

export interface TerminalConnection {
  sendInput(data: string): void
  sendResize(cols: number, rows: number): void
  close(): void
}

const CONNECTION_TIMEOUT_MS = 10_000

export function connectTerminal(
  ticket: string,
  onConnected: () => void,
  onOutput: (data: Uint8Array) => void,
  onExit: (code: number, message?: string) => void,
  onError: (message: string) => void,
  onDisconnect: () => void,
): TerminalConnection {
  const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:'
  const url = `${protocol}//${location.host}/api/containers/terminal/${encodeURIComponent(ticket)}`

  const ws = new WebSocket(url)
  let closed = false
  let resizeTimer: ReturnType<typeof setTimeout> | null = null

  const connectionTimeout = setTimeout(() => {
    if (!closed && ws.readyState !== WebSocket.OPEN) {
      closed = true
      ws.close()
      onError('Connection timeout.')
    }
  }, CONNECTION_TIMEOUT_MS)

  function clearTimers() {
    clearTimeout(connectionTimeout)
    if (resizeTimer) {
      clearTimeout(resizeTimer)
      resizeTimer = null
    }
  }

  ws.onmessage = (e) => {
    if (closed) return
    try {
      const msg = JSON.parse(e.data)
      switch (msg.type) {
        case 'connected':
          clearTimeout(connectionTimeout)
          onConnected()
          break
        case 'output': {
          const raw = atob(msg.data)
          const bytes = new Uint8Array(raw.length)
          for (let i = 0; i < raw.length; i++) bytes[i] = raw.charCodeAt(i)
          onOutput(bytes)
          break
        }
        case 'exit':
          onExit(msg.code ?? 0, msg.message)
          break
        case 'error':
          onError(msg.message || 'Unknown error.')
          break
        case 'pong':
          break
      }
    } catch {
      // ignore malformed messages
    }
  }

  ws.onerror = () => {
    if (closed) return
    clearTimers()
    onError('Connection error.')
  }

  ws.onclose = () => {
    if (closed) return
    closed = true
    clearTimers()
    onDisconnect()
  }

  return {
    sendInput(data: string) {
      if (!closed && ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify({ type: 'input', data }))
      }
    },

    sendResize(cols: number, rows: number) {
      if (resizeTimer) clearTimeout(resizeTimer)
      resizeTimer = setTimeout(() => {
        if (!closed && ws.readyState === WebSocket.OPEN) {
          ws.send(JSON.stringify({ type: 'resize', cols, rows }))
        }
      }, 200)
    },

    close() {
      if (closed) return
      closed = true
      clearTimers()
      if (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING) {
        ws.close()
      }
    },
  }
}
