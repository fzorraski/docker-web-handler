import { useState, useEffect, type ReactElement } from 'react'
import {
  Alert,
  Dialog,
  DialogTitle,
  DialogContent,
  DialogActions,
  Button,
  TextField,
  Typography,
  CircularProgress,
} from '@mui/material'
import { Delete } from '@mui/icons-material'
import { useTranslation } from 'react-i18next'
import { RateLimitError } from '../services/fetchWithAuth'
import { useAuth } from './AuthProvider'

function sanitizeHtml(html: string): string {
  return html.replace(/<(?!\/?(?:strong|b|em|br)\b)[^>]*>/gi, '')
}

interface Props {
  open: boolean
  onClose: () => void
  onConfirm: (password: string) => Promise<void>
  title: string
  message: string
  confirmLabel?: string
  loadingLabel?: string
  confirmColor?: 'error' | 'warning' | 'primary' | 'success'
  icon?: ReactElement
}

export default function PasswordConfirmDialog({
  open,
  onClose,
  onConfirm,
  title,
  message,
  confirmLabel,
  loadingLabel,
  confirmColor = 'error',
  icon,
}: Props) {
  const { t } = useTranslation()
  const { rbacEnabled } = useAuth()
  const [password, setPassword] = useState('')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [retryAfter, setRetryAfter] = useState(0)
  const isLocked = retryAfter > 0
  // Under RBAC role permissions replace the operation passwords: the dialog
  // stays as a confirmation step but no password is asked (backend ignores it).
  const needPassword = !rbacEnabled

  useEffect(() => {
    if (!isLocked) return
    const timer = setInterval(() => {
      setRetryAfter(prev => {
        if (prev <= 1) {
          clearInterval(timer)
          setError(null)
          return 0
        }
        return prev - 1
      })
    }, 1000)
    return () => clearInterval(timer)
  }, [isLocked])

  const resolvedIcon = icon ?? <Delete />

  function handleClose() {
    if (!loading) {
      setPassword('')
      setLoading(false)
      setError(null)
      setRetryAfter(0)
      onClose()
    }
  }

  async function handleConfirm() {
    setLoading(true)
    setError(null)
    try {
      await onConfirm(password)
    } catch (e) {
      if (e instanceof RateLimitError) {
        setRetryAfter(e.retryAfter)
      }
      setError(e instanceof Error ? e.message : t('common.unexpectedError'))
    } finally {
      setLoading(false)
      setPassword('')
    }
  }

  const errorMessage = isLocked
    ? t('common.rateLimited', { seconds: retryAfter })
    : error

  return (
    <Dialog open={open} onClose={handleClose} maxWidth="sm" fullWidth>
      <DialogTitle sx={{ bgcolor: `${confirmColor}.main`, color: 'white' }}>
        {resolvedIcon && <span style={{ marginRight: 8, verticalAlign: 'middle' }}>{resolvedIcon}</span>}
        {title}
      </DialogTitle>
      <DialogContent dividers sx={{ pt: 3 }}>
        <Typography sx={{ mb: 2 }}>
          <span dangerouslySetInnerHTML={{ __html: sanitizeHtml(message) }} />
        </Typography>
        {needPassword && (
          <TextField
            fullWidth
            type="password"
            label={t('common.operationsPassword')}
            value={password}
            onChange={(e) => { setPassword(e.target.value); setError(null) }}
            size="small"
            autoComplete="off"
            autoFocus
            disabled={isLocked}
            onKeyDown={(e) => { if (e.key === 'Enter' && password && !loading && !isLocked) handleConfirm() }}
          />
        )}
        {errorMessage && (
          <Alert severity="error" sx={{ mt: 2 }}>
            {errorMessage}
          </Alert>
        )}
      </DialogContent>
      <DialogActions sx={{ px: 3, py: 2 }}>
        <Button onClick={handleClose} color="inherit" disabled={loading}>
          {t('common.cancel')}
        </Button>
        <Button
          variant="contained"
          color={confirmColor}
          onClick={handleConfirm}
          disabled={loading || (needPassword && !password) || isLocked}
          startIcon={loading ? <CircularProgress size={20} /> : resolvedIcon}
        >
          {loading
            ? (loadingLabel ?? t('common.deleting'))
            : (confirmLabel ?? t('common.delete'))}
        </Button>
      </DialogActions>
    </Dialog>
  )
}
