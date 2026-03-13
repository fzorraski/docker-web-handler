import { Routes, Route, Navigate } from 'react-router-dom'
import { Box } from '@mui/material'
import Navbar from './components/Navbar'
import Footer from './components/Footer'
import ContainersPage from './pages/ContainersPage'
import ImagesPage from './pages/ImagesPage'
import DatabasePage from './pages/DatabasePage'

export default function App() {
  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', minHeight: '100vh' }}>
      <Navbar />
      <Box sx={{ flex: 1 }}>
        <Routes>
          <Route path="/" element={<ContainersPage />} />
          <Route path="/images" element={<ImagesPage />} />
          <Route path="/database" element={<DatabasePage />} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </Box>
      <Footer />
    </Box>
  )
}
