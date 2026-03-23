import { lazy, Suspense } from 'react'
import { Routes, Route, Navigate } from 'react-router-dom'
import { Box, CircularProgress } from '@mui/material'
import Navbar from './components/Navbar'
import Footer from './components/Footer'
import ErrorBoundary from './components/ErrorBoundary'
import { useAuth } from './components/AuthProvider'
import LoginPage from './pages/LoginPage'

const ContainersPage = lazy(() => import('./pages/ContainersPage'))
const ImagesPage = lazy(() => import('./pages/ImagesPage'))
const DatabasePage = lazy(() => import('./pages/DatabasePage'))
const SchedulesPage = lazy(() => import('./pages/SchedulesPage'))

const PageSpinner = () => (
  <Box sx={{ display: 'flex', justifyContent: 'center', py: 10 }}>
    <CircularProgress />
  </Box>
)

export default function App() {
  const { authEnabled, authenticated, loading } = useAuth()

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
    <Box sx={{ display: 'flex', flexDirection: 'column', minHeight: '100vh' }}>
      <Navbar />
      <Box sx={{ flex: 1 }}>
        <ErrorBoundary>
          <Suspense fallback={<PageSpinner />}>
            <Routes>
              <Route path="/" element={<ContainersPage />} />
              <Route path="/images" element={<ImagesPage />} />
              <Route path="/database" element={<DatabasePage />} />
              <Route path="/schedules" element={<SchedulesPage />} />
              <Route path="*" element={<Navigate to="/" replace />} />
            </Routes>
          </Suspense>
        </ErrorBoundary>
      </Box>
      <Footer />
    </Box>
  )
}
