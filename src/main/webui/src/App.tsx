import { lazy, Suspense, useEffect, useRef } from 'react'
import { Routes, Route, Navigate } from 'react-router-dom'
import { Alert, Box, CircularProgress } from '@mui/material'
import { useTranslation } from 'react-i18next'
import Navbar from './components/Navbar'
import Footer from './components/Footer'
import ErrorBoundary from './components/ErrorBoundary'
import RequirePermission from './components/RequirePermission'
import { useAuth } from './components/AuthProvider'
import { useNotification } from './components/NotificationProvider'
import { P } from './utils/permissions'
import LoginPage from './pages/LoginPage'

const ContainersPage = lazy(() => import('./pages/ContainersPage'))
const ImagesPage = lazy(() => import('./pages/ImagesPage'))
const DatabasePage = lazy(() => import('./pages/DatabasePage'))
const SchedulesPage = lazy(() => import('./pages/SchedulesPage'))
const LogAnalyzerPage = lazy(() => import('./pages/LogAnalyzerPage'))
const StatsComparisonPage = lazy(() => import('./pages/StatsComparisonPage'))
const AdminPage = lazy(() => import('./pages/AdminPage'))

const PageSpinner = () => (
  <Box sx={{ display: 'flex', justifyContent: 'center', py: 10 }}>
    <CircularProgress />
  </Box>
)

/**
 * Home route resolver: '/' is also where RequirePermission sends denied users,
 * so it must never render a page whose data the user cannot load. Falls through
 * to the first page the role can view; with no view permission at all it shows
 * a friendly notice instead of a broken page firing 403s.
 */
function HomeRoute() {
  const { hasPermission } = useAuth()
  const { t } = useTranslation()

  if (hasPermission(P.CONTAINERS_VIEW)) return <ContainersPage />
  const fallback = [
    { permission: P.IMAGES_VIEW, path: '/images' },
    { permission: P.DATABASE_VIEW, path: '/database' },
    { permission: P.SCHEDULES_VIEW, path: '/schedules' },
    { permission: P.LOGS_VIEW, path: '/logs' },
    { permission: P.USERS_MANAGE, path: '/admin' },
  ].find(({ permission }) => hasPermission(permission))
  if (fallback) return <Navigate to={fallback.path} replace />
  return (
    <Box sx={{ maxWidth: 480, mx: 'auto', mt: 10 }}>
      <Alert severity="warning">{t('errors.noPagePermissions')}</Alert>
    </Box>
  )
}

export default function App() {
  const { authEnabled, authenticated, loading, refreshUser } = useAuth()
  const { notify } = useNotification()
  const { t } = useTranslation()

  // RBAC denial: warn the user and re-fetch permissions (they may have been revoked mid-session).
  // Rate-limited so parallel failing calls don't stack toasts.
  const lastForbiddenAt = useRef(0)
  useEffect(() => {
    const handler = () => {
      const now = Date.now()
      if (now - lastForbiddenAt.current < 5000) return
      lastForbiddenAt.current = now
      notify(t('errors.forbidden'), 'error')
      refreshUser()
    }
    window.addEventListener('auth:forbidden', handler)
    return () => window.removeEventListener('auth:forbidden', handler)
  }, [notify, refreshUser, t])

  if (loading) {
    return (
      <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'center', minHeight: '100vh' }}>
        <CircularProgress />
      </Box>
    )
  }

  if (authEnabled && !authenticated) {
    return <LoginPage />
  }

  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', height: '100vh' }}>
      <Navbar />
      <Box sx={{ flex: 1, overflowY: 'auto' }}>
        <ErrorBoundary>
          <Suspense fallback={<PageSpinner />}>
            <Routes>
              <Route path="/" element={<HomeRoute />} />
              <Route path="/images" element={<RequirePermission permission={P.IMAGES_VIEW}><ImagesPage /></RequirePermission>} />
              <Route path="/database" element={<RequirePermission permission={P.DATABASE_VIEW}><DatabasePage /></RequirePermission>} />
              <Route path="/schedules" element={<RequirePermission permission={P.SCHEDULES_VIEW}><SchedulesPage /></RequirePermission>} />
              <Route path="/logs" element={<RequirePermission permission={P.LOGS_VIEW}><LogAnalyzerPage /></RequirePermission>} />
              <Route path="/compare" element={<RequirePermission permission={P.LOGS_VIEW}><StatsComparisonPage /></RequirePermission>} />
              <Route path="/admin" element={<RequirePermission permission={P.USERS_MANAGE}><AdminPage /></RequirePermission>} />
              <Route path="*" element={<Navigate to="/" replace />} />
            </Routes>
          </Suspense>
        </ErrorBoundary>
      </Box>
      <Footer />
    </Box>
  )
}
