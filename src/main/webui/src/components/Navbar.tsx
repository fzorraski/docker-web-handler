import { useState, useEffect } from 'react'
import { AppBar, Toolbar, Typography, Button, Box, IconButton, Tooltip } from '@mui/material'
import { Link, useLocation } from 'react-router-dom'
import { DarkMode, LightMode } from '@mui/icons-material'
import { isDumpEnabled } from '../services/dumpService'
import { useThemeMode } from './ThemeModeProvider'

export default function Navbar() {
  const location = useLocation()
  const { mode, toggleMode } = useThemeMode()
  const [dumpEnabled, setDumpEnabled] = useState(false)

  useEffect(() => {
    isDumpEnabled().then(setDumpEnabled).catch(() => setDumpEnabled(false))
  }, [])

  const navItems = [
    { label: 'Containers', path: '/' },
    { label: 'Images', path: '/images' },
    ...(dumpEnabled ? [{ label: 'Database', path: '/database' }] : []),
  ]

  return (
    <AppBar position="static">
      <Toolbar>
        <Typography
          variant="h6"
          component={Link}
          to="/"
          sx={{ flexGrow: 1, textDecoration: 'none', color: 'inherit', fontWeight: 700 }}
        >
          Docker Handler
        </Typography>
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5 }}>
          {navItems.map((item) => (
            <Button
              key={item.path}
              component={Link}
              to={item.path}
              sx={{
                color: location.pathname === item.path ? 'secondary.main' : 'inherit',
                fontWeight: 600,
                '&:hover': { color: 'secondary.main' },
              }}
            >
              {item.label}
            </Button>
          ))}
          <Tooltip title={mode === 'light' ? 'Dark mode' : 'Light mode'} arrow>
            <IconButton
              onClick={toggleMode}
              sx={{
                ml: 1,
                color: 'inherit',
                transition: 'transform 0.3s ease',
                '&:hover': { transform: 'rotate(30deg)' },
              }}
            >
              {mode === 'light' ? <DarkMode /> : <LightMode />}
            </IconButton>
          </Tooltip>
        </Box>
      </Toolbar>
    </AppBar>
  )
}
