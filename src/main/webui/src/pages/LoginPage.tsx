import { useState } from 'react'
import { Box, Paper, Typography, TextField, Button, Alert, IconButton, Tooltip } from '@mui/material'
import { Lock, DarkMode, LightMode } from '@mui/icons-material'
import { useTranslation } from 'react-i18next'
import { useAuth } from '../components/AuthProvider'
import { useThemeMode } from '../components/ThemeModeProvider'
import LanguageSwitcher from '../components/LanguageSwitcher'

export default function LoginPage() {
  const { t } = useTranslation()
  const { login } = useAuth()
  const { mode, toggleMode } = useThemeMode()
  const [password, setPassword] = useState('')
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    if (!password.trim() || loading) return
    setLoading(true)
    setError('')
    const result = await login(password)
    if (!result.success) {
      setError(result.error || t('login.invalidPassword'))
      setLoading(false)
    }
  }

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
            sx={{
              display: 'grid',
              gridTemplateColumns: '1fr 1fr',
              gap: '3px',
            }}
          >
            {[1, 0.6, 0.6, 0.3].map((opacity, i) => (
              <Box
                key={i}
                sx={{
                  width: 10,
                  height: 10,
                  borderRadius: '2px',
                  bgcolor: 'primary.main',
                  opacity,
                }}
              />
            ))}
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
          {error && (
            <Alert severity="error" sx={{ mb: 2, textAlign: 'left' }}>
              {error}
            </Alert>
          )}
          <TextField
            type="password"
            label={t('login.password')}
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            fullWidth
            autoFocus
            disabled={loading}
            sx={{ mb: 2.5 }}
          />
          <Button
            type="submit"
            variant="contained"
            fullWidth
            size="large"
            disabled={!password.trim() || loading}
            startIcon={<Lock />}
            sx={{ py: 1.3, fontWeight: 600, fontSize: '0.95rem' }}
          >
            {loading ? t('login.signingIn') : t('login.signIn')}
          </Button>
        </form>
      </Paper>
    </Box>
  )
}
