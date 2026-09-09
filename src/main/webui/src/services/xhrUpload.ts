/**
 * One XMLHttpRequest multipart upload with progress and cancellation, shared by every
 * uploader so session expiry (401) is handled the same way as in fetchWithAuth.
 * Resolves with the raw status and body; callers decide how to parse. Rejects on network
 * failure or abort with a message callers can show as-is.
 */
export interface XhrUploadOptions {
  onProgress?: (percent: number) => void
  signal?: AbortSignal
}

export interface XhrUploadResponse {
  status: number
  text: string
}

export function xhrUpload(url: string, formData: FormData, options: XhrUploadOptions = {}): Promise<XhrUploadResponse> {
  return new Promise((resolve, reject) => {
    const { onProgress, signal } = options
    if (signal?.aborted) {
      reject(new Error('Upload cancelled'))
      return
    }
    const xhr = new XMLHttpRequest()
    signal?.addEventListener('abort', () => xhr.abort(), { once: true })

    xhr.upload.addEventListener('progress', (e) => {
      if (e.lengthComputable && onProgress) onProgress(Math.round((e.loaded / e.total) * 100))
    })
    xhr.addEventListener('load', () => {
      if (xhr.status === 401) window.dispatchEvent(new CustomEvent('auth:session-expired'))
      resolve({ status: xhr.status, text: xhr.responseText })
    })
    xhr.addEventListener('error', () => reject(new Error('Network error during upload')))
    xhr.addEventListener('abort', () => reject(new Error('Upload cancelled')))

    xhr.open('POST', url)
    xhr.send(formData)
  })
}
