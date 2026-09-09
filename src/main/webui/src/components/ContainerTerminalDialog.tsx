import { useState, useEffect, useRef, useCallback } from 'react'
import {
  Dialog, DialogTitle, DialogContent, DialogActions, Button,
  Box, Typography, Chip, Alert, Collapse, IconButton, TextField,
  LinearProgress, Tooltip,
} from '@mui/material'
import { Code, FiberManualRecord, UploadFile, Send, Close, Image as ImageIcon } from '@mui/icons-material'
import { useTranslation } from 'react-i18next'
import { useTheme } from '@mui/material/styles'
import { Terminal } from '@xterm/xterm'
import { FitAddon } from '@xterm/addon-fit'
import { WebLinksAddon } from '@xterm/addon-web-links'
import '@xterm/xterm/css/xterm.css'
import { connectTerminal, uploadFileToContainer, uploadImageToContainer, type TerminalConnection } from '../services/terminalService'
import { extractImageFiles, hasPlainText, isMacPlatform, isNativePasteChord, isSupportedImage, pasteShortcutLabel } from '../utils/terminalAttachments'
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
  imageUploadEnabled?: boolean
  attachmentsPath?: string
  imageMaxSizeMb?: number
  terminalPassword?: string
}

type Status = 'connecting' | 'connected' | 'disconnected' | 'error'

interface AttachmentState {
  status: 'uploading' | 'done' | 'error'
  progress: number
  message: string
}

