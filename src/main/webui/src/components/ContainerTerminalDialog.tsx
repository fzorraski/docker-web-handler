import { useState, useEffect, useRef, useCallback } from 'react'
import {
  Dialog, DialogTitle, DialogContent, DialogActions, Button,
  Box, Typography, Chip, Alert,
} from '@mui/material'
import { Code, FiberManualRecord } from '@mui/icons-material'
import { useTranslation } from 'react-i18next'
import { useTheme } from '@mui/material/styles'
import { Terminal } from '@xterm/xterm'
import { FitAddon } from '@xterm/addon-fit'
import { WebLinksAddon } from '@xterm/addon-web-links'
import '@xterm/xterm/css/xterm.css'
import { connectTerminal, type TerminalConnection } from '../services/terminalService'
import useFullScreenDialog from '../hooks/useFullScreenDialog'
import FullscreenToggleButton from './FullscreenToggleButton'

interface Props {
  open: boolean
  ticket: string
  containerName: string
  onClose: () => void
}

type Status = 'connecting' | 'connected' | 'disconnected' | 'error'

export default function ContainerTerminalDialog({ open, ticket, containerName, onClose }: Props) {
  const { t } = useTranslation()
  const theme = useTheme()
  const { fullScreen, toggleFullScreen, resetFullScreen, dialogProps } = useFullScreenDialog()

  const [status, setStatus] = useState<Status>('connecting')
  const [errorMessage, setErrorMessage] = useState('')

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

  function handleClose() {
    cleanup()
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

      <DialogActions sx={{ px: 3, py: 1.5, bgcolor: 'grey.900' }}>
        <Button onClick={handleClose} sx={{ color: 'white' }}>
          {t('common.close')}
        </Button>
      </DialogActions>
    </Dialog>
  )
}
