const API = '/api/auth/'

export async function getAuthStatus(): Promise<{ authEnabled: boolean }> {
  const res = await fetch(API + 'status')
  if (!res.ok) return { authEnabled: false }
  return res.json()
}

export async function checkSession(): Promise<{ authenticated: boolean }> {
  const res = await fetch(API + 'check')
  if (!res.ok) return { authenticated: false }
  return res.json()
}

export async function login(password: string): Promise<{ authenticated: boolean; error?: string; retryAfter?: number }> {
  const res = await fetch(API + 'login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ password }),
  })
  const data = await res.json().catch(() => ({}))
  if (res.status === 429) {
    const retryAfter = Math.max(0, Math.floor(Number(data.retryAfter) || 0))
    return { authenticated: false, error: data.message || 'Too many attempts.', retryAfter }
  }
  if (!res.ok) {
    return { authenticated: false, error: data.message || 'Login failed.' }
  }
  return { authenticated: true }
}

export async function logout(): Promise<void> {
  await fetch(API + 'logout', { method: 'POST' }).catch(() => {})
}
