import { createContext, useContext, useState, useEffect, useCallback, type ReactNode } from 'react'
import {
  getAuthStatus,
  checkSession,
  getMe,
  login as apiLogin,
  logout as apiLogout,
  type CurrentUser,
} from '../services/authService'

interface AuthContextValue {
  authEnabled: boolean
  rbacEnabled: boolean
  authenticated: boolean
  loading: boolean
  currentUser: CurrentUser | null
  hasPermission: (permission: string) => boolean
  login: (password: string, username?: string) => Promise<{ success: boolean; error?: string; retryAfter?: number }>
  logout: () => Promise<void>
  refreshUser: () => Promise<void>
}

const AuthContext = createContext<AuthContextValue>({
  authEnabled: false,
  rbacEnabled: false,
  authenticated: true,
  loading: true,
  currentUser: null,
  hasPermission: () => true,
  login: async () => ({ success: false }),
  logout: async () => {},
  refreshUser: async () => {},
})

export function useAuth() {
  return useContext(AuthContext)
}

export default function AuthProvider({ children }: { children: ReactNode }) {
  const [authEnabled, setAuthEnabled] = useState(false)
  const [rbacEnabled, setRbacEnabled] = useState(false)
  const [authenticated, setAuthenticated] = useState(true)
  const [currentUser, setCurrentUser] = useState<CurrentUser | null>(null)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    let cancelled = false
    async function bootstrap() {
      try {
        const { authEnabled: enabled, rbacEnabled: rbac } = await getAuthStatus()
        if (cancelled) return
        setAuthEnabled(enabled)
        setRbacEnabled(rbac)
        if (!enabled) {
          setAuthenticated(true)
          return
        }
        const { authenticated: valid } = await checkSession()
        if (cancelled) return
        setAuthenticated(valid)
        if (valid && rbac) {
          // resolve permissions before rendering to avoid permission-hidden UI flicker
          const user = await getMe()
          if (!cancelled) setCurrentUser(user)
        }
      } catch {
        if (!cancelled) {
          setAuthEnabled(false)
          setRbacEnabled(false)
          setAuthenticated(true)
        }
      } finally {
        if (!cancelled) setLoading(false)
      }
    }
    bootstrap()
    return () => {
      cancelled = true
    }
  }, [])

  // Listen for session-expired events from service layer
  useEffect(() => {
    const handler = () => {
      setAuthenticated(false)
      setCurrentUser(null)
    }
    window.addEventListener('auth:session-expired', handler)
    return () => window.removeEventListener('auth:session-expired', handler)
  }, [])

  const login = useCallback(async (password: string, username?: string) => {
    const result = await apiLogin(password, username)
    if (result.authenticated) {
      if (rbacEnabled) {
        setCurrentUser(await getMe())
      }
      setAuthenticated(true)
      return { success: true }
    }
    return { success: false, error: result.error, retryAfter: result.retryAfter }
  }, [rbacEnabled])

  const logout = useCallback(async () => {
    await apiLogout()
    setAuthenticated(false)
    setCurrentUser(null)
  }, [])

  const refreshUser = useCallback(async () => {
    if (!rbacEnabled) return
    setCurrentUser(await getMe())
  }, [rbacEnabled])

  const hasPermission = useCallback(
    (permission: string) => !rbacEnabled || currentUser?.permissions.includes(permission) === true,
    [rbacEnabled, currentUser],
  )

  return (
    <AuthContext.Provider
      value={{
        authEnabled,
        rbacEnabled,
        authenticated,
        loading,
        currentUser,
        hasPermission,
        login,
        logout,
        refreshUser,
      }}
    >
      {children}
    </AuthContext.Provider>
  )
}
