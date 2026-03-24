// Footer — dark surface bar with monospace stat counters and subtle border separation.
// Uses theme-aware tokens for background and text colors.

import { useState, useEffect, useCallback } from 'react'
import { Box, Typography, Tooltip, useTheme, Dialog, DialogTitle, DialogContent, LinearProgress, IconButton } from '@mui/material'
import { Dns, DeleteSweep, Storage, CameraAlt, SettingsBackupRestore, Schedule, Memory, Speed, SdStorage, Close, Monitor } from '@mui/icons-material'
import { useTranslation } from 'react-i18next'

interface Stats {
  containers: number
  imagesDeleted: number
  dumps: number
  snapshots: number
  restores: number
  schedulesExecuted: number
  startedAt: string
}

interface HostStats {
  memory: { totalMb?: number; usedMb?: number; availableMb?: number; usagePercent?: number; guardEnabled?: boolean; guardThresholdMb?: number }
  cpu: { usagePercent?: number; cores?: number }
  disk: { totalGb?: number; usedGb?: number; usagePercent?: number }
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
  const [hostStats, setHostStats] = useState<HostStats | null>(null)
  const [hostModalOpen, setHostModalOpen] = useState(false)

  const loadHost = useCallback(() => {
    fetch('/api/stats/host').then(r => r.ok ? r.json() : null).then(setHostStats).catch(() => {})
  }, [])

  useEffect(() => {
    const loadSummary = () => fetch('/api/stats/summary')
      .then(r => r.ok ? r.json() : null)
      .then(setStats)
      .catch(() => {})

    loadSummary()
    loadHost()
    const summaryInterval = setInterval(loadSummary, 30000)
    const hostInterval = setInterval(loadHost, 60000)
    return () => { clearInterval(summaryInterval); clearInterval(hostInterval) }
  }, [loadHost])

  // Fast refresh while modal is open
  useEffect(() => {
    if (!hostModalOpen) return
    loadHost()
    const interval = setInterval(loadHost, 5000)
    return () => clearInterval(interval)
  }, [hostModalOpen, loadHost])

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
              {stats.schedulesExecuted > 0 && (
                <StatItem icon={<Schedule />} label={t('footer.schedulesExecuted')} value={stats.schedulesExecuted} isDark={isDark} />
              )}
            </Box>
          </>
        )}
      </Box>

      {/* Center: copyright */}
      <Typography variant="body2" sx={{ fontSize: '0.75rem', opacity: isDark ? 0.5 : 0.6 }}>
        {t('footer.copyright')}
      </Typography>

      {/* Right: host usage */}
      <Box
        sx={{ flex: 1, display: 'flex', alignItems: 'center', justifyContent: 'flex-end', gap: 1.5, cursor: hostStats ? 'pointer' : 'default' }}
        onClick={() => hostStats && setHostModalOpen(true)}
      >
        {hostStats && (
          <>
            {hostStats.cpu.usagePercent != null && (
              <StatItem
                icon={<Speed />}
                label={t('footer.cpuUsage', { percent: hostStats.cpu.usagePercent, cores: hostStats.cpu.cores ?? '?' })}
                displayValue={`${hostStats.cpu.usagePercent}%`}
                isDark={isDark}
              />
            )}
            {hostStats.memory.usagePercent != null && (
              <StatItem
                icon={<Memory />}
                label={t('footer.memoryUsage', { used: ((hostStats.memory.usedMb ?? 0) / 1024).toFixed(1), total: ((hostStats.memory.totalMb ?? 0) / 1024).toFixed(1) })}
                displayValue={`${hostStats.memory.usagePercent}%`}
                isDark={isDark}
              />
            )}
            {hostStats.disk.usagePercent != null && (
              <StatItem
                icon={<SdStorage />}
                label={t('footer.diskUsage', { used: hostStats.disk.usedGb ?? 0, total: hostStats.disk.totalGb ?? 0 })}
                displayValue={`${hostStats.disk.usagePercent}%`}
                isDark={isDark}
              />
            )}
          </>
        )}
      </Box>
      {/* Host usage modal */}
      <Dialog open={hostModalOpen} onClose={() => setHostModalOpen(false)} maxWidth="sm" fullWidth>
        <DialogTitle sx={{ display: 'flex', alignItems: 'center' }}>
          <Monitor sx={{ mr: 1 }} />
          {t('footer.hostDetails.title')}
          <IconButton onClick={() => setHostModalOpen(false)} sx={{ ml: 'auto' }}><Close /></IconButton>
        </DialogTitle>
        <DialogContent dividers>
          {hostStats && (
            <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2.5, py: 0.5 }}>
              {/* CPU */}
              {hostStats.cpu.usagePercent != null && (
                <UsageBar
                  icon={<Speed color="primary" />}
                  title={t('footer.hostDetails.cpu')}
                  percent={hostStats.cpu.usagePercent}
                  detail={t('footer.hostDetails.cpuDetail', { percent: hostStats.cpu.usagePercent, cores: hostStats.cpu.cores ?? '?' })}
                  isDark={isDark}
                />
              )}
              {/* Memory */}
              {hostStats.memory.usagePercent != null && (
                <UsageBar
                  icon={<Memory color="secondary" />}
                  title={t('footer.hostDetails.memory')}
                  percent={hostStats.memory.usagePercent}
                  detail={t('footer.hostDetails.memoryDetail', {
                    used: ((hostStats.memory.usedMb ?? 0) / 1024).toFixed(1),
                    total: ((hostStats.memory.totalMb ?? 0) / 1024).toFixed(1),
                    available: (((hostStats.memory.totalMb ?? 0) - (hostStats.memory.usedMb ?? 0)) / 1024).toFixed(1),
                  })}
                  isDark={isDark}
                  thresholdPercent={hostStats.memory.guardEnabled && hostStats.memory.guardThresholdMb && hostStats.memory.totalMb
                    ? Math.round(((hostStats.memory.totalMb - hostStats.memory.guardThresholdMb) / hostStats.memory.totalMb) * 100)
                    : undefined}
                  thresholdLabel={hostStats.memory.guardEnabled && hostStats.memory.guardThresholdMb
                    ? t('footer.hostDetails.guardThreshold', { value: (hostStats.memory.guardThresholdMb / 1024).toFixed(1) })
                    : undefined}
                />
              )}
              {/* Disk */}
              {hostStats.disk.usagePercent != null && (
                <UsageBar
                  icon={<SdStorage color="warning" />}
                  title={t('footer.hostDetails.disk')}
                  percent={hostStats.disk.usagePercent}
                  detail={t('footer.hostDetails.diskDetail', {
                    used: hostStats.disk.usedGb ?? 0,
                    total: hostStats.disk.totalGb ?? 0,
                    available: (hostStats.disk.totalGb ?? 0) - (hostStats.disk.usedGb ?? 0),
                  })}
                  isDark={isDark}
                />
              )}
            </Box>
          )}
        </DialogContent>
      </Dialog>
    </Box>
  )
}

