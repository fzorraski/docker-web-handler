import { useState, useEffect, useRef } from 'react'
import {
  Dialog, DialogTitle, DialogContent, DialogActions, Button,
  Box, Typography, Chip, Grid, LinearProgress, Paper,
} from '@mui/material'
import { Monitor, Upload, Download, Storage, Memory, Speed, Apps } from '@mui/icons-material'
import { useTranslation } from 'react-i18next'
import { streamContainerStats, type ContainerStats } from '../services/sseService'
import { formatBytes, formatBytesRate } from '../utils/format'
import useFullScreenDialog from '../hooks/useFullScreenDialog'
import FullscreenToggleButton from './FullscreenToggleButton'

interface Props {
  open: boolean
  containerId: string
  containerName: string
  onClose: () => void
}

function progressColor(percent: number): 'success' | 'warning' | 'error' {
  if (percent >= 85) return 'error'
  if (percent >= 60) return 'warning'
  return 'success'
}

export default function ContainerStatsDialog({ open, containerId, containerName, onClose }: Props) {
  const { t } = useTranslation()
  const { fullScreen, toggleFullScreen, resetFullScreen, dialogProps } = useFullScreenDialog()
  const [stats, setStats] = useState<ContainerStats | null>(null)
  const [connected, setConnected] = useState(false)
  const cleanupRef = useRef<(() => void) | null>(null)

  useEffect(() => {
    if (!open || !containerId) return

    setStats(null)
    setConnected(true)

    cleanupRef.current = streamContainerStats(
      containerId,
      (s) => setStats(s),
      () => setConnected(false),
    )

    return () => {
      cleanupRef.current?.()
      cleanupRef.current = null
    }
  }, [open, containerId])

  function handleClose() {
    cleanupRef.current?.()
    cleanupRef.current = null
    setConnected(false)
    setStats(null)
    resetFullScreen()
    onClose()
  }

  const mono = { fontFamily: "'JetBrains Mono', monospace" }

  return (
    <Dialog open={open} onClose={handleClose} maxWidth="md" fullWidth {...dialogProps}>
      <DialogTitle sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
        <Monitor />
        {t('containers.stats.title', { name: containerName })}
        <Chip
          label={connected ? t('containers.stats.connected') : t('containers.stats.disconnected')}
          color={connected ? 'success' : 'default'}
          size="small"
          variant="outlined"
          sx={{ ml: 1 }}
        />
        <Box sx={{ ml: 'auto', display: 'flex', gap: 0.5 }}>
          <FullscreenToggleButton fullScreen={fullScreen} onToggle={toggleFullScreen} />
        </Box>
      </DialogTitle>
      <DialogContent dividers sx={{ pt: 3 }}>
        {!stats ? (
          <Box sx={{ textAlign: 'center', py: 6, color: 'text.secondary' }}>
            <Typography>{connected ? t('containers.stats.loading') : t('containers.stats.disconnected')}</Typography>
          </Box>
        ) : (
          <Grid container spacing={2.5}>
            {/* CPU */}
            <Grid size={{ xs: 12, md: 6 }}>
              <Paper variant="outlined" sx={{ p: 2.5, height: '100%' }}>
                <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 2 }}>
                  <Speed color="primary" />
                  <Typography variant="subtitle2" fontWeight={600}>{t('containers.stats.cpu')}</Typography>
                </Box>
                <Typography variant="h4" sx={{ ...mono, fontWeight: 700, mb: 1 }}>
                  {stats.cpuPercent.toFixed(1)}%
                </Typography>
                <LinearProgress
                  variant="determinate"
                  value={Math.min(stats.cpuPercent, 100)}
                  color={progressColor(stats.cpuPercent)}
                  sx={{ height: 8, borderRadius: 1 }}
                />
              </Paper>
            </Grid>

            {/* Memory */}
            <Grid size={{ xs: 12, md: 6 }}>
              <Paper variant="outlined" sx={{ p: 2.5, height: '100%' }}>
                <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 2 }}>
                  <Memory color="secondary" />
                  <Typography variant="subtitle2" fontWeight={600}>{t('containers.stats.memory')}</Typography>
                </Box>
                <Typography variant="h4" sx={{ ...mono, fontWeight: 700, mb: 0.5 }}>
                  {stats.memoryPercent.toFixed(1)}%
                </Typography>
                <Typography variant="body2" color="text.secondary" sx={{ ...mono, mb: 1 }}>
                  {formatBytes(stats.memoryUsage)} / {formatBytes(stats.memoryLimit)}
                </Typography>
                <LinearProgress
                  variant="determinate"
                  value={Math.min(stats.memoryPercent, 100)}
                  color={progressColor(stats.memoryPercent)}
                  sx={{ height: 8, borderRadius: 1 }}
                />
              </Paper>
            </Grid>

            {/* Network I/O */}
            <Grid size={{ xs: 12, md: 6 }}>
              <Paper variant="outlined" sx={{ p: 2.5, height: '100%' }}>
                <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 2 }}>
                  <Download color="info" />
                  <Typography variant="subtitle2" fontWeight={600}>{t('containers.stats.network')}</Typography>
                </Box>
                <Box sx={{ display: 'flex', flexDirection: 'column', gap: 1.5 }}>
                  <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                    <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5 }}>
                      <Download fontSize="small" color="success" />
                      <Typography variant="body2" color="text.secondary">{t('containers.stats.received')}</Typography>
                    </Box>
                    <Box sx={{ textAlign: 'right' }}>
                      <Typography variant="body1" sx={{ ...mono, fontWeight: 600 }}>{formatBytes(stats.networkRxBytes)}</Typography>
                      <Typography variant="caption" color="text.secondary" sx={mono}>{formatBytesRate(stats.networkRxRate)}</Typography>
                    </Box>
                  </Box>
                  <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                    <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5 }}>
                      <Upload fontSize="small" color="warning" />
                      <Typography variant="body2" color="text.secondary">{t('containers.stats.transmitted')}</Typography>
                    </Box>
                    <Box sx={{ textAlign: 'right' }}>
                      <Typography variant="body1" sx={{ ...mono, fontWeight: 600 }}>{formatBytes(stats.networkTxBytes)}</Typography>
                      <Typography variant="caption" color="text.secondary" sx={mono}>{formatBytesRate(stats.networkTxRate)}</Typography>
                    </Box>
                  </Box>
                </Box>
              </Paper>
            </Grid>

            {/* Block I/O */}
            <Grid size={{ xs: 12, md: 6 }}>
              <Paper variant="outlined" sx={{ p: 2.5, height: '100%' }}>
                <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 2 }}>
                  <Storage color="warning" />
                  <Typography variant="subtitle2" fontWeight={600}>{t('containers.stats.blockIo')}</Typography>
                </Box>
                <Box sx={{ display: 'flex', flexDirection: 'column', gap: 1.5 }}>
                  <Box sx={{ display: 'flex', justifyContent: 'space-between' }}>
                    <Typography variant="body2" color="text.secondary">{t('containers.stats.read')}</Typography>
                    <Typography variant="body1" sx={{ ...mono, fontWeight: 600 }}>{formatBytes(stats.blockReadBytes)}</Typography>
                  </Box>
                  <Box sx={{ display: 'flex', justifyContent: 'space-between' }}>
                    <Typography variant="body2" color="text.secondary">{t('containers.stats.written')}</Typography>
                    <Typography variant="body1" sx={{ ...mono, fontWeight: 600 }}>{formatBytes(stats.blockWriteBytes)}</Typography>
                  </Box>
                </Box>
              </Paper>
            </Grid>

            {/* PIDs */}
            <Grid size={{ xs: 12 }}>
              <Paper variant="outlined" sx={{ p: 2, display: 'flex', alignItems: 'center', gap: 1.5 }}>
                <Apps color="action" />
                <Typography variant="subtitle2" fontWeight={600}>{t('containers.stats.pids')}</Typography>
                <Typography variant="body1" sx={{ ...mono, fontWeight: 600, ml: 'auto' }}>{stats.pids}</Typography>
              </Paper>
            </Grid>
          </Grid>
        )}
      </DialogContent>
      <DialogActions sx={{ px: 3, py: 2 }}>
        <Button onClick={handleClose} color="inherit">{t('common.close')}</Button>
      </DialogActions>
    </Dialog>
  )
}
