// Hero banner — geometric dark design with subtle cyan grid lines and radial glow.
// Technical monospace title reinforces the DevOps tool identity.

import { Box, Typography, Button, useTheme } from '@mui/material'
import { Link } from 'react-router-dom'
import { useTranslation } from 'react-i18next'

interface Props {
  linkTo: string
  linkLabel: string
}

export default function HeroBanner({ linkTo, linkLabel }: Props) {
  const theme = useTheme()
  const isDark = theme.palette.mode === 'dark'
  const { t } = useTranslation()

  return (
    <Box
      sx={{
        position: 'relative',
        overflow: 'hidden',
        bgcolor: isDark ? 'background.default' : '#1C2025',
        color: 'white',
        py: { xs: 6, md: 8 },
        textAlign: 'center',
        borderBottom: isDark ? '1px solid rgba(255,109,0,0.15)' : 'none',
      }}
    >
      {/* Subtle grid pattern */}
      <Box
        sx={{
          position: 'absolute',
          inset: 0,
          backgroundImage: isDark
            ? 'linear-gradient(rgba(255,109,0,0.04) 1px, transparent 1px), linear-gradient(90deg, rgba(255,109,0,0.04) 1px, transparent 1px)'
            : 'linear-gradient(rgba(255,109,0,0.06) 1px, transparent 1px), linear-gradient(90deg, rgba(255,109,0,0.06) 1px, transparent 1px)',
          backgroundSize: '60px 60px',
          pointerEvents: 'none',
        }}
      />

      {/* Radial glow behind content */}
      <Box
        sx={{
          position: 'absolute',
          top: '50%',
          left: '50%',
          transform: 'translate(-50%, -50%)',
          width: '50%',
          height: '200%',
          background: isDark
            ? 'radial-gradient(ellipse, rgba(255,109,0,0.07) 0%, transparent 70%)'
            : 'radial-gradient(ellipse, rgba(255,109,0,0.08) 0%, transparent 70%)',
          pointerEvents: 'none',
        }}
      />

      <Box sx={{ position: 'relative', zIndex: 1, px: 2 }}>
        <Typography
          variant="h2"
          sx={{
            fontSize: { xs: '1.75rem', sm: '2.5rem', md: '3rem' },
            fontWeight: 800,
            fontFamily: "'JetBrains Mono', monospace",
            letterSpacing: '-0.03em',
            mb: 1,
          }}
        >
          {t('hero.title')}
        </Typography>

        <Typography
          variant="body1"
          sx={{
            mb: 4,
            color: isDark ? 'text.secondary' : 'rgba(255,255,255,0.75)',
            fontWeight: 400,
            maxWidth: 420,
            mx: 'auto',
            fontSize: { xs: '0.9rem', md: '1rem' },
            letterSpacing: '0.01em',
          }}
        >
          {t('hero.subtitle')}
        </Typography>

        <Button
          component={Link}
          to={linkTo}
          variant="outlined"
          size="large"
          sx={{
            color: isDark ? 'primary.main' : '#FF6D00',
            borderColor: isDark ? 'rgba(255,109,0,0.5)' : 'rgba(255,109,0,0.5)',
            borderRadius: '6px',
            px: 4,
            py: 1,
            fontWeight: 600,
            letterSpacing: '0.02em',
            '&:hover': {
              borderColor: isDark ? 'primary.main' : '#FF6D00',
              bgcolor: isDark ? 'rgba(255,109,0,0.1)' : 'rgba(255,109,0,0.12)',
            },
          }}
        >
          {linkLabel}
        </Button>
      </Box>
    </Box>
  )
}
