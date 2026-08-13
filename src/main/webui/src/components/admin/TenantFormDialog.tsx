import { useState, useEffect } from 'react'
import {
  Dialog, DialogTitle, DialogContent, DialogActions, Button, TextField,
  Alert, CircularProgress, Switch, FormControlLabel, FormGroup, Checkbox,
  Typography, Box, IconButton, Tooltip, alpha,
} from '@mui/material'
import { GroupAdd, Edit, Casino, Check } from '@mui/icons-material'
import { useTranslation } from 'react-i18next'
import {
  createTenant, updateTenant, getEntitlementOptions, getTenantPalette,
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

interface ColorPickerProps {
  palette: string[]
  value: string
  onChange: (color: string) => void
  disabled: boolean
}

/**
 * The tenant's badge colour: the palette as swatches, a dice for "surprise me",
 * and a native colour input for anything outside the twelve. The swatches are
 * the intended path - they are the hues that stay legible on both themes and
 * distinct from each other in a list of badges.
 */
function ColorPicker({ palette, value, onChange, disabled }: ColorPickerProps) {
  const { t } = useTranslation()

  function shuffle() {
    const others = palette.filter((color) => color.toUpperCase() !== value.toUpperCase())
    const pool = others.length > 0 ? others : palette
    onChange(pool[Math.floor(Math.random() * pool.length)])
  }

  return (
    <Box>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 1 }}>
        <Typography variant="body2" sx={{ fontWeight: 600 }}>{t('tenants.color')}</Typography>
        <Box sx={{ flex: 1 }} />
        <Tooltip title={t('tenants.randomColor')}>
          <span>
            <IconButton size="small" onClick={shuffle} disabled={disabled}>
              <Casino fontSize="small" />
            </IconButton>
          </span>
        </Tooltip>
        <Tooltip title={t('tenants.customColor')}>
          <Box
            component="input"
            type="color"
            aria-label={t('tenants.customColor')}
            value={value}
            disabled={disabled}
            onChange={(e: React.ChangeEvent<HTMLInputElement>) => onChange(e.target.value.toUpperCase())}
            sx={{
              width: 28, height: 28, p: 0, cursor: disabled ? 'default' : 'pointer',
              border: '1px solid', borderColor: 'divider', borderRadius: '50%',
              background: 'none', overflow: 'hidden',
            }}
          />
        </Tooltip>
      </Box>
      <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 1 }}>
        {palette.map((color) => {
          const selected = color.toUpperCase() === value.toUpperCase()
          return (
            <Box
              key={color}
              component="button"
              type="button"
              aria-label={color}
              aria-pressed={selected}
              disabled={disabled}
              onClick={() => onChange(color)}
              sx={{
                width: 28, height: 28, borderRadius: '50%', cursor: disabled ? 'default' : 'pointer',
                backgroundColor: alpha(color, 0.85),
                border: '2px solid',
                borderColor: selected ? color : 'transparent',
                outline: selected ? undefined : 'none',
                boxShadow: selected ? `0 0 0 2px ${alpha(color, 0.35)}` : 'none',
                display: 'flex', alignItems: 'center', justifyContent: 'center',
                color: '#fff', p: 0,
              }}
            >
              {selected && <Check sx={{ fontSize: 16 }} />}
            </Box>
          )
        })}
      </Box>
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
  const [color, setColor] = useState<string>('')
  const [palette, setPalette] = useState<string[]>([])
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
      setColor(tenant?.color ?? '')
      setError(null)
      setOptionsError(false)
      setOptions(null)
      getEntitlementOptions()
        .then(setOptions)
        .catch(() => setOptionsError(true))
      // a new tenant starts on a random swatch, so the badge is distinct
      // from the start even if the admin never opens the picker
      getTenantPalette()
        .then((colors) => {
          setPalette(colors)
          if (!tenant && colors.length > 0) {
            setColor(colors[Math.floor(Math.random() * colors.length)])
          }
        })
        .catch(() => setPalette([]))
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
        color: color || undefined,
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
        {palette.length > 0 && (
          <ColorPicker palette={palette} value={color} onChange={setColor} disabled={saving} />
        )}
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
