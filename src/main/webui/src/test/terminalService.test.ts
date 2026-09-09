import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { uploadImageToContainer, uploadFileToContainer } from '../services/terminalService'

class FakeXhr {
  static instances: FakeXhr[] = []
  static respondWith: { status: number; body: string } = { status: 200, body: '{}' }

  status = 0
  responseText = ''
  url = ''
  sent: FormData | null = null
  private listeners: Record<string, Array<(e: unknown) => void>> = {}
  upload = {
    addEventListener: (type: string, cb: (e: unknown) => void) => {
      this.listeners['upload:' + type] = [...(this.listeners['upload:' + type] ?? []), cb]
    },
  }

  constructor() { FakeXhr.instances.push(this) }
  addEventListener(type: string, cb: (e: unknown) => void) {
    this.listeners[type] = [...(this.listeners[type] ?? []), cb]
  }
  open(_method: string, url: string) { this.url = url }
  send(body: FormData) {
    this.sent = body
    for (const cb of this.listeners['upload:progress'] ?? []) cb({ lengthComputable: true, loaded: 50, total: 100 })
    this.status = FakeXhr.respondWith.status
    this.responseText = FakeXhr.respondWith.body
    for (const cb of this.listeners['load'] ?? []) cb({})
  }
}

beforeEach(() => {
  FakeXhr.instances = []
  FakeXhr.respondWith = { status: 200, body: '{}' }
  vi.stubGlobal('XMLHttpRequest', FakeXhr)
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('uploadImageToContainer', () => {
  it('posts the image and password to the image endpoint and returns the container path', async () => {
    FakeXhr.respondWith = {
      status: 200,
      body: JSON.stringify({ filename: 'clip-1-ab.png', remotePath: '/tmp/attachments', path: '/tmp/attachments/clip-1-ab.png' }),
    }
    const blob = new Blob([new Uint8Array([1, 2, 3])], { type: 'image/png' })
    const progress = vi.fn()

    const result = await uploadImageToContainer('abc123', blob, 'secret', progress)

    expect(result).toEqual({ success: true, filename: 'clip-1-ab.png', remotePath: '/tmp/attachments', path: '/tmp/attachments/clip-1-ab.png' })
    const xhr = FakeXhr.instances[0]
    expect(xhr.url).toBe('/api/containers/abc123/upload/image')
    expect(xhr.sent?.get('password')).toBe('secret')
    expect(xhr.sent?.get('file')).toBeInstanceOf(Blob)
    expect(xhr.sent?.has('remotePath')).toBe(false)
    expect(progress).toHaveBeenCalledWith(50)
  })

  it('surfaces the backend error message on failure', async () => {
    FakeXhr.respondWith = { status: 400, body: JSON.stringify({ error: 'Unsupported image format.' }) }

    const result = await uploadImageToContainer('abc123', new Blob([]), 'secret')

    expect(result.success).toBe(false)
    expect(result.error).toBe('Unsupported image format.')
  })

  it('reports a generic failure when the response is not JSON', async () => {
    FakeXhr.respondWith = { status: 502, body: '<html>bad gateway</html>' }

    const result = await uploadImageToContainer('abc123', new Blob([]), 'secret')

    expect(result).toEqual({ success: false, error: 'Upload failed' })
  })
})

describe('uploadFileToContainer', () => {
  it('still posts to the generic upload endpoint with the remote path', async () => {
    FakeXhr.respondWith = { status: 200, body: JSON.stringify({ filename: 'a.txt', remotePath: '/tmp' }) }
    const file = new File(['x'], 'a.txt', { type: 'text/plain' })

    const result = await uploadFileToContainer('abc123', file, '/tmp', 'secret')

    expect(result.success).toBe(true)
    expect(FakeXhr.instances[0].url).toBe('/api/containers/abc123/upload')
    expect(FakeXhr.instances[0].sent?.get('remotePath')).toBe('/tmp')
  })
})
