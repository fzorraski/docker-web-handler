import { useState, useEffect } from 'react'
import {
  Dialog, DialogTitle, DialogContent, DialogActions, Button, TextField,
  Alert, CircularProgress,
} from '@mui/material'
import { Password } from '@mui/icons-material'
import { useTranslation } from 'react-i18next'
import { changeOwnPassword } from '../services/authService'
import { useNotification } from './NotificationProvider'

interface Props {
  open: boolean
  onClose: () => void
}

const MIN_PASSWORD_LENGTH = 6

export default function ChangePasswordDialog({ open, onClose }: Props) {
  const { t } = useTranslation()
  const { notify } = useNotification()
  const [currentPassword, setCurrentPassword] = useState('')
  const [newPassword, setNewPassword] = useState('')
  const [confirm, setConfirm] = useState('')
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (open) {
      setCurrentPassword('')
      setNewPassword('')
      setConfirm('')
      setError(null)
    }
  }, [open])

  const mismatch = confirm.length > 0 && newPassword !== confirm
  const tooShort = newPassword.length > 0 && newPassword.length < MIN_PASSWORD_LENGTH
  const canSave = currentPassword.length > 0 && newPassword.length >= MIN_PASSWORD_LENGTH && newPassword === confirm

  async function handleSave() {
    setSaving(true)
    setError(null)
    try {
      const result = await changeOwnPassword(currentPassword, newPassword)
      if (result.success) {
        notify(t('account.passwordChanged'), 'success')
        onClose()
      } else {
        setError(result.error ?? t('common.unexpectedError'))
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : t('common.unexpectedError'))
    } finally {
      setSaving(false)
    }
  }

  return (
    <Dialog open={open} onClose={saving ? undefined : onClose} maxWidth="xs" fullWidth>
      <DialogTitle>
        <Password sx={{ mr: 1, verticalAlign: 'middle' }} />
        {t('account.changePassword')}
      </DialogTitle>
      <DialogContent dividers sx={{ pt: 3, display: 'flex', flexDirection: 'column', gap: 2 }}>
        <TextField
          label={t('account.currentPassword')}
          type="password"
          value={currentPassword}
          onChange={(e) => setCurrentPassword(e.target.value)}
          size="small"
          fullWidth
          autoFocus
          autoComplete="current-password"
        />
        <TextField
          label={t('account.newPassword')}
          type="password"
          value={newPassword}
          onChange={(e) => setNewPassword(e.target.value)}
          size="small"
          fullWidth
          autoComplete="new-password"
          error={tooShort}
          helperText={tooShort ? t('users.passwordTooShort') : undefined}
        />
        <TextField
          label={t('account.confirmPassword')}
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
