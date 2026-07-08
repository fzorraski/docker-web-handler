// Navbar — dark surface with accent-pill active states and monospace brand mark.
// Nav items: muted by default, accent color + subtle bg on active/hover.

import { useState, useEffect } from 'react'
import {
  AppBar, Toolbar, Typography, Button, Box, IconButton, Tooltip,
  Drawer, List, ListItem, ListItemButton, ListItemText, ListItemIcon, Divider,
  Menu, MenuItem, useMediaQuery, useTheme,
} from '@mui/material'
import { Link, useLocation } from 'react-router-dom'
import { DarkMode, LightMode, Logout, Menu as MenuIcon, AccountCircle, Password } from '@mui/icons-material'
import { useTranslation } from 'react-i18next'
import { isDumpEnabled } from '../services/dumpService'
import { isSchedulingEnabled } from '../services/scheduleService'
import { isLogAnalyzerEnabled } from '../services/logAnalyzerService'
import { useThemeMode } from './ThemeModeProvider'
import { useAuth } from './AuthProvider'
import { P } from '../utils/permissions'
import LanguageSwitcher from './LanguageSwitcher'
import ChangePasswordDialog from './ChangePasswordDialog'

export default function Navbar() {
  const location = useLocation()
  const { mode, toggleMode } = useThemeMode()
  const { authEnabled, rbacEnabled, currentUser, logout, hasPermission } = useAuth()
  const { t } = useTranslation()
  const [dumpEnabled, setDumpEnabled] = useState(false)
  const [schedulingEnabled, setSchedulingEnabled] = useState(false)
  const [logAnalyzerEnabled, setLogAnalyzerEnabled] = useState(false)
  const [drawerOpen, setDrawerOpen] = useState(false)
  const [accountAnchor, setAccountAnchor] = useState<HTMLElement | null>(null)
  const [changePwOpen, setChangePwOpen] = useState(false)

  const theme = useTheme()
  const isDark = theme.palette.mode === 'dark'
  const isMobile = useMediaQuery(theme.breakpoints.down('md'))

  useEffect(() => {
    isDumpEnabled().then(setDumpEnabled).catch(() => setDumpEnabled(false))
    isSchedulingEnabled().then(setSchedulingEnabled).catch(() => setSchedulingEnabled(false))
    isLogAnalyzerEnabled().then(setLogAnalyzerEnabled).catch(() => setLogAnalyzerEnabled(false))
  }, [])

  const navItems = [
    { label: t('navbar.containers'), path: '/' },
    ...(hasPermission(P.IMAGES_VIEW) ? [{ label: t('navbar.images'), path: '/images' }] : []),
    ...(dumpEnabled && hasPermission(P.DATABASE_VIEW) ? [{ label: t('navbar.database'), path: '/database' }] : []),
    ...(schedulingEnabled && hasPermission(P.SCHEDULES_VIEW) ? [{ label: t('navbar.schedules'), path: '/schedules' }] : []),
    ...(logAnalyzerEnabled && hasPermission(P.LOGS_VIEW) ? [{ label: t('navbar.logs'), path: '/logs' }] : []),
    ...(rbacEnabled && hasPermission(P.USERS_MANAGE) ? [{ label: t('navbar.admin'), path: '/admin' }] : []),
  ]

  const handleDrawerToggle = () => {
    setDrawerOpen((prev) => !prev)
  }

  const handleDrawerClose = () => {
    setDrawerOpen(false)
  }

  const isActive = (path: string) => location.pathname === path

  return (
    <>
      <AppBar position="static">
        <Toolbar>
          {/* Brand mark: 4-square grid icon + monospace title */}
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, flexGrow: 1 }}>
            <Box
              component="svg"
              viewBox="0 0 64 64"
              sx={{ width: 22, height: 22, flexShrink: 0 }}
            >
              <defs>
                <linearGradient id="nav-a" x1="0" y1="0" x2="1" y2="1">
                  <stop offset="0%" stopColor="#FFAB40" />
                  <stop offset="100%" stopColor="#FF8F33" />
                </linearGradient>
                <linearGradient id="nav-b" x1="1" y1="0" x2="0" y2="1">
                  <stop offset="0%" stopColor="#FF6D00" />
                  <stop offset="100%" stopColor="#BF360C" />
                </linearGradient>
                <linearGradient id="nav-c" x1="0" y1="0" x2="1" y2="1">
                  <stop offset="0%" stopColor="#FF8F33" />
                  <stop offset="100%" stopColor="#E65100" />
                </linearGradient>
              </defs>
              <g opacity={0.8}>
                <path d="M50 2L55 5L50 8L45 5Z" fill="#FFAB40" />
                <path d="M45 5L50 8L50 13L45 10Z" fill="#BF360C" />
                <path d="M55 5L50 8L50 13L55 10Z" fill="#E65100" />
              </g>
              <path d="M32 14L54 26L32 38L10 26Z" fill="url(#nav-a)" />
              <path d="M10 26L32 38L32 56L10 44Z" fill="url(#nav-b)" />
              <path d="M54 26L32 38L32 56L54 44Z" fill="url(#nav-c)" />
            </Box>
            <Typography
              variant="h6"
              component={Link}
              to="/"
              sx={{
                textDecoration: 'none',
                color: 'inherit',
                fontWeight: 700,
                fontFamily: "'JetBrains Mono', monospace",
                letterSpacing: '-0.02em',
                fontSize: '1rem',
              }}
            >
              {t('navbar.title')}
            </Typography>
          </Box>

          {/* Desktop nav */}
          <Box sx={{ display: { xs: 'none', md: 'flex' }, alignItems: 'center', gap: 0.5 }}>
            {navItems.map((item) => (
              <Button
                key={item.path}
                component={Link}
                to={item.path}
                sx={{
                  color: isActive(item.path)
                    ? 'primary.main'
                    : isDark ? 'rgba(255,255,255,0.55)' : 'text.secondary',
                  fontWeight: 600,
                  fontSize: '0.85rem',
                  borderRadius: '8px',
                  px: 2,
                  py: 0.75,
                  ...(isActive(item.path) && {
                    bgcolor: isDark ? 'rgba(255,109,0,0.12)' : 'rgba(230,81,0,0.08)',
                  }),
                  '&:hover': {
                    color: 'primary.main',
                    bgcolor: isDark ? 'rgba(255,109,0,0.07)' : 'rgba(230,81,0,0.05)',
                  },
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
                  color: isDark ? 'rgba(255,255,255,0.55)' : 'text.secondary',
                  transition: 'transform 0.3s ease, color 0.2s ease',
                  '&:hover': { transform: 'rotate(30deg)', color: 'primary.main' },
                }}
              >
                {mode === 'light' ? <DarkMode /> : <LightMode />}
              </IconButton>
            </Tooltip>
            {authEnabled && rbacEnabled && (
              <Tooltip title={currentUser?.username ?? t('account.title')} arrow>
                <IconButton
                  onClick={(e) => setAccountAnchor(e.currentTarget)}
                  sx={{
                    ml: 0.5,
                    color: isDark ? 'rgba(255,255,255,0.55)' : 'text.secondary',
                    '&:hover': { color: 'primary.main' },
                  }}
                >
                  <AccountCircle />
                </IconButton>
              </Tooltip>
            )}
            {authEnabled && !rbacEnabled && (
              <Tooltip title={t('login.logout')} arrow>
                <IconButton
                  onClick={logout}
                  sx={{
                    ml: 0.5,
                    color: isDark ? 'rgba(255,255,255,0.55)' : 'text.secondary',
                    '&:hover': { color: 'error.main' },
                  }}
                >
                  <Logout />
                </IconButton>
              </Tooltip>
            )}
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
                  selected={isActive(item.path)}
                  onClick={handleDrawerClose}
                  sx={{
                    ...(isActive(item.path) && {
                      bgcolor: isDark ? 'rgba(255,109,0,0.12)' : 'rgba(230,81,0,0.08)',
                      borderRight: '3px solid',
                      borderColor: 'primary.main',
                    }),
                    '&.Mui-selected': {
                      bgcolor: isDark ? 'rgba(255,109,0,0.12)' : 'rgba(230,81,0,0.08)',
                    },
                  }}
                >
                  <ListItemText
                    primary={item.label}
                    primaryTypographyProps={{
                      fontWeight: 600,
                      color: isActive(item.path) ? 'primary.main' : undefined,
                    }}
                  />
                </ListItemButton>
              </ListItem>
            ))}
          </List>
          <Divider sx={{ borderColor: isDark ? 'rgba(255,255,255,0.06)' : undefined }} />
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
            {authEnabled && rbacEnabled && (
              <Tooltip title={currentUser?.username ?? t('account.title')} arrow>
                <IconButton onClick={(e) => setAccountAnchor(e.currentTarget)} sx={{ '&:hover': { color: 'primary.main' } }}>
                  <AccountCircle />
                </IconButton>
              </Tooltip>
            )}
            {authEnabled && !rbacEnabled && (
              <Tooltip title={t('login.logout')} arrow>
                <IconButton onClick={logout} sx={{ '&:hover': { color: 'error.main' } }}>
                  <Logout />
                </IconButton>
              </Tooltip>
            )}
          </Box>
        </Box>
      </Drawer>

      {/* Account menu (RBAC mode) */}
      <Menu
        anchorEl={accountAnchor}
        open={Boolean(accountAnchor)}
        onClose={() => setAccountAnchor(null)}
        slotProps={{ paper: { sx: { minWidth: 200 } } }}
      >
        <Box sx={{ px: 2, py: 1 }}>
          <Typography variant="body2" sx={{ fontWeight: 600, fontFamily: "'JetBrains Mono', monospace" }}>
            {currentUser?.username}
          </Typography>
          {currentUser?.roleName && (
            <Typography variant="caption" color="text.secondary">{currentUser.roleName}</Typography>
          )}
        </Box>
        <Divider />
        <MenuItem onClick={() => { setAccountAnchor(null); setChangePwOpen(true) }}>
          <ListItemIcon><Password fontSize="small" /></ListItemIcon>
          <ListItemText>{t('account.changePassword')}</ListItemText>
        </MenuItem>
        <MenuItem onClick={() => { setAccountAnchor(null); logout() }}>
          <ListItemIcon><Logout fontSize="small" color="error" /></ListItemIcon>
          <ListItemText>{t('login.logout')}</ListItemText>
        </MenuItem>
      </Menu>

      <ChangePasswordDialog open={changePwOpen} onClose={() => setChangePwOpen(false)} />
    </>
  )
}
