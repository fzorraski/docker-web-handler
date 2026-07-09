import { useState, useEffect } from 'react'
import {
  Dialog, DialogTitle, DialogContent, DialogActions, Button, TextField,
  Alert, CircularProgress, Switch, FormControlLabel, FormGroup, Checkbox,
  Typography, Box,
} from '@mui/material'
import { GroupAdd, Edit } from '@mui/icons-material'
import { useTranslation } from 'react-i18next'
import {
  createTenant, updateTenant, getEntitlementOptions,
  type Tenant, type EntitlementOptions,
} from '../../services/tenantService'

interface Props {
  open: boolean
  onClose: () => void
  onSaved: () => void
  /** null = create mode */
  tenant: Tenant | null
}

interface EntitlementSectionProps {
  label: string
  allEnabledText: string
  noneSelectedText: string
  options: string[]
  restricted: boolean
  onRestrictedChange: (restricted: boolean) => void
  selected: string[]
  onSelectedChange: (selected: string[]) => void
  disabled: boolean
}

function EntitlementSection({
  label, allEnabledText, noneSelectedText, options, restricted,
  onRestrictedChange, selected, onSelectedChange, disabled,
}: EntitlementSectionProps) {
  const { t } = useTranslation()

  function toggle(option: string, checked: boolean) {
    onSelectedChange(checked ? [...selected, option] : selected.filter((o) => o !== option))
  }

  // stale = persisted on the tenant but no longer globally configured;
  // shown for transparency, dropped on save (the backend rejects unknown names)
  const stale = selected.filter((entry) => !options.includes(entry))

  return (
    <Box>
      <FormControlLabel
        control={(
          <Switch
            checked={restricted}
            onChange={(e) => onRestrictedChange(e.target.checked)}
            disabled={disabled}
            size="small"
          />
        )}
        label={label}
      />
      {!restricted && (
        <Typography variant="body2" color="text.secondary" sx={{ ml: 4 }}>
          {allEnabledText}
        </Typography>
      )}
      {restricted && (
        <FormGroup sx={{ ml: 4 }}>
          {options.map((option) => (
            <FormControlLabel
              key={option}
              control={(
                <Checkbox
                  size="small"
                  checked={selected.includes(option)}
                  onChange={(e) => toggle(option, e.target.checked)}
                  sx={{ py: 0.25 }}
                />
              )}
              label={option}
            />
          ))}
          {stale.map((entry) => (
            <FormControlLabel
              key={entry}
              control={<Checkbox size="small" checked disabled sx={{ py: 0.25 }} />}
              label={`${entry} ${t('tenants.entitlements.staleEntry')}`}
            />
          ))}
          {restricted && selected.filter((entry) => options.includes(entry)).length === 0 && (
            <Alert severity="warning" sx={{ mt: 1 }}>{noneSelectedText}</Alert>
          )}
        </FormGroup>
      )}
    </Box>
  )
}

export default function TenantFormDialog({ open, onClose, onSaved, tenant }: Props) {
  const { t } = useTranslation()
  const isEdit = tenant !== null
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [restrictRepos, setRestrictRepos] = useState(false)
  const [selectedRepos, setSelectedRepos] = useState<string[]>([])
  const [restrictDbs, setRestrictDbs] = useState(false)
  const [selectedDbs, setSelectedDbs] = useState<string[]>([])
  const [options, setOptions] = useState<EntitlementOptions | null>(null)
  const [optionsError, setOptionsError] = useState(false)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (open) {
      setName(tenant?.name ?? '')
      setDescription(tenant?.description ?? '')
      setRestrictRepos(tenant?.enabledRepositories != null)
      setSelectedRepos(tenant?.enabledRepositories ?? [])
      setRestrictDbs(tenant?.enabledDatabases != null)
      setSelectedDbs(tenant?.enabledDatabases ?? [])
      setError(null)
      setOptionsError(false)
      setOptions(null)
      getEntitlementOptions()
        .then(setOptions)
        .catch(() => setOptionsError(true))
    }
  }, [open, tenant])

  const optionsLoading = options === null && !optionsError
  // saving a restriction needs the option lists (stale entries are dropped against them)
  const canSave = name.trim().length >= 2
    && !((restrictRepos || restrictDbs) && options === null)

  async function handleSave() {
    setSaving(true)
    setError(null)
    try {
      const request = {
        name: name.trim(),
        description: description.trim() || undefined,
        enabledRepositories: restrictRepos
          ? selectedRepos.filter((r) => options!.repositories.includes(r))
          : null,
        enabledDatabases: restrictDbs
          ? selectedDbs.filter((d) => options!.databases.includes(d))
          : null,
      }
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
    <Dialog open={open} onClose={saving ? undefined : onClose} maxWidth="sm" fullWidth>
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
        <Typography variant="body2" color="text.secondary">
          {t('tenants.entitlements.hint')}
        </Typography>
        {optionsLoading && <CircularProgress size={22} sx={{ alignSelf: 'center' }} />}
        {optionsError && <Alert severity="error">{t('tenants.entitlements.loadFailed')}</Alert>}
        {options && (
          <>
            <EntitlementSection
              label={t('tenants.entitlements.restrictRepositories')}
              allEnabledText={t('tenants.entitlements.allRepositoriesEnabled')}
              noneSelectedText={t('tenants.entitlements.noRepositoriesSelected')}
              options={options.repositories}
              restricted={restrictRepos}
              onRestrictedChange={setRestrictRepos}
              selected={selectedRepos}
              onSelectedChange={setSelectedRepos}
              disabled={saving}
            />
            <EntitlementSection
              label={t('tenants.entitlements.restrictDatabases')}
              allEnabledText={t('tenants.entitlements.allDatabasesEnabled')}
              noneSelectedText={t('tenants.entitlements.noDatabasesSelected')}
              options={options.databases}
              restricted={restrictDbs}
              onRestrictedChange={setRestrictDbs}
              selected={selectedDbs}
              onSelectedChange={setSelectedDbs}
              disabled={saving}
            />
          </>
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
