import fetchWithAuth from './fetchWithAuth'

const API = '/api/auth/'

export interface AuthStatus {
  authEnabled: boolean
  rbacEnabled: boolean
}

export interface CurrentUser {
  username: string
  roleIds: string[]
  roleNames: string[]
  tenants: { id: string; name: string }[]
  permissions: string[]
}

export async function getAuthStatus(): Promise<AuthStatus> {
  const res = await fetch(API + 'status')
  if (!res.ok) return { authEnabled: false, rbacEnabled: false }
  const data = await res.json()
  return { authEnabled: !!data.authEnabled, rbacEnabled: !!data.rbacEnabled }
}

export async function checkSession(): Promise<{ authenticated: boolean }> {
  const res = await fetch(API + 'check')
  if (!res.ok) return { authenticated: false }
  return res.json()
}

export async function getMe(): Promise<CurrentUser | null> {
  const res = await fetchWithAuth(API + 'me')
  if (!res.ok) return null
  const data = await res.json()
  if (!data.rbac) return null
  return {
    username: data.username ?? '',
    roleIds: Array.isArray(data.roleIds) ? data.roleIds : [],
    roleNames: Array.isArray(data.roleNames) ? data.roleNames : [],
    tenants: Array.isArray(data.tenants) ? data.tenants : [],
    permissions: Array.isArray(data.permissions) ? data.permissions : [],
  }
}

export async function login(
  password: string,
  username?: string,
): Promise<{ authenticated: boolean; error?: string; retryAfter?: number }> {
  const body = username === undefined ? { password } : { username, password }
  const res = await fetch(API + 'login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
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

export async function changeOwnPassword(
  currentPassword: string,
  newPassword: string,
): Promise<{ success: boolean; error?: string }> {
  const res = await fetchWithAuth(API + 'change-password', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ currentPassword, newPassword }),
  })
  if (!res.ok) {
    const data = await res.json().catch(() => ({}))
    return { success: false, error: data.message || 'Failed to change password.' }
  }
  return { success: true }
}

export async function logout(): Promise<void> {
  await fetch(API + 'logout', { method: 'POST' }).catch(() => {})
}
