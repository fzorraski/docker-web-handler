import { useState, useEffect, useRef, useCallback } from 'react'
import {
  Dialog, DialogTitle, DialogContent, DialogActions, Button,
  Box, Typography, Chip, Alert, Collapse, IconButton, TextField,
  LinearProgress, Tooltip,
} from '@mui/material'
import { Code, FiberManualRecord, UploadFile, Send, Close } from '@mui/icons-material'
import { useTranslation } from 'react-i18next'
import { useTheme } from '@mui/material/styles'
import { Terminal } from '@xterm/xterm'
import { FitAddon } from '@xterm/addon-fit'
import { WebLinksAddon } from '@xterm/addon-web-links'
import '@xterm/xterm/css/xterm.css'
import { connectTerminal, uploadFileToContainer, type TerminalConnection } from '../services/terminalService'
import useFullScreenDialog from '../hooks/useFullScreenDialog'
import FullscreenToggleButton from './FullscreenToggleButton'

interface Props {
  open: boolean
  ticket: string
  containerName: string
  containerId: string
  onClose: () => void
  uploadEnabled?: boolean
  uploadMaxSizeMb?: number
  uploadDefaultPath?: string
  terminalPassword?: string
}

type Status = 'connecting' | 'connected' | 'disconnected' | 'error'

