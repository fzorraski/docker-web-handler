import { useState, useEffect } from 'react'
import {
  Dialog, DialogTitle, DialogContent, DialogActions, Button, TextField,
  Alert, CircularProgress,
} from '@mui/material'
import { LockReset } from '@mui/icons-material'
import { useTranslation } from 'react-i18next'
import { resetUserPassword, type AppUser } from '../../services/userService'

interface Props {
  open: boolean
  onClose: () => void
  onDone: () => void
  user: AppUser | null
}

const MIN_PASSWORD_LENGTH = 6

export default function ResetPasswordDialog({ open, onClose, onDone, user }: Props) {
  const { t } = useTranslation()
  const [password, setPassword] = useState('')
  const [confirm, setConfirm] = useState('')
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (open) {
      setPassword('')
      setConfirm('')
      setError(null)
    }
  }, [open])

  const mismatch = confirm.length > 0 && password !== confirm
  const tooShort = password.length > 0 && password.length < MIN_PASSWORD_LENGTH
  const canSave = password.length >= MIN_PASSWORD_LENGTH && password === confirm

  async function handleSave() {
    if (!user) return
    setSaving(true)
    setError(null)
    try {
      await resetUserPassword(user.id, password)
      onDone()
      onClose()
    } catch (e) {
      setError(e instanceof Error ? e.message : t('common.unexpectedError'))
    } finally {
      setSaving(false)
    }
  }

  return (
    <Dialog open={open} onClose={saving ? undefined : onClose} maxWidth="xs" fullWidth>
      <DialogTitle>
        <LockReset sx={{ mr: 1, verticalAlign: 'middle' }} />
        {t('users.resetPassword')}{user ? ` — ${user.username}` : ''}
      </DialogTitle>
      <DialogContent dividers sx={{ pt: 3, display: 'flex', flexDirection: 'column', gap: 2 }}>
        <TextField
          label={t('users.newPassword')}
          type="password"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          size="small"
          fullWidth
          autoFocus
          autoComplete="new-password"
          error={tooShort}
          helperText={tooShort ? t('users.passwordTooShort') : undefined}
        />
        <TextField
          label={t('users.confirmPassword')}
          type="password"
          value={confirm}
          onChange={(e) => setConfirm(e.target.value)}
          size="small"
          fullWidth
          autoComplete="new-password"
          error={mismatch}
          helperText={mismatch ? t('users.passwordMismatch') : undefined}
          onKeyDown={(e) => { if (e.key === 'Enter' && canSave && !saving) handleSave() }}
        />
        {error && <Alert severity="error">{error}</Alert>}
      </DialogContent>
      <DialogActions sx={{ px: 3, py: 2 }}>
        <Button onClick={onClose} color="inherit" disabled={saving}>{t('common.cancel')}</Button>
        <Button
          variant="contained"
          onClick={handleSave}
          disabled={saving || !canSave}
          startIcon={saving ? <CircularProgress size={18} /> : undefined}
        >
          {t('common.save')}
        </Button>
      </DialogActions>
    </Dialog>
  )
}
