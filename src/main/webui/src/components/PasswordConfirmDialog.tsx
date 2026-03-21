import { useState, type ReactElement } from 'react'
import {
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
  const [password, setPassword] = useState('')
  const [loading, setLoading] = useState(false)

  const resolvedIcon = icon ?? <Delete />

  function handleClose() {
    if (!loading) {
      setPassword('')
      setLoading(false)
      onClose()
    }
  }

  async function handleConfirm() {
    setLoading(true)
    try {
      await onConfirm(password)
    } finally {
      setLoading(false)
      setPassword('')
    }
  }

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
        <TextField
          fullWidth
          type="password"
          label={t('common.operationsPassword')}
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          size="small"
          autoComplete="off"
          autoFocus
          onKeyDown={(e) => { if (e.key === 'Enter' && password && !loading) handleConfirm() }}
        />
      </DialogContent>
      <DialogActions sx={{ px: 3, py: 2 }}>
        <Button onClick={handleClose} color="inherit" disabled={loading}>
          {t('common.cancel')}
        </Button>
        <Button
          variant="contained"
          color={confirmColor}
          onClick={handleConfirm}
          disabled={loading || !password}
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