export default function ContainerTerminalDialog({
  open, ticket, containerName, containerId, onClose,
  uploadEnabled, uploadMaxSizeMb, uploadDefaultPath, imageUploadEnabled, attachmentsPath, imageMaxSizeMb, terminalPassword,
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

  const [attachment, setAttachment] = useState<AttachmentState | null>(null)
  const [dragActive, setDragActive] = useState(false)
  const attachmentQueueRef = useRef<Promise<void>>(Promise.resolve())
  // Bumped whenever the connection changes or the dialog closes; in-flight uploads compare
  // against it so a late result never lands in another container's prompt.
  const sessionRef = useRef(0)
  const imageUploadEnabledRef = useRef(!!imageUploadEnabled)
  imageUploadEnabledRef.current = !!imageUploadEnabled

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
    sessionRef.current += 1
    attachmentQueueRef.current = Promise.resolve()
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
  const isMac = isMacPlatform()

  // Connect when dialog opens with a ticket
  useEffect(() => {
    if (!open || !ticket) return

    setStatus('connecting')
    setErrorMessage('')
    disposeTerminal()
    sessionRef.current += 1

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
      sessionRef.current += 1
      attachmentQueueRef.current = Promise.resolve()
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

    // With image attachments on, let the browser fire its native paste event for Ctrl+V / Cmd+V.
    // Without this, xterm turns Ctrl+V into ^V on Linux/Windows and no paste ever happens. When the
    // feature is off the terminal keeps stock behavior, so ^V still reaches vim/readline.
    term.attachCustomKeyEventHandler((ev) => {
      if (!imageUploadEnabledRef.current || !isNativePasteChord(ev, isMac)) return true
      // A held chord auto-repeats: swallow the repeats entirely, so they neither paste again
      // (one upload per repeat) nor fall back to xterm, which would send ^V to the shell.
      if (ev.repeat) {
        ev.preventDefault()
        return false
      }
      return false
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

  // Upload one pasted/dropped image, then type its container path into the prompt.
  const attachImage = useCallback(async (file: File, session: number) => {
    if (!containerId || session !== sessionRef.current) return
    if (!isSupportedImage(file)) {
      setAttachment({ status: 'error', progress: 0, message: t('containers.terminal.imageUnsupported') })
      return
    }
    const maxMb = imageMaxSizeMb ?? 10
    if (file.size > maxMb * 1024 * 1024) {
      setAttachment({ status: 'error', progress: 0, message: t('containers.terminal.imageTooLarge', { max: maxMb }) })
      return
    }

    setAttachment({ status: 'uploading', progress: 0, message: t('containers.terminal.imageUploading') })
    const res = await uploadImageToContainer(
      containerId,
      file,
      terminalPassword ?? '',
      (pct) => setAttachment((prev) => prev?.status === 'uploading' ? { ...prev, progress: pct } : prev),
    )
    // The dialog closed or reconnected meanwhile: the file belongs to a session that is gone.
    if (!mountedRef.current || session !== sessionRef.current) return

    if (res.success && res.path) {
      connectionRef.current?.sendInput(res.path + ' ')
      xtermRef.current?.focus()
      setAttachment({ status: 'done', progress: 100, message: t('containers.terminal.imageAttached', { path: res.path }) })
    } else {
      setAttachment({ status: 'error', progress: 0, message: res.error ?? t('containers.terminal.uploadFailed') })
    }
  }, [containerId, terminalPassword, imageMaxSizeMb, t])

  // Serialize uploads so several pasted images land in the prompt in order.
  const enqueueImages = useCallback((files: File[]) => {
    const session = sessionRef.current
    for (const file of files) {
      attachmentQueueRef.current = attachmentQueueRef.current.then(() => attachImage(file, session)).catch(() => {})
    }
  }, [attachImage])

  // Intercept image paste/drop on the terminal; plain text keeps flowing to xterm.
  useEffect(() => {
    const container = termRef.current
    if (!imageUploadEnabled || status !== 'connected' || !container) return

    const onPaste = (e: ClipboardEvent) => {
      // Spreadsheet/browser copies ship text plus a rendered preview image; the text is what was meant.
      if (hasPlainText(e.clipboardData)) return
      const images = extractImageFiles(e.clipboardData)
      if (images.length === 0) return
      e.preventDefault()
      e.stopPropagation()
      enqueueImages(images)
    }
    const onDragOver = (e: DragEvent) => {
      if (!e.dataTransfer || !Array.from(e.dataTransfer.types).includes('Files')) return
      e.preventDefault()
      e.dataTransfer.dropEffect = 'copy'
      setDragActive(true)
    }
    const onDragLeave = (e: DragEvent) => {
      if (e.relatedTarget && container.contains(e.relatedTarget as Node)) return
      setDragActive(false)
    }
    const onDrop = (e: DragEvent) => {
      // Always swallow the drop: the browser's default is to navigate the tab to the dropped file.
      e.preventDefault()
      e.stopPropagation()
      setDragActive(false)
      const images = extractImageFiles(e.dataTransfer)
      if (images.length === 0) {
        if ((e.dataTransfer?.files.length ?? 0) > 0) {
          setAttachment({ status: 'error', progress: 0, message: t('containers.terminal.imageUnsupported') })
        }
        return
      }
      enqueueImages(images)
    }

    container.addEventListener('paste', onPaste, true)
    container.addEventListener('dragover', onDragOver)
    container.addEventListener('dragleave', onDragLeave)
    container.addEventListener('drop', onDrop)
    return () => {
      container.removeEventListener('paste', onPaste, true)
      container.removeEventListener('dragover', onDragOver)
      container.removeEventListener('dragleave', onDragLeave)
      container.removeEventListener('drop', onDrop)
    }
  }, [imageUploadEnabled, status, enqueueImages, t])

  const resetUpload = useCallback(() => {
    setUploadOpen(false)
    setAttachment(null)
    setDragActive(false)
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
              outline: dragActive ? '2px dashed' : 'none',
              outlineColor: 'primary.main',
              outlineOffset: -4,
              transition: 'outline-color 120ms',
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

      {imageUploadEnabled && attachment && (
        <Box
          sx={{
            bgcolor: isDark ? '#252526' : '#f5f5f5',
            borderTop: `1px solid ${isDark ? '#3c3c3c' : '#ddd'}`,
            px: 2,
            py: 0.75,
            display: 'flex',
            alignItems: 'center',
            gap: 1.5,
          }}
        >
          {attachment.status === 'uploading' ? (
            <>
              <ImageIcon sx={{ fontSize: 18, color: isDark ? '#aaa' : 'text.secondary' }} />
              <Typography variant="caption" sx={{ color: isDark ? '#ccc' : 'text.secondary', whiteSpace: 'nowrap' }}>
                {attachment.message}
              </Typography>
              <LinearProgress
                variant="determinate"
                value={attachment.progress}
                sx={{ flex: 1, height: 6, borderRadius: 3 }}
              />
              <Typography variant="caption" sx={{ color: isDark ? '#ccc' : 'text.secondary', minWidth: 36 }}>
                {attachment.progress}%
              </Typography>
            </>
          ) : (
            <Alert
              severity={attachment.status === 'done' ? 'success' : 'error'}
              icon={attachment.status === 'done' ? <ImageIcon fontSize="inherit" /> : undefined}
              sx={{ py: 0, fontSize: '0.8rem', flex: 1, '& .MuiAlert-message': { fontFamily: attachment.status === 'done' ? 'monospace' : undefined } }}
              onClose={() => setAttachment(null)}
            >
              {attachment.message}
            </Alert>
          )}
        </Box>
      )}

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
        {status === 'connected' && (uploadEnabled || imageUploadEnabled) && (
          <Box sx={{ mr: 'auto', display: 'flex', alignItems: 'center', gap: 1.5 }}>
            {uploadEnabled && (
              <Tooltip title={t('containers.terminal.uploadFile')}>
                <IconButton
                  onClick={() => { setUploadOpen((prev) => !prev); setUploadResult(null) }}
                  size="small"
                  sx={{ color: uploadOpen ? 'primary.main' : 'white' }}
                >
                  <UploadFile sx={{ fontSize: 20 }} />
                </IconButton>
              </Tooltip>
            )}
            {imageUploadEnabled && (
              <Tooltip title={t('containers.terminal.pasteImageTooltip', { path: attachmentsPath ?? '/tmp' })}>
                <Typography variant="caption" sx={{ color: 'rgba(255,255,255,0.6)', display: 'flex', alignItems: 'center', gap: 0.5 }}>
                  <ImageIcon sx={{ fontSize: 16 }} />
                  {t('containers.terminal.pasteImageHint', { shortcut: pasteShortcutLabel(isMac) })}
                </Typography>
              </Tooltip>
            )}
          </Box>
        )}
        <Button onClick={handleClose} sx={{ color: 'white' }}>
          {t('common.close')}
        </Button>
      </DialogActions>
    </Dialog>
  )
}
