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
        background: isDark
          ? 'linear-gradient(to right, #d4544a, #b04a3a)'
          : 'linear-gradient(to right, #ff6f61, #de6b48)',
        color: 'white',
        py: 8,
        textAlign: 'center',
      }}
    >
      <Typography variant="h2" fontWeight="bold" gutterBottom>
        {t('hero.title')}
      </Typography>
      <Typography variant="h6" sx={{ mb: 3 }}>
        {t('hero.subtitle')}
      </Typography>
      <Button
        component={Link}
        to={linkTo}
        variant="contained"
        size="large"
        sx={{
          bgcolor: 'rgba(255,255,255,0.2)',
          color: 'white',
          borderRadius: '50px',
          px: 4,
          '&:hover': { bgcolor: 'rgba(255,255,255,0.35)' },
        }}
      >
        {linkLabel}
      </Button>
    </Box>
  )
}
