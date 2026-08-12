import { useState, useEffect, useMemo } from 'react'
import {
  Dialog, DialogTitle, DialogContent, DialogActions, Button, TextField,
  Alert, CircularProgress, Checkbox, FormControlLabel, Typography, Box, Chip,
} from '@mui/material'
import { AdminPanelSettings } from '@mui/icons-material'
import { useTranslation } from 'react-i18next'
import { createRole, updateRole, type AppRole, type PermissionInfo } from '../../services/roleService'
import { P } from '../../utils/permissions'

/** Mirrors BuiltInRoles.SUPER_ADMIN_ID on the backend. */
const SUPER_ADMIN_ROLE_ID = 'builtin-super-admin'

interface Props {
  open: boolean
  onClose: () => void
  onSaved: () => void
  /** null = create mode */
  role: AppRole | null
  catalog: PermissionInfo[]
  /** whether the acting user holds SYSTEM_CONFIG (may grant it to roles) */
  canSystemConfig: boolean
  /** view-only mode (built-in roles, or SYSTEM_CONFIG roles the actor cannot touch) */
  readOnly: boolean
}

export default function RoleFormDialog({ open, onClose, onSaved, role, catalog, canSystemConfig, readOnly }: Props) {
  const { t } = useTranslation()
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [permissions, setPermissions] = useState<Set<string>>(new Set())
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const byCategory = useMemo(() => {
    const groups = new Map<string, PermissionInfo[]>()
    for (const p of catalog) {
      const list = groups.get(p.category) ?? []
      list.push(p)
      groups.set(p.category, list)
    }
    return groups
  }, [catalog])

  useEffect(() => {
    if (open) {
      setName(role?.name ?? '')
      setDescription(role?.description ?? '')
      setPermissions(new Set(role?.permissions ?? []))
      setError(null)
    }
  }, [open, role])

  function togglePermission(permission: string) {
    setPermissions(prev => {
      const next = new Set(prev)
      if (next.has(permission)) next.delete(permission)
      else next.add(permission)
      return next
    })
  }

  const canSave = !readOnly && name.trim().length > 0 && permissions.size > 0

  async function handleSave() {
    setSaving(true)
    setError(null)
    try {
      const request = { name: name.trim(), description: description.trim() || undefined, permissions: [...permissions] }
      if (role) {
        await updateRole(role.id, request)
      } else {
        await createRole(request)
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
    <Dialog open={open} onClose={saving ? undefined : onClose} maxWidth="sm" fullWidth>
      <DialogTitle>
        <AdminPanelSettings sx={{ mr: 1, verticalAlign: 'middle' }} />
        {readOnly ? t('roles.viewRole') : role ? t('roles.editRole') : t('roles.newRole')}
        {role?.builtIn && <Chip label={t('roles.builtIn')} size="small" color="info" variant="outlined" sx={{ ml: 1 }} />}
      </DialogTitle>
      <DialogContent dividers sx={{ pt: 3, display: 'flex', flexDirection: 'column', gap: 2 }}>
        {role?.builtIn === true && (
          <Alert severity="info">
            {readOnly ? t('roles.builtInReadOnly') : t('roles.builtInEditable')}
          </Alert>
        )}
        <TextField
          label={t('roles.name')}
          value={name}
          onChange={(e) => setName(e.target.value)}
          size="small"
          fullWidth
          // a built-in role keeps its name even when a super admin retunes it
          disabled={readOnly || role?.builtIn === true}
          autoFocus={!readOnly && role?.builtIn !== true}
          autoComplete="off"
        />
        <TextField
          label={t('roles.description')}
          value={description}
          onChange={(e) => setDescription(e.target.value)}
          size="small"
          fullWidth
          disabled={readOnly}
          multiline
          maxRows={2}
        />
        <Typography variant="subtitle2" sx={{ mt: 1 }}>{t('roles.permissions')}</Typography>
        {[...byCategory.entries()].map(([category, perms]) => (
          <Box key={category}>
            <Typography variant="caption" sx={{ color: 'text.secondary', textTransform: 'uppercase', letterSpacing: '0.06em', fontWeight: 600 }}>
              {t(`permissions.categories.${category}` as never)}
            </Typography>
            <Box sx={{ display: 'flex', flexDirection: 'column' }}>
              {perms.map((p) => (
                <FormControlLabel
                  key={p.name}
                  control={
                    <Checkbox
                      size="small"
                      checked={permissions.has(p.name)}
                      onChange={() => togglePermission(p.name)}
                      disabled={
                        readOnly
                        || (p.name === P.SYSTEM_CONFIG && !canSystemConfig)
                        // SUPER_ADMIN without SYSTEM_CONFIG would lock everyone
                        // out of the admin area, so the box stays checked
                        || (p.name === P.SYSTEM_CONFIG && role?.id === SUPER_ADMIN_ROLE_ID)
                      }
                    />
                  }
                  label={
                    <Box sx={{ display: 'flex', alignItems: 'baseline', gap: 1 }}>
                      <Typography variant="body2" sx={{ fontFamily: "'JetBrains Mono', monospace", fontSize: '0.8rem' }}>{p.name}</Typography>
                      <Typography variant="caption" color="text.secondary">{t(`permissions.${p.name}` as never)}</Typography>
                    </Box>
                  }
                />
              ))}
            </Box>
          </Box>
        ))}
        {error && <Alert severity="error">{error}</Alert>}
      </DialogContent>
      <DialogActions sx={{ px: 3, py: 2 }}>
        <Button onClick={onClose} color="inherit" disabled={saving}>
          {readOnly ? t('common.close') : t('common.cancel')}
        </Button>
        {!readOnly && (
          <Button
            variant="contained"
            onClick={handleSave}
            disabled={saving || !canSave}
            startIcon={saving ? <CircularProgress size={18} /> : undefined}
          >
            {t('common.save')}
          </Button>
        )}
      </DialogActions>
    </Dialog>
  )
}
