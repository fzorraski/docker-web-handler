import { createContext, useContext, useState, useEffect, useCallback, type ReactNode } from 'react'
import { getAuthStatus, checkSession, login as apiLogin, logout as apiLogout } from '../services/authService'

interface AuthContextValue {
  authEnabled: boolean
  authenticated: boolean
  loading: boolean
  login: (password: string) => Promise<{ success: boolean; error?: string }>
  logout: () => Promise<void>
}

const AuthContext = createContext<AuthContextValue>({
  authEnabled: false,
  authenticated: true,
  loading: true,
  login: async () => ({ success: false }),
  logout: async () => {},
})

export function useAuth() {
  return useContext(AuthContext)
}

export default function AuthProvider({ children }: { children: ReactNode }) {
  const [authEnabled, setAuthEnabled] = useState(false)
  const [authenticated, setAuthenticated] = useState(true)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    getAuthStatus()
      .then(({ authEnabled: enabled }) => {
        setAuthEnabled(enabled)
        if (!enabled) {
          setAuthenticated(true)
          setLoading(false)
        } else {
          checkSession()
            .then(({ authenticated: valid }) => {
              setAuthenticated(valid)
              setLoading(false)
            })
            .catch(() => {
              setAuthenticated(false)
              setLoading(false)
            })
        }
      })
      .catch(() => {
        setAuthEnabled(false)
        setAuthenticated(true)
        setLoading(false)
      })
  }, [])

  // Listen for session-expired events from service layer
  useEffect(() => {
    const handler = () => setAuthenticated(false)
    window.addEventListener('auth:session-expired', handler)
    return () => window.removeEventListener('auth:session-expired', handler)
  }, [])

  const login = useCallback(async (password: string) => {
    const result = await apiLogin(password)
    if (result.authenticated) {
      setAuthenticated(true)
      return { success: true }
    }
    return { success: false, error: result.error }
  }, [])

  const logout = useCallback(async () => {
    await apiLogout()
    setAuthenticated(false)
  }, [])

  return (
    <AuthContext.Provider value={{ authEnabled, authenticated, loading, login, logout }}>
      {children}
    </AuthContext.Provider>
  )
}
