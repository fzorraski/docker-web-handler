import { useState, useEffect, useMemo } from 'react'
import {
  Dialog, DialogTitle, DialogContent, DialogActions, Button, TextField,
  MenuItem, FormControlLabel, Switch, Alert, CircularProgress, Checkbox,
  ListItemText, Chip, Box,
} from '@mui/material'
import { PersonAdd, Edit } from '@mui/icons-material'
import { useTranslation } from 'react-i18next'
import { createUser, updateUser, type AppUser } from '../../services/userService'
import type { AppRole } from '../../services/roleService'
import type { TenantSummary } from '../../services/tenantService'
import { P } from '../../utils/permissions'
import { MIN_PASSWORD_LENGTH } from '../../utils/validation'

interface Props {
  open: boolean
  onClose: () => void
  onSaved: () => void
  roles: AppRole[]
  /** assignable tenants: all of them for global admins, own memberships for scoped admins */
  tenants: TenantSummary[]
  /** null = create mode */
  user: AppUser | null
  /** the acting user's own permissions; a role may not grant more than these */
  myPermissions: string[]
  /** whether the acting user holds SYSTEM_CONFIG (may assign super-admin roles) */
  canSystemConfig: boolean
  /** cross-tenant reach; without it the actor is a tenant-scoped admin */
  canTenantsViewAll: boolean
}

export default function UserFormDialog({ open, onClose, onSaved, roles, tenants, user, canSystemConfig, canTenantsViewAll }: Props) {
  const { t } = useTranslation()
  const isEdit = user !== null
  // a scoped admin editing a user who also belongs to a foreign tenant may only
  // change the memberships in their own tenants - roles/enabled would leak
  const membershipOnly = isEdit && !canTenantsViewAll
    && !user.tenantIds.every(id => tenants.some(tn => tn.id === id))
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [roleIds, setRoleIds] = useState<string[]>([])
  const [tenantIds, setTenantIds] = useState<string[]>([])
  const [enabled, setEnabled] = useState(true)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)

  // roles holding SYSTEM_CONFIG (or TENANTS_VIEW_ALL, for scoped admins) can only
  // be assigned by actors who have that reach themselves
  const assignableRoles = useMemo(
    () => roles
      .filter(r => canSystemConfig || !r.permissions.includes(P.SYSTEM_CONFIG))
      .filter(r => canTenantsViewAll || !r.permissions.includes(P.TENANTS_VIEW_ALL)),
    [roles, canSystemConfig, canTenantsViewAll],
  )

  useEffect(() => {
    if (open) {
      setUsername(user?.username ?? '')
      setPassword('')
      setRoleIds(user?.roleIds ?? [])
      // foreign memberships are not selectable options for a scoped admin; the
      // backend preserves them regardless of what this form submits
      setTenantIds((user?.tenantIds ?? []).filter(id => canTenantsViewAll || tenants.some(tn => tn.id === id)))
      setEnabled(user?.enabled ?? true)
      setError(null)
    }
  }, [open, user, tenants, canTenantsViewAll])

  const roleName = (id: string) => roles.find(r => r.id === id)?.name ?? id
  const tenantName = (id: string) => tenants.find(tn => tn.id === id)?.name ?? id

  const passwordTooShort = !isEdit && password.length > 0 && password.length < MIN_PASSWORD_LENGTH
  // scoped admins must keep every user inside their own tenants
  const tenantsOk = canTenantsViewAll || tenantIds.length > 0
  const canSave = isEdit
    // membershipOnly targets keep their foreign memberships, so removing every
    // own-tenant selection is a valid "remove from my tenant" operation
    ? (membershipOnly ? true : roleIds.length > 0 && tenantsOk)
    : username.trim().length >= 3 && password.length >= MIN_PASSWORD_LENGTH && roleIds.length > 0 && tenantsOk

  async function handleSave() {
    setSaving(true)
    setError(null)
    try {
      if (isEdit) {
        // shared users accept membership changes only - sending roles/enabled would be rejected
        await updateUser(user.id, membershipOnly ? { tenantIds } : { roleIds, tenantIds, enabled })
      } else {
        await createUser({ username: username.trim(), password, roleIds, tenantIds })
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
        {membershipOnly && <Alert severity="info">{t('users.membershipOnlyHint')}</Alert>}
        <TextField
          select
          label={t('users.roles')}
          value={roleIds}
          disabled={membershipOnly}
          onChange={(e) => {
            const value = e.target.value as unknown
            setRoleIds(Array.isArray(value) ? value : [String(value)])
          }}
          size="small"
          fullWidth
          helperText={t('users.rolesHint')}
          slotProps={{
            select: {
              multiple: true,
              renderValue: (selected) => (
                <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 0.5 }}>
                  {(selected as string[]).map((id) => (
                    <Chip key={id} label={roleName(id)} size="small" />
                  ))}
                </Box>
              ),
            },
          }}
        >
          {assignableRoles.map((r) => (
            <MenuItem key={r.id} value={r.id}>
              <Checkbox size="small" checked={roleIds.includes(r.id)} sx={{ py: 0 }} />
              <ListItemText primary={r.name} secondary={r.description || undefined} />
            </MenuItem>
          ))}
        </TextField>
        {tenants.length > 0 && (
          <TextField
            select
            label={t('users.tenants')}
            value={tenantIds}
            onChange={(e) => {
              const value = e.target.value as unknown
              setTenantIds(Array.isArray(value) ? value : [String(value)])
            }}
            size="small"
            fullWidth
            helperText={t('users.tenantsHint')}
            slotProps={{
              select: {
                multiple: true,
                renderValue: (selected) => (
                  <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 0.5 }}>
                    {(selected as string[]).map((id) => (
                      <Chip key={id} label={tenantName(id)} size="small" />
                    ))}
                  </Box>
                ),
              },
            }}
          >
            {tenants.map((tn) => (
              <MenuItem key={tn.id} value={tn.id}>
                <Checkbox size="small" checked={tenantIds.includes(tn.id)} sx={{ py: 0 }} />
                <ListItemText primary={tn.name} />
              </MenuItem>
            ))}
          </TextField>
        )}
        {isEdit && (
          <FormControlLabel
            control={<Switch checked={enabled} disabled={membershipOnly} onChange={(e) => setEnabled(e.target.checked)} size="small" />}
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
