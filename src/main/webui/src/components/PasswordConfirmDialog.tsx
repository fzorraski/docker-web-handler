import { useState } from 'react'
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

interface Props {
  open: boolean
  onClose: () => void
  onConfirm: (password: string) => Promise<void>
  title: string
  message: string
  confirmLabel?: string
  confirmColor?: 'error' | 'warning'
}

export default function PasswordConfirmDialog({
  open,
  onClose,
  onConfirm,
  title,
  message,
  confirmLabel,
  confirmColor = 'error',
}: Props) {
  const { t } = useTranslation()
  const [password, setPassword] = useState('')
  const [loading, setLoading] = useState(false)

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
        <Delete sx={{ mr: 1, verticalAlign: 'middle' }} /> {title}
      </DialogTitle>
      <DialogContent dividers sx={{ pt: 3 }}>
        <Typography sx={{ mb: 2 }}>
          <span dangerouslySetInnerHTML={{ __html: message }} />
        </Typography>
        <TextField
          fullWidth
          type="password"
          label={t('common.operationsPassword')}
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          size="small"
          autoComplete="off"
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
          startIcon={loading ? <CircularProgress size={20} /> : <Delete />}
        >
          {loading ? t('common.deleting') : (confirmLabel ?? t('common.delete'))}
        </Button>
      </DialogActions>
    </Dialog>
  )
}
