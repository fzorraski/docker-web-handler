import { useState, useEffect } from 'react'
import {
  Dialog, DialogTitle, DialogContent, DialogActions, Button, TextField,
  MenuItem, Checkbox, ListItemText, Chip, Box, Alert, CircularProgress,
  Typography,
} from '@mui/material'
import { Share } from '@mui/icons-material'
import { useTranslation } from 'react-i18next'
import { useAuth } from './AuthProvider'
import { P } from '../utils/permissions'
import { listTenants, type TenantSummary } from '../services/tenantService'

interface Props {
  open: boolean
  onClose: () => void
  /** display name of the resource being shared */
  resourceName: string
  /** current owning tenant id (null = no tenant / visible to everyone) */
  tenantId: string | null
  /** current shared-with tenant ids */
  sharedWithTenants: string[]
  /** persists the change; tenantId is only passed for TENANTS_VIEW_ALL holders */
  onSave: (sharedWithTenants: string[], tenantId: string | null | undefined) => Promise<void>
}

/** Edits which tenants can see a dump or snapshot. */
export default function ShareResourceDialog({ open, onClose, resourceName, tenantId, sharedWithTenants, onSave }: Props) {
  const { t } = useTranslation()
  const { hasPermission } = useAuth()
  const canChangeOwner = hasPermission(P.TENANTS_VIEW_ALL)

  const [tenants, setTenants] = useState<TenantSummary[]>([])
  const [shared, setShared] = useState<string[]>([])
  const [owner, setOwner] = useState('')
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (open) {
      setShared(sharedWithTenants)
      setOwner(tenantId ?? '')
      setError(null)
      listTenants().then(setTenants).catch(() => setTenants([]))
    }
  }, [open, tenantId, sharedWithTenants])

  const tenantName = (id: string) => tenants.find(tn => tn.id === id)?.name ?? id
  const shareOptions = tenants.filter(tn => tn.id !== owner)

  async function handleSave() {
    setSaving(true)
    setError(null)
    try {
      const nextShared = shared.filter(id => id !== owner)
      await onSave(nextShared, canChangeOwner ? (owner || null) : undefined)
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
        <Share sx={{ mr: 1, verticalAlign: 'middle' }} />
        {t('tenants.sharing.title')}
      </DialogTitle>
      <DialogContent dividers sx={{ pt: 3, display: 'flex', flexDirection: 'column', gap: 2 }}>
        <Typography variant="body2" sx={{ fontFamily: "'JetBrains Mono', monospace", fontSize: '0.85rem' }}>
          {resourceName}
        </Typography>
        {canChangeOwner && (
          <TextField
            select
            label={t('tenants.sharing.owner')}
            value={owner}
            onChange={(e) => setOwner(e.target.value)}
            size="small"
            fullWidth
          >
            <MenuItem value="">{t('tenants.noneOption')}</MenuItem>
            {tenants.map((tn) => (
              <MenuItem key={tn.id} value={tn.id}>{tn.name}</MenuItem>
            ))}
          </TextField>
        )}
        <TextField
          select
          label={t('tenants.sharedWith')}
          value={shared}
          onChange={(e) => {
            const value = e.target.value as unknown
            setShared(Array.isArray(value) ? value : [String(value)])
          }}
          size="small"
          fullWidth
          helperText={t('tenants.sharing.description')}
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
          {shareOptions.map((tn) => (
            <MenuItem key={tn.id} value={tn.id}>
              <Checkbox size="small" checked={shared.includes(tn.id)} sx={{ py: 0 }} />
              <ListItemText primary={tn.name} />
            </MenuItem>
          ))}
        </TextField>
        {error && <Alert severity="error">{error}</Alert>}
      </DialogContent>
      <DialogActions sx={{ px: 3, py: 2 }}>
        <Button onClick={onClose} color="inherit" disabled={saving}>{t('common.cancel')}</Button>
        <Button
          variant="contained"
          onClick={handleSave}
          disabled={saving}
          startIcon={saving ? <CircularProgress size={18} /> : undefined}
        >
          {t('common.save')}
        </Button>
      </DialogActions>
    </Dialog>
  )
}
