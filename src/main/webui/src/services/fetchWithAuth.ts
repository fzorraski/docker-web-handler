/**
 * Wraps the global fetch to dispatch a session-expired event on 401 responses.
 * All service modules should use this instead of raw fetch for API calls.
 */
export default function fetchWithAuth(input: RequestInfo | URL, init?: RequestInit): Promise<Response> {
  const request = init !== undefined ? fetch(input, init) : fetch(input)
  return request.then((res) => {
    if (res.status === 401) {
      window.dispatchEvent(new CustomEvent('auth:session-expired'))
    }
    return res
  })
}
