import { useState, useEffect, useRef } from 'react'
import {
  Dialog,
  DialogTitle,
  DialogContent,
  DialogActions,
  Button,
  TextField,
  ToggleButtonGroup,
  ToggleButton,
  Typography,
  Box,
  Chip,
  Tooltip,
  IconButton,
  FormControlLabel,
  Switch,
} from '@mui/material'
import { Close, UploadFile, AutoFixHigh } from '@mui/icons-material'
import { useTranslation } from 'react-i18next'

export interface MigrationConfig {
  mode: 'MANUAL' | 'API'
  sql?: string
  fileName?: string
  sourceVersion?: string
  targetVersion?: string
  validateBeforeExecute?: boolean
}

interface Props {
  open: boolean
  config: MigrationConfig | null
  apiModeAvailable: boolean
  suggestedSourceVersion?: string
  suggestedTargetVersion?: string
  onSave: (config: MigrationConfig) => void
  onClose: () => void
}

export default function MigrationConfigModal({ open, config, apiModeAvailable, suggestedSourceVersion, suggestedTargetVersion, onSave, onClose }: Props) {
  const { t } = useTranslation()
  const [mode, setMode] = useState<'MANUAL' | 'API'>(config?.mode ?? 'MANUAL')
  const [sql, setSql] = useState(config?.sql ?? '')
  const [fileName, setFileName] = useState(config?.fileName ?? '')
  const [sourceVersion, setSourceVersion] = useState(config?.sourceVersion ?? '')
  const [targetVersion, setTargetVersion] = useState(config?.targetVersion ?? '')
  const [validateBeforeExecute, setValidateBeforeExecute] = useState(config?.validateBeforeExecute ?? true)
  const [error, setError] = useState('')
  const fileInputRef = useRef<HTMLInputElement>(null)

  useEffect(() => {
    if (open) {
      setMode(config?.mode ?? 'MANUAL')
      setSql(config?.sql ?? '')
      setFileName(config?.fileName ?? '')
      setSourceVersion(config?.sourceVersion ?? '')
      setTargetVersion(config?.targetVersion ?? '')
      setValidateBeforeExecute(config?.validateBeforeExecute ?? true)
      setError('')
    }
  }, [open, config])

  function handleFileUpload(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0]
    if (!file) return
    const maxSizeMb = 10
    if (file.size > maxSizeMb * 1024 * 1024) {
      setError(t('migrationConfig.fileTooLarge', { max: String(maxSizeMb) }))
      e.target.value = ''
      return
    }
    const reader = new FileReader()
    reader.onload = (ev) => {
      setSql(ev.target?.result as string ?? '')
      setFileName(file.name)
    }
    reader.readAsText(file)
    e.target.value = ''
  }

  function handleSave() {
    if (mode === 'MANUAL') {
      if (!sql.trim()) {
        setError(t('migrationConfig.sqlRequired'))
        return
      }
    }
    if (mode === 'API') {
      if (!sourceVersion.trim() || !targetVersion.trim()) {
        setError(t('migrationConfig.versionsRequired'))
        return
      }
    }
    onSave({
      mode,
      sql: mode === 'MANUAL' ? sql : undefined,
      fileName: mode === 'MANUAL' && fileName ? fileName : undefined,
      sourceVersion: sourceVersion.trim() || undefined,
      targetVersion: targetVersion.trim() || undefined,
      validateBeforeExecute,
    })
  }

  const hasSuggestion = !!(suggestedSourceVersion || suggestedTargetVersion)

  return (
    <Dialog open={open} onClose={onClose} maxWidth="sm" fullWidth>
      <DialogTitle sx={{ bgcolor: 'primary.dark', color: 'white', display: 'flex', alignItems: 'center' }}>
        {t('migrationConfig.title')}
        <Button onClick={onClose} sx={{ ml: 'auto', color: 'white', minWidth: 'auto' }}>
          <Close />
        </Button>
      </DialogTitle>
      <DialogContent dividers sx={{ pt: 3, display: 'flex', flexDirection: 'column', gap: 2.5 }}>
        {/* Version fields */}
        <Box sx={{ display: 'flex', gap: 1.5, alignItems: 'center' }}>
          <TextField
            fullWidth
            label={t('migrationConfig.sourceVersion')}
            placeholder={t('migrationConfig.sourceVersionPlaceholder')}
            value={sourceVersion}
            onChange={(e) => { setSourceVersion(e.target.value); setError('') }}
            size="small"
          />
          <Typography variant="body2" color="text.secondary" sx={{ flexShrink: 0 }}>{'\u2192'}</Typography>
          <TextField
            fullWidth
            label={t('migrationConfig.targetVersion')}
            placeholder={t('migrationConfig.targetVersionPlaceholder')}
            value={targetVersion}
            onChange={(e) => { setTargetVersion(e.target.value); setError('') }}
            size="small"
          />
          {hasSuggestion && (
            <Tooltip title={t('migrationConfig.autoFill', {
              source: suggestedSourceVersion || '?',
              target: suggestedTargetVersion || '?',
            })}>
              <IconButton
                size="small"
                onClick={() => {
                  if (suggestedSourceVersion) setSourceVersion(suggestedSourceVersion)
                  if (suggestedTargetVersion) setTargetVersion(suggestedTargetVersion)
                  setError('')
                }}
              >
                <AutoFixHigh fontSize="small" />
              </IconButton>
            </Tooltip>
          )}
        </Box>

        {/* Mode toggle */}
        <Box sx={{ display: 'flex', justifyContent: 'center' }}>
          <ToggleButtonGroup
            value={mode}
            exclusive
            onChange={(_e, v) => { if (v) { setMode(v); setError('') } }}
            size="small"
          >
            <ToggleButton value="MANUAL" sx={{ px: 3 }}>{t('migrationConfig.manualMode')}</ToggleButton>
            <ToggleButton value="API" disabled={!apiModeAvailable} sx={{ px: 3 }}>
              {apiModeAvailable ? t('migrationConfig.apiMode') : (
                <Tooltip title={t('migrationConfig.apiNotAvailable')}>
                  <span>{t('migrationConfig.apiMode')}</span>
                </Tooltip>
              )}
            </ToggleButton>
          </ToggleButtonGroup>
        </Box>

        {/* Mode-specific content */}
        {mode === 'MANUAL' && (
          <Box sx={{ display: 'flex', flexDirection: 'column', gap: 1.5 }}>
            <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
              <input
                type="file"
                accept=".sql"
                ref={fileInputRef}
                style={{ display: 'none' }}
                onChange={handleFileUpload}
              />
              <Button
                variant="outlined"
                size="small"
                startIcon={<UploadFile />}
                onClick={() => fileInputRef.current?.click()}
              >
                {t('migrationConfig.uploadFile')}
              </Button>
              {fileName && (
                <Chip label={t('migrationConfig.fileSelected', { name: fileName })} size="small" variant="outlined" onDelete={() => { setFileName(''); setSql('') }} />
              )}
            </Box>
            <TextField
              fullWidth
              multiline
              minRows={5}
              maxRows={10}
              label={t('migrationConfig.pasteSQL')}
              placeholder={t('migrationConfig.sqlPlaceholder')}
              value={sql}
              onChange={(e) => { setSql(e.target.value); setError('') }}
              size="small"
            />
          </Box>
        )}

        {mode === 'API' && (
          <Box sx={{ p: 2, border: 1, borderColor: 'divider', borderRadius: 1, bgcolor: 'action.hover' }}>
            <Typography variant="body2" color="text.secondary">
              {t('migrationConfig.apiDescription')}
            </Typography>
          </Box>
        )}

        {/* Review toggle */}
        <FormControlLabel
          control={
            <Switch
              checked={validateBeforeExecute}
              onChange={(e) => setValidateBeforeExecute(e.target.checked)}
              size="small"
            />
          }
          label={
            <Typography variant="body2">{t('migrationConfig.validateBeforeExecute')}</Typography>
          }
          sx={{ mx: 0 }}
        />

        {error && (
          <Typography color="error" variant="body2">
            {error}
          </Typography>
        )}
      </DialogContent>
      <DialogActions sx={{ px: 3, py: 2 }}>
        <Button onClick={onClose} color="inherit">{t('common.cancel')}</Button>
        <Button variant="contained" onClick={handleSave}>{t('migrationConfig.save')}</Button>
      </DialogActions>
    </Dialog>
  )
}
