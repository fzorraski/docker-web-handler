// Footer — dark surface bar with monospace stat counters and subtle border separation.
// Uses theme-aware tokens for background and text colors.

import { useState, useEffect } from 'react'
import { Box, Typography, Tooltip, useTheme } from '@mui/material'
import { Dns, DeleteSweep, Storage, CameraAlt, SettingsBackupRestore } from '@mui/icons-material'
import { useTranslation } from 'react-i18next'

interface Stats {
  containers: number
  imagesDeleted: number
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
        bgcolor: isDark ? '#13151C' : '#FFFFFF',
        color: isDark ? 'text.secondary' : 'text.secondary',
        borderTop: isDark ? '1px solid rgba(255,255,255,0.07)' : '1px solid rgba(0,0,0,0.1)',
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
                  opacity: isDark ? 0.4 : 0.55,
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
              <StatItem icon={<Dns />} label={t('footer.containers')} value={stats.containers} isDark={isDark} />
              <StatItem icon={<DeleteSweep />} label={t('footer.imagesDeleted')} value={stats.imagesDeleted} isDark={isDark} />
              {(stats.dumps > 0 || stats.snapshots > 0) && (
                <>
                  <StatItem icon={<Storage />} label={t('footer.dumps')} value={stats.dumps} isDark={isDark} />
                  <StatItem icon={<CameraAlt />} label={t('footer.snapshots')} value={stats.snapshots} isDark={isDark} />
                  <StatItem icon={<SettingsBackupRestore />} label={t('footer.restores')} value={stats.restores} isDark={isDark} />
                </>
              )}
            </Box>
          </>
        )}
      </Box>

      {/* Center: copyright */}
      <Typography variant="body2" sx={{ fontSize: '0.75rem', opacity: isDark ? 0.5 : 0.6 }}>
        {t('footer.copyright')}
      </Typography>

      {/* Right: spacer for balance */}
      <Box sx={{ flex: 1 }} />
    </Box>
  )
}

function StatItem({ icon, label, value, isDark }: { icon: React.ReactElement; label: string; value: number; isDark: boolean }) {
  return (
    <Tooltip title={label} arrow>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.4, cursor: 'default' }}>
        <Box sx={{ display: 'flex', opacity: isDark ? 0.4 : 0.55, '& > svg': { fontSize: 13 } }}>{icon}</Box>
        <Typography
          variant="body2"
          sx={{
            fontSize: '0.72rem',
            fontFamily: "'JetBrains Mono', monospace",
            fontWeight: 700,
            color: isDark ? 'primary.main' : 'text.primary',
          }}
        >
          {value}
        </Typography>
      </Box>
    </Tooltip>
  )
}
