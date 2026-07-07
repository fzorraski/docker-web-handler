import { useState, useEffect } from 'react'
import { Box, Paper, Typography, TextField, Button, Alert, IconButton, Tooltip } from '@mui/material'
import { Lock, DarkMode, LightMode } from '@mui/icons-material'
import { useTranslation } from 'react-i18next'
import { useAuth } from '../components/AuthProvider'
import { useThemeMode } from '../components/ThemeModeProvider'
import LanguageSwitcher from '../components/LanguageSwitcher'

export default function LoginPage() {
  const { t } = useTranslation()
  const { login, rbacEnabled } = useAuth()
  const { mode, toggleMode } = useThemeMode()
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)
  const [retryAfter, setRetryAfter] = useState(0)
  const isLocked = retryAfter > 0

  useEffect(() => {
    if (!isLocked) return
    const timer = setInterval(() => {
      setRetryAfter(prev => {
        if (prev <= 1) {
          clearInterval(timer)
          setError('')
          return 0
        }
        return prev - 1
      })
    }, 1000)
    return () => clearInterval(timer)
  }, [isLocked])

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    if (!password.trim() || loading || retryAfter > 0) return
    if (rbacEnabled && !username.trim()) return
    setLoading(true)
    setError('')
    const result = await login(password, rbacEnabled ? username.trim() : undefined)
    if (!result.success) {
      if (result.retryAfter) {
        setRetryAfter(result.retryAfter)
        setError(t('login.tooManyAttempts', { seconds: result.retryAfter }))
      } else {
        setError(result.error || t(rbacEnabled ? 'login.invalidCredentials' : 'login.invalidPassword'))
      }
      setLoading(false)
    }
  }

  const errorMessage = isLocked
    ? t('login.tooManyAttempts', { seconds: retryAfter })
    : error

  return (
    <Box
      sx={{
        minHeight: '100vh',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        position: 'relative',
        bgcolor: 'background.default',
        // Subtle grid pattern matching HeroBanner aesthetic
        backgroundImage: (theme) =>
          theme.palette.mode === 'dark'
            ? 'radial-gradient(circle at 50% 40%, rgba(255,109,0,0.06) 0%, transparent 60%)'
            : 'radial-gradient(circle at 50% 40%, rgba(230,81,0,0.04) 0%, transparent 60%)',
      }}
    >
      {/* Top-right controls */}
      <Box sx={{ position: 'absolute', top: 16, right: 16, display: 'flex', alignItems: 'center', gap: 1 }}>
        <LanguageSwitcher />
        <Tooltip title={mode === 'light' ? t('navbar.darkMode') : t('navbar.lightMode')} arrow>
          <IconButton
            onClick={toggleMode}
            sx={{ color: 'text.secondary', '&:hover': { color: 'primary.main' } }}
          >
            {mode === 'light' ? <DarkMode /> : <LightMode />}
          </IconButton>
        </Tooltip>
      </Box>

      <Paper
        elevation={8}
        sx={{
          p: 5,
          width: '100%',
          maxWidth: 400,
          mx: 2,
          borderRadius: 3,
          border: '1px solid',
          borderColor: 'divider',
          textAlign: 'center',
        }}
      >
        {/* Brand */}
        <Box sx={{ display: 'flex', justifyContent: 'center', mb: 2 }}>
          <Box
            component="svg"
            viewBox="0 0 64 64"
            sx={{ width: 40, height: 40 }}
          >
            <defs>
              <linearGradient id="login-a" x1="0" y1="0" x2="1" y2="1">
                <stop offset="0%" stopColor="#FFAB40" />
                <stop offset="100%" stopColor="#FF8F33" />
              </linearGradient>
              <linearGradient id="login-b" x1="1" y1="0" x2="0" y2="1">
                <stop offset="0%" stopColor="#FF6D00" />
                <stop offset="100%" stopColor="#BF360C" />
              </linearGradient>
              <linearGradient id="login-c" x1="0" y1="0" x2="1" y2="1">
                <stop offset="0%" stopColor="#FF8F33" />
                <stop offset="100%" stopColor="#E65100" />
              </linearGradient>
            </defs>
            <g opacity={0.8}>
              <path d="M50 2L55 5L50 8L45 5Z" fill="#FFAB40" />
              <path d="M45 5L50 8L50 13L45 10Z" fill="#BF360C" />
              <path d="M55 5L50 8L50 13L55 10Z" fill="#E65100" />
            </g>
            <path d="M32 14L54 26L32 38L10 26Z" fill="url(#login-a)" />
            <path d="M10 26L32 38L32 56L10 44Z" fill="url(#login-b)" />
            <path d="M54 26L32 38L32 56L54 44Z" fill="url(#login-c)" />
          </Box>
        </Box>
        <Typography
          variant="h5"
          fontWeight={700}
          sx={{ fontFamily: "'JetBrains Mono', monospace", letterSpacing: '-0.02em', mb: 1 }}
        >
          {t('login.title')}
        </Typography>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 3 }}>
          {t('login.subtitle')}
        </Typography>

        <form onSubmit={handleSubmit}>
          {errorMessage && (
            <Alert severity={isLocked ? 'warning' : 'error'} sx={{ mb: 2, textAlign: 'left' }}>
              {errorMessage}
            </Alert>
          )}
          {rbacEnabled && (
            <TextField
              label={t('login.username')}
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              fullWidth
              autoFocus
              autoComplete="username"
              disabled={loading || isLocked}
              sx={{ mb: 2.5 }}
            />
          )}
          <TextField
            type="password"
            label={t('login.password')}
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            fullWidth
            autoFocus={!rbacEnabled}
            autoComplete="current-password"
            disabled={loading || isLocked}
            sx={{ mb: 2.5 }}
          />
          <Button
            type="submit"
            variant="contained"
            fullWidth
            size="large"
            disabled={!password.trim() || (rbacEnabled && !username.trim()) || loading || isLocked}
            startIcon={<Lock />}
            sx={{ py: 1.3, fontWeight: 600, fontSize: '0.95rem' }}
          >
            {loading ? t('login.signingIn') : isLocked ? t('login.tooManyAttempts', { seconds: retryAfter }) : t('login.signIn')}
          </Button>
        </form>
      </Paper>
    </Box>
  )
}
