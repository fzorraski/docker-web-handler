import { useState, useEffect, useMemo } from 'react'
import {
  Dialog, DialogTitle, DialogContent, DialogActions, Button, TextField,
  MenuItem, FormControlLabel, Switch, Alert, CircularProgress,
} from '@mui/material'
import { PersonAdd, Edit } from '@mui/icons-material'
import { useTranslation } from 'react-i18next'
import { createUser, updateUser, type AppUser } from '../../services/userService'
import type { AppRole } from '../../services/roleService'
import { P } from '../../utils/permissions'

interface Props {
  open: boolean
  onClose: () => void
  onSaved: () => void
  roles: AppRole[]
  /** null = create mode */
  user: AppUser | null
  /** whether the acting user holds SYSTEM_CONFIG (may assign super-admin roles) */
  canSystemConfig: boolean
}

const MIN_PASSWORD_LENGTH = 6

export default function UserFormDialog({ open, onClose, onSaved, roles, user, canSystemConfig }: Props) {
  const { t } = useTranslation()
  const isEdit = user !== null
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [roleId, setRoleId] = useState('')
  const [enabled, setEnabled] = useState(true)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)

  // roles holding SYSTEM_CONFIG can only be assigned by actors who hold it themselves
  const assignableRoles = useMemo(
    () => roles.filter(r => canSystemConfig || !r.permissions.includes(P.SYSTEM_CONFIG)),
    [roles, canSystemConfig],
  )

  useEffect(() => {
    if (open) {
      setUsername(user?.username ?? '')
      setPassword('')
      setRoleId(user?.roleId ?? '')
      setEnabled(user?.enabled ?? true)
      setError(null)
    }
  }, [open, user])

  const passwordTooShort = !isEdit && password.length > 0 && password.length < MIN_PASSWORD_LENGTH
  const canSave = isEdit
    ? roleId !== ''
    : username.trim().length >= 3 && password.length >= MIN_PASSWORD_LENGTH && roleId !== ''

  async function handleSave() {
    setSaving(true)
    setError(null)
    try {
      if (isEdit) {
        await updateUser(user.id, { roleId, enabled })
      } else {
        await createUser({ username: username.trim(), password, roleId })
      }
      onSaved()
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
        {isEdit ? <Edit sx={{ mr: 1, verticalAlign: 'middle' }} /> : <PersonAdd sx={{ mr: 1, verticalAlign: 'middle' }} />}
        {isEdit ? t('users.editUser') : t('users.newUser')}
      </DialogTitle>
      <DialogContent dividers sx={{ pt: 3, display: 'flex', flexDirection: 'column', gap: 2 }}>
        <TextField
          label={t('users.username')}
          value={username}
          onChange={(e) => setUsername(e.target.value)}
          size="small"
          fullWidth
          disabled={isEdit}
          autoFocus={!isEdit}
          autoComplete="off"
        />
        {!isEdit && (
          <TextField
            label={t('users.password')}
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            size="small"
            fullWidth
            autoComplete="new-password"
            error={passwordTooShort}
            helperText={passwordTooShort ? t('users.passwordTooShort') : undefined}
          />
        )}
        <TextField
          select
          label={t('users.role')}
          value={roleId}
          onChange={(e) => setRoleId(e.target.value)}
          size="small"
          fullWidth
        >
          {assignableRoles.map((r) => (
            <MenuItem key={r.id} value={r.id}>{r.name}</MenuItem>
          ))}
        </TextField>
        {isEdit && (
          <FormControlLabel
            control={<Switch checked={enabled} onChange={(e) => setEnabled(e.target.checked)} size="small" />}
            label={t('users.enabled')}
          />
        )}
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
