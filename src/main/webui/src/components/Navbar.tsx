import { useState, useEffect } from 'react'
import { AppBar, Toolbar, Typography, Button, Box } from '@mui/material'
import { Link, useLocation } from 'react-router-dom'
import { isDumpEnabled } from '../services/dumpService'

export default function Navbar() {
  const location = useLocation()
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
    <AppBar position="static" sx={{ bgcolor: 'primary.main' }}>
      <Toolbar>
        <Typography
          variant="h6"
          component={Link}
          to="/"
          sx={{ flexGrow: 1, textDecoration: 'none', color: 'white', fontWeight: 700 }}
        >
          Docker Handler
        </Typography>
        <Box>
          {navItems.map((item) => (
            <Button
              key={item.path}
              component={Link}
              to={item.path}
              sx={{
                color: location.pathname === item.path ? 'secondary.main' : 'white',
                fontWeight: 600,
                '&:hover': { color: 'secondary.main' },
              }}
            >
              {item.label}
            </Button>
          ))}
        </Box>
      </Toolbar>
    </AppBar>
  )
}