export default function ContainerTerminalDialog({
  open, ticket, containerName, containerId, onClose,
  uploadEnabled, uploadMaxSizeMb, uploadDefaultPath, terminalPassword,
}: Props) {
  const { t } = useTranslation()
  const theme = useTheme()
  const { fullScreen, toggleFullScreen, resetFullScreen, dialogProps } = useFullScreenDialog()

  const [status, setStatus] = useState<Status>('connecting')
  const [errorMessage, setErrorMessage] = useState('')

  const [uploadOpen, setUploadOpen] = useState(false)
  const [uploadFile, setUploadFile] = useState<File | null>(null)
  const [remotePath, setRemotePath] = useState(uploadDefaultPath ?? '/tmp')
  const [uploadProgress, setUploadProgress] = useState(0)
  const [uploading, setUploading] = useState(false)
  const [uploadResult, setUploadResult] = useState<{ type: 'success' | 'error'; message: string } | null>(null)
  const fileInputRef = useRef<HTMLInputElement>(null)

  const termRef = useRef<HTMLDivElement>(null)
  const xtermRef = useRef<Terminal | null>(null)
  const connectionRef = useRef<TerminalConnection | null>(null)
  const resizeObserverRef = useRef<ResizeObserver | null>(null)
  const mountedRef = useRef(true)

  const disposeTerminal = useCallback(() => {
    resizeObserverRef.current?.disconnect()
    resizeObserverRef.current = null
    xtermRef.current?.dispose()
    xtermRef.current = null
  }, [])

  const cleanup = useCallback(() => {
    connectionRef.current?.close()
    connectionRef.current = null
    disposeTerminal()
  }, [disposeTerminal])

  // Track mounted state
  useEffect(() => {
    mountedRef.current = true
    return () => { mountedRef.current = false }
  }, [])

  const isDark = theme.palette.mode === 'dark'

  // Connect when dialog opens with a ticket
  useEffect(() => {
    if (!open || !ticket) return

    setStatus('connecting')
    setErrorMessage('')
    disposeTerminal()

    const conn = connectTerminal(
      ticket,
      () => { if (mountedRef.current) setStatus('connected') },
      (data) => { xtermRef.current?.write(data) },
      (_code, msg) => {
        if (!mountedRef.current) return
        setStatus('disconnected')
        if (msg) setErrorMessage(msg)
      },
      (msg) => {
        if (!mountedRef.current) return
        setStatus('error')
        setErrorMessage(msg)
      },
      () => {
        if (!mountedRef.current) return
        setStatus((prev) => prev === 'connecting' ? 'error' : 'disconnected')
      },
    )

    connectionRef.current = conn

    return () => {
      conn.close()
      connectionRef.current = null
      disposeTerminal()
    }
  }, [open, ticket, disposeTerminal])

  // Initialize xterm when connected
  useEffect(() => {
    if (status !== 'connected' || !termRef.current) return

    // Dispose previous if any (reconnect scenario)
    disposeTerminal()

    const xtermTheme = isDark
      ? { background: '#1e1e1e', foreground: '#d4d4d4', cursor: '#d4d4d4', selectionBackground: '#264f78' }
      : { background: '#ffffff', foreground: '#1e1e1e', cursor: '#1e1e1e', selectionBackground: '#add6ff' }

    const term = new Terminal({
      cursorBlink: true,
      fontFamily: "'JetBrains Mono', 'Cascadia Code', 'Fira Code', monospace",
      fontSize: 14,
      theme: xtermTheme,
      scrollback: 5000,
    })
    const fitAddon = new FitAddon()
    term.loadAddon(fitAddon)
    term.loadAddon(new WebLinksAddon())
    term.open(termRef.current)

    requestAnimationFrame(() => {
      fitAddon.fit()
      connectionRef.current?.sendResize(term.cols, term.rows)
    })

    term.onData((data) => {
      connectionRef.current?.sendInput(data)
    })

    const container = termRef.current
    const observer = new ResizeObserver(() => {
      if (xtermRef.current) {
        fitAddon.fit()
        connectionRef.current?.sendResize(term.cols, term.rows)
      }
    })
    observer.observe(container)
    resizeObserverRef.current = observer

    xtermRef.current = term
    term.focus()

    return () => {
      observer.disconnect()
      resizeObserverRef.current = null
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps -- only re-init when status becomes 'connected'
  }, [status])

  const resetUpload = useCallback(() => {
    setUploadOpen(false)
    setUploadFile(null)
    setRemotePath(uploadDefaultPath ?? '/tmp')
    setUploadProgress(0)
    setUploading(false)
    setUploadResult(null)
  }, [uploadDefaultPath])

  const handleUpload = useCallback(async () => {
    if (!uploadFile || !containerId) return

    const maxBytes = (uploadMaxSizeMb ?? 100) * 1024 * 1024
    if (uploadFile.size > maxBytes) {
      setUploadResult({ type: 'error', message: t('containers.terminal.fileTooLarge', { max: uploadMaxSizeMb ?? 100 }) })
      return
    }

    setUploading(true)
    setUploadProgress(0)
    setUploadResult(null)

    const res = await uploadFileToContainer(
      containerId,
      uploadFile,
      remotePath,
      terminalPassword ?? '',
      (pct) => setUploadProgress(pct),
    )

    setUploading(false)
    if (res.success) {
      setUploadResult({ type: 'success', message: t('containers.terminal.uploadComplete', { path: remotePath }) })
      setUploadFile(null)
      if (fileInputRef.current) fileInputRef.current.value = ''
    } else {
      setUploadResult({ type: 'error', message: res.error ?? t('containers.terminal.uploadFailed') })
    }
  }, [uploadFile, containerId, remotePath, terminalPassword, uploadMaxSizeMb, t])

  function handleClose() {
    cleanup()
    resetUpload()
    resetFullScreen()
    onClose()
  }

  const statusColor = status === 'connected' ? 'success'
    : status === 'error' || status === 'disconnected' ? 'error'
    : 'default'

  const statusLabel = t(`containers.terminal.${status}` as never)

  return (
    <Dialog open={open} onClose={handleClose} maxWidth="md" fullWidth {...dialogProps}>
      <DialogTitle sx={{ bgcolor: 'grey.900', color: 'white', display: 'flex', alignItems: 'center', gap: 1 }}>
        <Code sx={{ mr: 0.5 }} />
        {t('containers.terminal.title', { name: containerName })}
        <Box sx={{ flex: 1 }} />
        <Chip
          icon={<FiberManualRecord sx={{
            fontSize: 10,
            ...(status === 'connected' && {
              animation: 'pulse 2s ease-in-out infinite',
              '@keyframes pulse': {
                '0%, 100%': { opacity: 1 },
                '50%': { opacity: 0.4 },
              },
            }),
          }} />}
          label={statusLabel}
          size="small"
          color={statusColor}
          variant="outlined"
          sx={{ color: 'white', borderColor: 'rgba(255,255,255,0.3)' }}
        />
        <FullscreenToggleButton fullScreen={fullScreen} onToggle={toggleFullScreen} color="white" />
      </DialogTitle>

      <DialogContent
        dividers
        sx={{
          p: 0,
          bgcolor: isDark ? '#1e1e1e' : '#ffffff',
          display: 'flex',
          flexDirection: 'column',
          ...(fullScreen ? { overflow: 'hidden' } : { minHeight: 400 }),
        }}
      >
        {status === 'connected' && (
          <Box
            ref={termRef}
            sx={{
              flex: 1,
              minHeight: 0,
              p: 0.5,
              '& .xterm': { height: '100%' },
              '& .xterm-viewport': { overflowY: 'auto !important' },
            }}
          />
        )}

        {status === 'connecting' && (
          <Box sx={{ p: 3 }}>
            <Typography color="text.secondary">{t('containers.terminal.connecting')}</Typography>
          </Box>
        )}

        {(status === 'disconnected' || status === 'error') && (
          <Box sx={{ p: 3, display: 'flex', flexDirection: 'column', gap: 2 }}>
            {errorMessage && <Alert severity="error">{errorMessage}</Alert>}
            <Typography color="text.secondary">
              {t('containers.terminal.sessionEnded')}
            </Typography>
          </Box>
        )}
      </DialogContent>

      {uploadEnabled && status === 'connected' && (
        <Collapse in={uploadOpen}>
          <Box
            sx={{
              bgcolor: isDark ? '#252526' : '#f5f5f5',
              borderTop: `1px solid ${isDark ? '#3c3c3c' : '#ddd'}`,
              px: 2,
              py: 1.5,
              display: 'flex',
              flexDirection: 'column',
              gap: 1,
            }}
          >
            <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5, flexWrap: 'wrap' }}>
              <Button
                variant="outlined"
                component="label"
                size="small"
                disabled={uploading}
                sx={{ textTransform: 'none', minWidth: 0, color: isDark ? '#ccc' : undefined, borderColor: isDark ? '#555' : undefined }}
              >
                {uploadFile ? uploadFile.name : t('containers.terminal.selectFile')}
                <input
                  ref={fileInputRef}
                  type="file"
                  hidden
                  onChange={(e) => {
                    setUploadFile(e.target.files?.[0] ?? null)
                    setUploadResult(null)
                  }}
                />
              </Button>

              <TextField
                size="small"
                label={t('containers.terminal.remotePath')}
                value={remotePath}
                onChange={(e) => setRemotePath(e.target.value)}
                disabled={uploading}
                sx={{
                  minWidth: 160,
                  '& .MuiInputBase-root': { fontSize: '0.85rem', height: 36 },
                  '& .MuiInputLabel-root': { fontSize: '0.85rem' },
                }}
              />

              <Tooltip title={t('containers.terminal.uploadToContainer')}>
                <span>
                  <IconButton
                    onClick={handleUpload}
                    disabled={!uploadFile || !remotePath || uploading}
                    size="small"
                    color="primary"
                  >
                    <Send sx={{ fontSize: 20 }} />
                  </IconButton>
                </span>
              </Tooltip>

              <Box sx={{ flex: 1 }} />

              <IconButton size="small" onClick={() => { resetUpload() }} sx={{ color: isDark ? '#aaa' : undefined }}>
                <Close sx={{ fontSize: 18 }} />
              </IconButton>
            </Box>

            {uploading && (
              <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5 }}>
                <LinearProgress
                  variant="determinate"
                  value={uploadProgress}
                  sx={{ flex: 1, height: 6, borderRadius: 3 }}
                />
                <Typography variant="caption" sx={{ color: isDark ? '#ccc' : 'text.secondary', minWidth: 36 }}>
                  {uploadProgress}%
                </Typography>
              </Box>
            )}

            {uploadResult && (
              <Alert severity={uploadResult.type} sx={{ py: 0, fontSize: '0.8rem' }} onClose={() => setUploadResult(null)}>
                {uploadResult.message}
              </Alert>
            )}
          </Box>
        </Collapse>
      )}

      <DialogActions sx={{ px: 3, py: 1.5, bgcolor: 'grey.900' }}>
        {uploadEnabled && status === 'connected' && (
          <Tooltip title={t('containers.terminal.uploadFile')}>
            <IconButton
              onClick={() => { setUploadOpen((prev) => !prev); setUploadResult(null) }}
              size="small"
              sx={{
                color: uploadOpen ? 'primary.main' : 'white',
                mr: 'auto',
              }}
            >
              <UploadFile sx={{ fontSize: 20 }} />
            </IconButton>
          </Tooltip>
        )}
        <Button onClick={handleClose} sx={{ color: 'white' }}>
          {t('common.close')}
        </Button>
      </DialogActions>
    </Dialog>
  )
}
