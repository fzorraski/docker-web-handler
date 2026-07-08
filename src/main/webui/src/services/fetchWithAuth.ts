export class RateLimitError extends Error {
  retryAfter: number
  constructor(message: string, retryAfter: number) {
    super(message)
    this.retryAfter = retryAfter
  }
}

export class ForbiddenError extends Error {}

export interface FetchWithAuthOptions {
  /**
   * Set false for calls that surface backend errors themselves (e.g. the admin
   * page): the global "no permission" toast is skipped so the user sees only
   * the specific message. ForbiddenError is still thrown.
   */
  forbiddenEvent?: boolean
}

/**
 * Wraps the global fetch to dispatch a session-expired event on 401 responses,
 * an auth:forbidden event (plus ForbiddenError) on RBAC 403 denials, and throw
 * RateLimitError on 429 responses.
 * All service modules should use this instead of raw fetch for API calls.
 */
export default async function fetchWithAuth(
  input: RequestInfo | URL,
  init?: RequestInit,
  options?: FetchWithAuthOptions,
): Promise<Response> {
  const res = init !== undefined ? await fetch(input, init) : await fetch(input)
  if (res.status === 401) {
    window.dispatchEvent(new CustomEvent('auth:session-expired'))
  }
  if (res.status === 403) {
    // peek at a clone so legacy 403 flows (e.g. invalid operations password) can
    // still read the body themselves; only RBAC denials are intercepted here
    let data: { code?: string; error?: string; message?: string } = {}
    try {
      data = await (typeof res.clone === 'function' ? res.clone() : res).json()
    } catch { /* not JSON */ }
    if (data.code === 'FORBIDDEN') {
      if (options?.forbiddenEvent !== false) {
        // permissions may have been revoked mid-session; App refreshes them on this event
        window.dispatchEvent(new CustomEvent('auth:forbidden'))
      }
      throw new ForbiddenError(data.error || data.message || 'Access denied.')
    }
  }
  if (res.status === 429) {
    const data = await res.json().catch(() => ({}))
    const retryAfter = Math.max(0, Math.floor(Number(data.retryAfter) || 0))
    throw new RateLimitError(data.error || data.message || 'Too many attempts.', retryAfter)
  }
  return res
}

/** Shared JSON response unwrapper: throws the backend's error message on non-2xx. */
export async function handleJsonResponse<T>(res: Response): Promise<T> {
  if (!res.ok) {
    const data = await res.json().catch(() => ({}))
    throw new Error(data.error || data.message || res.statusText)
  }
  return res.json()
}
