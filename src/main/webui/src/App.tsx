import { Routes, Route, Navigate } from 'react-router-dom'
import { Box, CircularProgress } from '@mui/material'
import Navbar from './components/Navbar'
import Footer from './components/Footer'
import ErrorBoundary from './components/ErrorBoundary'
import { useAuth } from './components/AuthProvider'
import ContainersPage from './pages/ContainersPage'
import ImagesPage from './pages/ImagesPage'
import DatabasePage from './pages/DatabasePage'
import SchedulesPage from './pages/SchedulesPage'
import LoginPage from './pages/LoginPage'

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
          <Routes>
            <Route path="/" element={<ContainersPage />} />
            <Route path="/images" element={<ImagesPage />} />
            <Route path="/database" element={<DatabasePage />} />
            <Route path="/schedules" element={<SchedulesPage />} />
            <Route path="*" element={<Navigate to="/" replace />} />
          </Routes>
        </ErrorBoundary>
      </Box>
      <Footer />
    </Box>
  )
}