function UsageBar({ icon, title, percent, detail, isDark, thresholdPercent, thresholdLabel }: {
  icon: React.ReactElement; title: string; percent: number; detail: string; isDark: boolean
  thresholdPercent?: number; thresholdLabel?: string
}) {
  const color = percent >= 90 ? 'error' : percent >= 70 ? 'warning' : 'primary'
  return (
    <Box>
      <Box sx={{ display: 'flex', alignItems: 'center', mb: 0.5 }}>
        <Box sx={{ mr: 1, display: 'flex' }}>{icon}</Box>
        <Typography variant="subtitle2" sx={{ fontWeight: 600, flex: 1 }}>{title}</Typography>
        <Typography
          variant="subtitle2"
          sx={{ fontFamily: "'JetBrains Mono', monospace", fontWeight: 700, color: isDark ? 'primary.main' : 'text.primary' }}
        >
          {percent}%
        </Typography>
      </Box>
      <Box sx={{ position: 'relative' }}>
        <LinearProgress
          variant="determinate"
          value={percent}
          color={color}
          sx={{ height: 6, borderRadius: 3, bgcolor: isDark ? 'rgba(255,255,255,0.08)' : 'rgba(0,0,0,0.08)' }}
        />
        {thresholdPercent != null && thresholdPercent > 0 && thresholdPercent < 100 && (
          <Tooltip title={thresholdLabel ?? ''} arrow placement="top">
            <Box sx={{
              position: 'absolute',
              left: `${thresholdPercent}%`,
              top: -1,
              bottom: -1,
              width: 2,
              bgcolor: 'error.main',
              borderRadius: 0.5,
              cursor: 'default',
              zIndex: 1,
              opacity: 0.8,
            }} />
          </Tooltip>
        )}
      </Box>
      <Typography variant="caption" color="text.secondary" sx={{ mt: 0.25, display: 'block', fontSize: '0.7rem' }}>
        {detail}
      </Typography>
    </Box>
  )
}

function StatItem({ icon, label, value, displayValue, isDark }: { icon: React.ReactElement; label: string; value?: number; displayValue?: string; isDark: boolean }) {
  return (
    <Tooltip title={label} arrow>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.4 }}>
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
          {displayValue ?? value}
        </Typography>
      </Box>
    </Tooltip>
  )
}
