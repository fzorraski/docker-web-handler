import { useState, useEffect } from 'react'
import {
  Dialog, DialogTitle, DialogContent, DialogActions, Button, TextField,
  Alert, CircularProgress,
} from '@mui/material'
import { GroupAdd, Edit } from '@mui/icons-material'
import { useTranslation } from 'react-i18next'
import { createTenant, updateTenant, type Tenant } from '../../services/tenantService'

interface Props {
  open: boolean
  onClose: () => void
  onSaved: () => void
  /** null = create mode */
  tenant: Tenant | null
}

export default function TenantFormDialog({ open, onClose, onSaved, tenant }: Props) {
  const { t } = useTranslation()
  const isEdit = tenant !== null
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (open) {
      setName(tenant?.name ?? '')
      setDescription(tenant?.description ?? '')
      setError(null)
    }
  }, [open, tenant])

  const canSave = name.trim().length >= 2

  async function handleSave() {
    setSaving(true)
    setError(null)
    try {
      const request = { name: name.trim(), description: description.trim() || undefined }
      if (isEdit) {
        await updateTenant(tenant.id, request)
      } else {
        await createTenant(request)
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
        {isEdit ? <Edit sx={{ mr: 1, verticalAlign: 'middle' }} /> : <GroupAdd sx={{ mr: 1, verticalAlign: 'middle' }} />}
        {isEdit ? t('tenants.editTenant') : t('tenants.newTenant')}
      </DialogTitle>
      <DialogContent dividers sx={{ pt: 3, display: 'flex', flexDirection: 'column', gap: 2 }}>
        <TextField
          label={t('tenants.name')}
          value={name}
          onChange={(e) => setName(e.target.value)}
          size="small"
          fullWidth
          autoFocus
          autoComplete="off"
        />
        <TextField
          label={t('tenants.description')}
          value={description}
          onChange={(e) => setDescription(e.target.value)}
          size="small"
          fullWidth
          multiline
          minRows={2}
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
