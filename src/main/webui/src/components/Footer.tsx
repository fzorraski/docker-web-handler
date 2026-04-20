// Footer — dark surface bar with monospace stat counters and subtle border separation.
// Uses theme-aware tokens for background and text colors.

import { useState, useEffect, useCallback } from 'react'
import { Box, Typography, Tooltip, useTheme, Dialog, DialogTitle, DialogContent, LinearProgress, IconButton, Table, TableBody, TableCell, TableRow, TableHead } from '@mui/material'
import { Dns, DeleteSweep, Storage, CameraAlt, SettingsBackupRestore, Schedule, Memory, Speed, SdStorage, Close, Monitor, Assessment, Description, DeleteForever, SwapHoriz } from '@mui/icons-material'
import { useTranslation } from 'react-i18next'

interface Stats {
  containers: number
  imagesDeleted: number
  dumps: number
  snapshots: number
  restores: number
  schedulesExecuted: number
  logsAnalyzed: number
  databasesDeleted: number
  migrationsExecuted: number
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
  const [statsModalOpen, setStatsModalOpen] = useState(false)
  const [appVersion, setAppVersion] = useState<string | null>(null)

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
    fetch('/api/stats/info').then(r => r.ok ? r.json() : null).then(d => { if (d?.version) setAppVersion(d.version) }).catch(() => {})
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
      <Box
        sx={{ flex: 1, display: 'flex', alignItems: 'center', gap: 0.5, cursor: stats ? 'pointer' : 'default' }}
        onClick={() => stats && setStatsModalOpen(true)}
      >
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
              {stats.logsAnalyzed > 0 && (
                <StatItem icon={<Description />} label={t('footer.logsAnalyzed')} value={stats.logsAnalyzed} isDark={isDark} />
              )}
              {stats.databasesDeleted > 0 && (
                <StatItem icon={<DeleteForever />} label={t('footer.databasesDeleted')} value={stats.databasesDeleted} isDark={isDark} />
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
      {/* Stats modal */}
      {stats && <StatsModal open={statsModalOpen} onClose={() => setStatsModalOpen(false)} stats={stats} isDark={isDark} appVersion={appVersion} />}

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

function StatsModal({ open, onClose, stats, isDark, appVersion }: { open: boolean; onClose: () => void; stats: Stats; isDark: boolean; appVersion: string | null }) {
  const { t, i18n } = useTranslation()
  const startDate = new Date(stats.startedAt)
  const daysRunning = Math.max(1, Math.round((Date.now() - startDate.getTime()) / (1000 * 60 * 60 * 24)))
  const avg = (value: number) => (value / daysRunning).toFixed(1)

  const rows: { icon: React.ReactElement; label: string; total: number }[] = [
    { icon: <Dns color="primary" />, label: t('footer.containers'), total: stats.containers },
    { icon: <DeleteSweep color="action" />, label: t('footer.imagesDeleted'), total: stats.imagesDeleted },
  ]
  if (stats.dumps > 0 || stats.snapshots > 0) {
    rows.push(
      { icon: <Storage color="action" />, label: t('footer.dumps'), total: stats.dumps },
      { icon: <CameraAlt color="action" />, label: t('footer.snapshots'), total: stats.snapshots },
      { icon: <SettingsBackupRestore color="action" />, label: t('footer.restores'), total: stats.restores },
    )
  }
  if (stats.schedulesExecuted > 0) {
    rows.push({ icon: <Schedule color="action" />, label: t('footer.schedulesExecuted'), total: stats.schedulesExecuted })
  }
  if (stats.logsAnalyzed > 0) {
    rows.push({ icon: <Description color="action" />, label: t('footer.logsAnalyzed'), total: stats.logsAnalyzed })
  }
  if (stats.databasesDeleted > 0) {
    rows.push({ icon: <DeleteForever color="action" />, label: t('footer.databasesDeleted'), total: stats.databasesDeleted })
  }
  if (stats.migrationsExecuted > 0) {
    rows.push({ icon: <SwapHoriz color="action" />, label: t('footer.migrationsExecuted'), total: stats.migrationsExecuted })
  }

  return (
    <Dialog open={open} onClose={onClose} maxWidth="sm" fullWidth>
      <DialogTitle sx={{ display: 'flex', alignItems: 'center' }}>
        <Assessment sx={{ mr: 1 }} />
        {t('footer.statsDetails.title')}
        <IconButton onClick={onClose} sx={{ ml: 'auto' }}><Close /></IconButton>
      </DialogTitle>
      <DialogContent dividers>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
          {t('footer.statsDetails.runningSince', { date: formatStartedAt(stats.startedAt, i18n.language), days: daysRunning })}
        </Typography>
        <Table size="small">
          <TableHead>
            <TableRow>
              <TableCell />
              <TableCell sx={{ fontWeight: 600 }}>{t('footer.statsDetails.metric')}</TableCell>
              <TableCell align="right" sx={{ fontWeight: 600 }}>{t('footer.statsDetails.total')}</TableCell>
              <TableCell align="right" sx={{ fontWeight: 600 }}>{t('footer.statsDetails.avgPerDay')}</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {rows.map((row) => (
              <TableRow key={row.label}>
                <TableCell sx={{ width: 32, pr: 0, '& > svg': { fontSize: 18 } }}>{row.icon}</TableCell>
                <TableCell>{row.label}</TableCell>
                <TableCell align="right" sx={{ fontFamily: "'JetBrains Mono', monospace", fontWeight: 700, color: isDark ? 'primary.main' : 'text.primary' }}>
                  {row.total}
                </TableCell>
                <TableCell align="right" sx={{ fontFamily: "'JetBrains Mono', monospace", color: 'text.secondary' }}>
                  {avg(row.total)}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
          {appVersion && (
            <Typography variant="caption" color="text.secondary" sx={{ display: 'block', textAlign: 'center', mt: 2, fontFamily: "'JetBrains Mono', monospace" }}>
              v{appVersion}
            </Typography>
          )}
      </DialogContent>
    </Dialog>
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
