import { useState, useEffect } from 'react'
import {
  AppBar, Toolbar, Typography, Button, Box, IconButton, Tooltip,
  Drawer, List, ListItem, ListItemButton, ListItemText, Divider,
  useMediaQuery, useTheme,
} from '@mui/material'
import { Link, useLocation } from 'react-router-dom'
import { DarkMode, LightMode, Menu as MenuIcon } from '@mui/icons-material'
import { useTranslation } from 'react-i18next'
import { isDumpEnabled } from '../services/dumpService'
import { useThemeMode } from './ThemeModeProvider'
import LanguageSwitcher from './LanguageSwitcher'

export default function Navbar() {
  const location = useLocation()
  const { mode, toggleMode } = useThemeMode()
  const { t } = useTranslation()
  const [dumpEnabled, setDumpEnabled] = useState(false)
  const [drawerOpen, setDrawerOpen] = useState(false)

  const theme = useTheme()
  const isMobile = useMediaQuery(theme.breakpoints.down('md'))

  useEffect(() => {
    isDumpEnabled().then(setDumpEnabled).catch(() => setDumpEnabled(false))
  }, [])

  const navItems = [
    { label: t('navbar.containers'), path: '/' },
    { label: t('navbar.images'), path: '/images' },
    ...(dumpEnabled ? [{ label: t('navbar.database'), path: '/database' }] : []),
  ]

  const handleDrawerToggle = () => {
    setDrawerOpen((prev) => !prev)
  }

  const handleDrawerClose = () => {
    setDrawerOpen(false)
  }

  return (
    <>
      <AppBar position="static">
        <Toolbar>
          <Typography
            variant="h6"
            component={Link}
            to="/"
            sx={{ flexGrow: 1, textDecoration: 'none', color: 'inherit', fontWeight: 700 }}
          >
            {t('navbar.title')}
          </Typography>

          {/* Desktop nav */}
          <Box sx={{ display: { xs: 'none', md: 'flex' }, alignItems: 'center', gap: 0.5 }}>
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
            <LanguageSwitcher />
            <Tooltip title={mode === 'light' ? t('navbar.darkMode') : t('navbar.lightMode')} arrow>
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

          {/* Mobile hamburger button */}
          <Box sx={{ display: { xs: 'flex', md: 'none' } }}>
            <IconButton color="inherit" aria-label="open menu" onClick={handleDrawerToggle}>
              <MenuIcon />
            </IconButton>
          </Box>
        </Toolbar>
      </AppBar>

      {/* Mobile drawer */}
      <Drawer
        anchor="right"
        open={isMobile && drawerOpen}
        onClose={handleDrawerClose}
      >
        <Box sx={{ width: 250 }} role="presentation">
          <List>
            {navItems.map((item) => (
              <ListItem key={item.path} disablePadding>
                <ListItemButton
                  component={Link}
                  to={item.path}
                  selected={location.pathname === item.path}
                  onClick={handleDrawerClose}
                >
                  <ListItemText
                    primary={item.label}
                    primaryTypographyProps={{ fontWeight: 600 }}
                  />
                </ListItemButton>
              </ListItem>
            ))}
          </List>
          <Divider />
          <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', px: 2, py: 1 }}>
            <LanguageSwitcher />
            <Tooltip title={mode === 'light' ? t('navbar.darkMode') : t('navbar.lightMode')} arrow>
              <IconButton
                onClick={toggleMode}
                sx={{
                  transition: 'transform 0.3s ease',
                  '&:hover': { transform: 'rotate(30deg)' },
                }}
              >
                {mode === 'light' ? <DarkMode /> : <LightMode />}
              </IconButton>
            </Tooltip>
          </Box>
        </Box>
      </Drawer>
    </>
  )
}
