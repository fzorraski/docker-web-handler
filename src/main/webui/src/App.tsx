import { Routes, Route, Navigate } from 'react-router-dom'
import { Box } from '@mui/material'
import Navbar from './components/Navbar'
import Footer from './components/Footer'
import ErrorBoundary from './components/ErrorBoundary'
import ContainersPage from './pages/ContainersPage'
import ImagesPage from './pages/ImagesPage'
import DatabasePage from './pages/DatabasePage'
import SchedulesPage from './pages/SchedulesPage'

export default function App() {
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
