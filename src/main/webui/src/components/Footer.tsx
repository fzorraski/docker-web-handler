import { useState, useEffect } from 'react'
import { Box, Typography, Tooltip, useTheme } from '@mui/material'
import { Dns, PhotoLibrary, Storage, CameraAlt, SettingsBackupRestore } from '@mui/icons-material'
import { useTranslation } from 'react-i18next'

interface Stats {
  containers: number
  images: number
  dumps: number
  snapshots: number
  restores: number
  startedAt: string
}

function formatStartedAt(iso: string, lang: string): string {
  const d = new Date(iso)
  if (isNaN(d.getTime())) return iso
  const locale = lang === 'pt-BR' ? 'pt-BR' : lang === 'es' ? 'es' : 'en'
  return d.toLocaleDateString(locale, { day: '2-digit', month: '2-digit', year: 'numeric' })
    + ' ' + d.toLocaleTimeString(locale, { hour: '2-digit', minute: '2-digit' })
}

export default function Footer() {
  const { t, i18n } = useTranslation()
  const isDark = useTheme().palette.mode === 'dark'
  const [stats, setStats] = useState<Stats | null>(null)

  useEffect(() => {
    const load = () => fetch('/api/stats/summary')
      .then(r => r.ok ? r.json() : null)
      .then(setStats)
      .catch(() => {})

    load()
    const interval = setInterval(load, 30000)
    return () => clearInterval(interval)
  }, [])

  return (
    <Box
      component="footer"
      sx={{
        bgcolor: isDark ? '#13151C' : 'primary.dark',
        color: isDark ? 'text.secondary' : 'primary.contrastText',
        borderTop: isDark ? '1px solid rgba(255,255,255,0.07)' : 'none',
        py: 1.25,
        px: 3,
        mt: 'auto',
        display: 'flex',
        alignItems: 'center',
      }}
    >
      {/* Left: stats overview */}
      <Box sx={{ flex: 1, display: 'flex', alignItems: 'center', gap: 0.5 }}>
        {stats && (
          <>
            <Tooltip title={t('footer.startedAt', { date: formatStartedAt(stats.startedAt, i18n.language) })} arrow>
              <Typography
                variant="body2"
                sx={{
                  fontSize: '0.65rem',
                  opacity: 0.4,
                  letterSpacing: '0.04em',
                  fontWeight: 500,
                  mr: 0.5,
                  cursor: 'default',
                  fontStyle: 'italic',
                }}
              >
                {t('footer.soFar')}
              </Typography>
            </Tooltip>
            <Box sx={{ display: 'flex', gap: 1.5 }}>
              <StatItem icon={<Dns />} label={t('footer.containers')} value={stats.containers} />
              <StatItem icon={<PhotoLibrary />} label={t('footer.images')} value={stats.images} />
              {(stats.dumps > 0 || stats.snapshots > 0) && (
                <>
                  <StatItem icon={<Storage />} label={t('footer.dumps')} value={stats.dumps} />
                  <StatItem icon={<CameraAlt />} label={t('footer.snapshots')} value={stats.snapshots} />
                  <StatItem icon={<SettingsBackupRestore />} label={t('footer.restores')} value={stats.restores} />
                </>
              )}
            </Box>
          </>
        )}
      </Box>

      {/* Center: copyright */}
      <Typography variant="body2" sx={{ fontSize: '0.75rem', opacity: 0.5 }}>
        {t('footer.copyright')}
      </Typography>

      {/* Right: spacer for balance */}
      <Box sx={{ flex: 1 }} />
    </Box>
  )
}

function StatItem({ icon, label, value }: { icon: React.ReactElement; label: string; value: number }) {
  return (
    <Tooltip title={label} arrow>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.4, cursor: 'default' }}>
        <Box sx={{ display: 'flex', opacity: 0.4, '& > svg': { fontSize: 13 } }}>{icon}</Box>
        <Typography variant="body2" sx={{ fontSize: '0.72rem', fontFamily: 'monospace', fontWeight: 700 }}>
          {value}
        </Typography>
      </Box>
    </Tooltip>
  )
}
