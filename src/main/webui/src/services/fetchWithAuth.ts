export class RateLimitError extends Error {
  retryAfter: number
  constructor(message: string, retryAfter: number) {
    super(message)
    this.retryAfter = retryAfter
  }
}

/**
 * Wraps the global fetch to dispatch a session-expired event on 401 responses
 * and throw RateLimitError on 429 responses.
 * All service modules should use this instead of raw fetch for API calls.
 */
export default async function fetchWithAuth(input: RequestInfo | URL, init?: RequestInit): Promise<Response> {
  const res = init !== undefined ? await fetch(input, init) : await fetch(input)
  if (res.status === 401) {
    window.dispatchEvent(new CustomEvent('auth:session-expired'))
  }
  if (res.status === 429) {
    const data = await res.json().catch(() => ({}))
    const retryAfter = Math.max(0, Math.floor(Number(data.retryAfter) || 0))
    throw new RateLimitError(data.error || data.message || 'Too many attempts.', retryAfter)
  }
  return res
}
