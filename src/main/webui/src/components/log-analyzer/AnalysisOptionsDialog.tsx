import { useState, useEffect, useCallback, useRef } from 'react'
import {
  Autocomplete, Box, Button, Dialog, DialogActions, DialogContent, DialogTitle,
  IconButton, Stack, Switch, Tab, Tabs, TextField, Tooltip, Typography,
  FormControlLabel,
  alpha, useTheme,
} from '@mui/material'
import {
  Close, CloudUpload, Clear, CheckCircleOutline,
  SyncAlt, Work, ErrorOutline, WarningAmber, BugReport, Code, Extension,
} from '@mui/icons-material'
import { useTranslation } from 'react-i18next'
import { formatBytes } from '../../utils/format'
import type { LogPreset, UploadOptions } from '../../services/logAnalyzerService'

// ── Types ──────────────────────────────────────────────────────────────────

export interface AnalysisOptions {
  apiCalls: boolean
  jobs: boolean
  failures: boolean
  criticalIssues: boolean
  npeAnalysis: boolean
  exceptionAnalysis: boolean
  customFields: boolean
}

export interface AnalysisConfiguration {
  label: string
  selectedPreset: string
  slowThreshold: number
  customRegex: Partial<UploadOptions>
  customFieldInputs: Array<{ name: string; regex: string; countOnly: boolean }>
  analysisOptions: AnalysisOptions
}

// ── Constants ──────────────────────────────────────────────────────────────

const STORAGE_KEY = 'log-analyzer-options'
const ACCEPTED_EXTENSIONS = ['.log', '.txt', '.out']

const ALL_ON: AnalysisOptions = {
  apiCalls: true, jobs: true, failures: true,
  criticalIssues: true, npeAnalysis: true,
  exceptionAnalysis: true, customFields: true,
}

function loadOptions(): AnalysisOptions {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (raw) return { ...ALL_ON, ...JSON.parse(raw) }
  } catch { /* ignore */ }
  return { ...ALL_ON }
}

function saveOptions(options: AnalysisOptions) {
  localStorage.setItem(STORAGE_KEY, JSON.stringify(options))
}

export function applyPreset(preset: LogPreset): { customRegex: Partial<UploadOptions>; customFieldInputs: Array<{ name: string; regex: string; countOnly: boolean }> } {
  return {
    customRegex: {
      logLineRegex: preset.logLineRegex,
      apiCallRegex: preset.apiCallRegex ?? undefined,
      timestampFormat: preset.timestampFormat,
      jobStartRegex: preset.jobStartRegex ?? undefined,
      jobEndRegex: preset.jobEndRegex ?? undefined,
      failureRegex: preset.failureRegex ?? undefined,
      sensitiveFieldNames: preset.sensitiveFieldNames?.join(','),
      criticalIssueExclusions: preset.criticalIssueExclusions?.join(','),
    },
    customFieldInputs: preset.customFields?.map(cf => ({ name: cf.name, regex: cf.regex, countOnly: cf.countOnly })) ?? [],
  }
}

// ── Analysis option rows ───────────────────────────────────────────────────

type CostLevel = 'low' | 'medium' | 'high'
const COST_COLORS: Record<CostLevel, string> = { low: '#4caf50', medium: '#ff9800', high: '#f44336' }

const OPTION_ROWS = [
  { key: 'apiCalls' as const, icon: <SyncAlt />, cost: 'high' as CostLevel, labelKey: 'logAnalyzer.analysisOptions.apiCalls' as const, descKey: 'logAnalyzer.analysisOptions.apiCallsDesc' as const },
  { key: 'jobs' as const, icon: <Work />, cost: 'low' as CostLevel, labelKey: 'logAnalyzer.analysisOptions.jobs' as const, descKey: 'logAnalyzer.analysisOptions.jobsDesc' as const },
  { key: 'failures' as const, icon: <ErrorOutline />, cost: 'low' as CostLevel, labelKey: 'logAnalyzer.analysisOptions.failures' as const, descKey: 'logAnalyzer.analysisOptions.failuresDesc' as const },
  { key: 'criticalIssues' as const, icon: <WarningAmber />, cost: 'medium' as CostLevel, labelKey: 'logAnalyzer.analysisOptions.criticalIssues' as const, descKey: 'logAnalyzer.analysisOptions.criticalIssuesDesc' as const },
  { key: 'npeAnalysis' as const, icon: <BugReport />, cost: 'high' as CostLevel, labelKey: 'logAnalyzer.analysisOptions.npeAnalysis' as const, descKey: 'logAnalyzer.analysisOptions.npeAnalysisDesc' as const },
  { key: 'exceptionAnalysis' as const, icon: <Code />, cost: 'high' as CostLevel, labelKey: 'logAnalyzer.analysisOptions.exceptionAnalysis' as const, descKey: 'logAnalyzer.analysisOptions.exceptionAnalysisDesc' as const },
  { key: 'customFields' as const, icon: <Extension />, cost: 'medium' as CostLevel, labelKey: 'logAnalyzer.analysisOptions.customFields' as const, descKey: 'logAnalyzer.analysisOptions.customFieldsDesc' as const },
]

// ── Component ──────────────────────────────────────────────────────────────

export function AnalysisOptionsDialog({ open, onClose, onStart, files, onFilesChange, presets, initialConfig, containerSource }: {
  open: boolean
  onClose: () => void
  onStart: (config: AnalysisConfiguration) => void
  files: File[]
  onFilesChange: (files: File[]) => void
  presets: LogPreset[]
  initialConfig: AnalysisConfiguration
  containerSource?: { id: string; name: string } | null
}) {
  const { t } = useTranslation()
  const theme = useTheme()

  const [tab, setTab] = useState(0)
  const [options, setOptions] = useState<AnalysisOptions>(ALL_ON)
  const [label, setLabel] = useState('')
  const [selectedPreset, setSelectedPreset] = useState('')
  const [slowThreshold, setSlowThreshold] = useState(1000)
  const [customRegex, setCustomRegex] = useState<Partial<UploadOptions>>({})
  const [customFieldInputs, setCustomFieldInputs] = useState<Array<{ name: string; regex: string; countOnly: boolean }>>([])

  // Drag state for drop zone inside dialog
  const [isDragging, setIsDragging] = useState(false)
  const dragCounter = useRef(0)

  // Clone config into local state when dialog opens
  useEffect(() => {
    if (open) {
      setTab(0)
      setOptions(loadOptions())
      setLabel(initialConfig.label)
      setSelectedPreset(initialConfig.selectedPreset)
      setSlowThreshold(initialConfig.slowThreshold)
      setCustomRegex(initialConfig.customRegex)
      setCustomFieldInputs(initialConfig.customFieldInputs)
    }
  }, [open]) // eslint-disable-line react-hooks/exhaustive-deps

  const toggle = useCallback((key: keyof AnalysisOptions) => {
    setOptions(prev => ({ ...prev, [key]: !prev[key] }))
  }, [])

  const enableAll = useCallback(() => setOptions({ ...ALL_ON }), [])
  const disableAll = useCallback(() => setOptions({
    apiCalls: false, jobs: false, failures: false,
    criticalIssues: false, npeAnalysis: false,
    exceptionAnalysis: false, customFields: false,
  }), [])

  const handlePresetChange = useCallback((_: unknown, p: LogPreset | null) => {
    if (!p) return
    setSelectedPreset(p.name.toUpperCase())
    const { customRegex: cr, customFieldInputs: cfi } = applyPreset(p)
    setCustomRegex(cr)
    setCustomFieldInputs(cfi)
  }, [])

  const handleStart = useCallback(() => {
    saveOptions(options)
    onStart({
      label,
      selectedPreset,
      slowThreshold,
      customRegex,
      customFieldInputs,
      analysisOptions: options,
    })
  }, [label, options, selectedPreset, slowThreshold, customRegex, customFieldInputs, onStart])

  const handleAddFiles = useCallback((fileList: FileList | null) => {
    if (!fileList) return
    const valid = Array.from(fileList).filter(f =>
      ACCEPTED_EXTENSIONS.some(ext => f.name.toLowerCase().endsWith(ext))
    )
    if (valid.length > 0) onFilesChange([valid[0]])
  }, [onFilesChange])

  // Drop zone handlers (inside dialog)
  const handleDragEnter = useCallback((e: React.DragEvent) => {
    e.preventDefault(); e.stopPropagation()
    dragCounter.current++
    if (e.dataTransfer.types.includes('Files')) setIsDragging(true)
  }, [])
  const handleDragLeave = useCallback((e: React.DragEvent) => {
    e.preventDefault(); e.stopPropagation()
    dragCounter.current--
    if (dragCounter.current === 0) setIsDragging(false)
  }, [])
  const handleDragOver = useCallback((e: React.DragEvent) => { e.preventDefault(); e.stopPropagation() }, [])
  const handleDrop = useCallback((e: React.DragEvent) => {
    e.preventDefault(); e.stopPropagation()
    dragCounter.current = 0; setIsDragging(false)
    handleAddFiles(e.dataTransfer.files)
  }, [handleAddFiles])

  return (
    <Dialog open={open} onClose={onClose} maxWidth="md" fullWidth>
      <DialogTitle sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', pb: 0 }}>
        {t('logAnalyzer.analysisOptions.dialogTitle')}
        <IconButton size="small" onClick={onClose}><Close /></IconButton>
      </DialogTitle>

      <Tabs value={tab} onChange={(_, v) => setTab(v)} sx={{ px: 3 }}>
        <Tab label={t('logAnalyzer.analysisOptions.tabUpload')} />
        <Tab label={t('logAnalyzer.analysisOptions.tabAnalyses')} />
        <Tab label={t('logAnalyzer.analysisOptions.tabAdvanced')} />
      </Tabs>

      <DialogContent sx={{ minHeight: 340 }}>
        {/* ── Tab 0: Upload ── */}
        {tab === 0 && (
          <Stack spacing={2.5}>
            {/* Container source banner OR drop zone / file ready state */}
            {containerSource ? (
              <Box sx={{
                display: 'flex', alignItems: 'center', gap: 2,
                py: 3, px: 3, borderRadius: 2,
                border: '1px solid', borderColor: 'primary.main',
                bgcolor: alpha(theme.palette.primary.main, 0.04),
              }}>
                <CloudUpload sx={{ fontSize: 32, color: 'primary.main' }} />
                <Box>
                  <Typography variant="body2" fontWeight={600}>{t('logAnalyzer.analysisOptions.containerSource')}</Typography>
                  <Typography variant="body1" fontFamily="'JetBrains Mono', monospace" fontSize="0.9rem">{containerSource.name}</Typography>
                </Box>
              </Box>
            ) : files.length > 0 ? (
              /* ── File ready state ── */
              <Box
                component="label"
                onDragEnter={handleDragEnter}
                onDragLeave={handleDragLeave}
                onDragOver={handleDragOver}
                onDrop={handleDrop}
                sx={{
                  display: 'flex', alignItems: 'center', gap: 2,
                  py: 2.5, px: 3, borderRadius: 2, cursor: 'pointer',
                  border: '1px solid',
                  borderColor: isDragging ? 'primary.main' : alpha(theme.palette.success.main, 0.4),
                  bgcolor: isDragging ? alpha(theme.palette.primary.main, 0.06) : alpha(theme.palette.success.main, 0.03),
                  transition: 'all 0.2s ease',
                  '&:hover': { borderColor: isDragging ? 'primary.main' : alpha(theme.palette.success.main, 0.6), bgcolor: alpha(theme.palette.success.main, 0.05) },
                }}
              >
                <input type="file" hidden accept=".log,.txt,.out" onChange={(e) => { handleAddFiles(e.target.files); e.target.value = '' }} />
                <CheckCircleOutline sx={{ fontSize: 28, color: 'success.main' }} />
                <Box sx={{ flex: 1, minWidth: 0 }}>
                  {files.map((f, i) => (
                    <Box key={`${f.name}-${i}`}>
                      <Typography variant="body2" fontWeight={600} sx={{ fontFamily: "'JetBrains Mono', monospace", fontSize: '0.85rem' }} noWrap>
                        {f.name}
                      </Typography>
                      <Typography variant="caption" color="text.secondary">
                        {formatBytes(f.size)}
                      </Typography>
                    </Box>
                  ))}
                </Box>
                <Typography variant="caption" color="text.disabled" sx={{ whiteSpace: 'nowrap' }}>
                  {t('logAnalyzer.analysisOptions.clickOrDropToReplace')}
                </Typography>
              </Box>
            ) : (
              /* ── Empty drop zone ── */
              <Box
                component="label"
                onDragEnter={handleDragEnter}
                onDragLeave={handleDragLeave}
                onDragOver={handleDragOver}
                onDrop={handleDrop}
                sx={{
                  display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center',
                  py: 4, px: 2, borderRadius: 2, cursor: 'pointer',
                  border: '2px dashed',
                  borderColor: isDragging ? 'primary.main' : 'divider',
                  bgcolor: isDragging ? alpha(theme.palette.primary.main, 0.06) : 'transparent',
                  transition: 'all 0.2s ease',
                  '&:hover': { borderColor: 'primary.light', bgcolor: alpha(theme.palette.primary.main, 0.03) },
                }}
              >
                <input type="file" hidden accept=".log,.txt,.out" onChange={(e) => { handleAddFiles(e.target.files); e.target.value = '' }} />
                <CloudUpload sx={{ fontSize: 36, color: isDragging ? 'primary.main' : 'text.disabled', mb: 1 }} />
                <Typography variant="body2">
                  <strong>{t('logAnalyzer.upload.selectFiles')}</strong> {t('logAnalyzer.analysisOptions.orDragDrop')}
                </Typography>
                <Typography variant="caption" color="text.disabled">
                  {t('logAnalyzer.upload.supported')}
                </Typography>
              </Box>
            )}

            {/* Label field (file mode only) */}
            {!containerSource && files.length > 0 && (
              <TextField
                size="small"
                fullWidth
                label={t('logAnalyzer.analysisOptions.labelField')}
                placeholder={files[0]?.name ?? ''}
                slotProps={{ htmlInput: { maxLength: 50 } }}
                value={label}
                onChange={(e) => setLabel(e.target.value)}
              />
            )}

            {/* Preset + Threshold */}
            <Stack direction="row" spacing={2}>
              <Box sx={{ flex: 1 }}>
                <Typography variant="caption" color="text.secondary" fontWeight={600} sx={{ textTransform: 'uppercase', fontSize: '0.65rem', letterSpacing: '0.05em', mb: 0.5, display: 'block' }}>
                  {t('logAnalyzer.upload.preset')}
                </Typography>
                <Autocomplete
                  size="small"
                  fullWidth
                  disableClearable
                  options={presets}
                  getOptionLabel={(p) => p.name}
                  value={presets.find(p => p.name.toUpperCase() === selectedPreset.toUpperCase()) ?? presets[0] ?? null}
                  onChange={handlePresetChange}
                  isOptionEqualToValue={(o, v) => o.name === v.name}
                  renderInput={(params) => <TextField {...params} />}
                />
              </Box>
              <Box sx={{ width: 160 }}>
                <Typography variant="caption" color="text.secondary" fontWeight={600} sx={{ textTransform: 'uppercase', fontSize: '0.65rem', letterSpacing: '0.05em', mb: 0.5, display: 'block' }}>
                  {t('logAnalyzer.upload.slowThreshold')}
                </Typography>
                <TextField
                  size="small"
                  fullWidth
                  type="number"
                  value={slowThreshold}
                  onChange={(e) => setSlowThreshold(Number(e.target.value))}
                  slotProps={{ htmlInput: { min: 0 } }}
                />
              </Box>
            </Stack>
          </Stack>
        )}

        {/* ── Tab 1: Analyses ── */}
        {tab === 1 && (
          <Stack spacing={0.5}>
            <Typography variant="body2" color="text.secondary" sx={{ mb: 1 }}>
              {t('logAnalyzer.analysisOptions.description')}
            </Typography>
            {OPTION_ROWS.map(row => (
              <Box
                key={row.key}
                sx={{
                  display: 'flex', alignItems: 'center', gap: 1.5,
                  px: 1.5, py: 1, borderRadius: 1,
                  '&:hover': { bgcolor: 'action.hover' },
                }}
              >
                <Box sx={{ color: options[row.key] ? 'primary.main' : 'text.disabled', display: 'flex' }}>
                  {row.icon}
                </Box>
                <Box sx={{ flex: 1 }}>
                  <Stack direction="row" spacing={0.5} alignItems="center">
                    <Typography variant="body2" fontWeight={500}>{t(row.labelKey)}</Typography>
                    <Tooltip title={t(`logAnalyzer.analysisOptions.cost_${row.cost}` as const)} arrow>
                      <Typography sx={{ fontSize: '0.5rem', color: COST_COLORS[row.cost], cursor: 'default', lineHeight: 1 }}>
                        ●
                      </Typography>
                    </Tooltip>
                  </Stack>
                  <Typography variant="caption" color="text.secondary">{t(row.descKey)}</Typography>
                </Box>
                <Switch checked={options[row.key]} onChange={() => toggle(row.key)} size="small" />
              </Box>
            ))}
          </Stack>
        )}

        {/* ── Tab 2: Advanced ── */}
        {tab === 2 && (
          <Stack spacing={2}>
            <TextField size="small" fullWidth label={t('logAnalyzer.upload.logLineRegex')}
              value={customRegex.logLineRegex ?? ''} onChange={(e) => setCustomRegex(r => ({ ...r, logLineRegex: e.target.value }))} />
            <TextField size="small" fullWidth label={t('logAnalyzer.upload.apiCallRegex')}
              value={customRegex.apiCallRegex ?? ''} onChange={(e) => setCustomRegex(r => ({ ...r, apiCallRegex: e.target.value }))} />
            <TextField size="small" fullWidth label={t('logAnalyzer.upload.timestampFormat')}
              value={customRegex.timestampFormat ?? ''} onChange={(e) => setCustomRegex(r => ({ ...r, timestampFormat: e.target.value }))} />
            <TextField size="small" fullWidth label={t('logAnalyzer.upload.jobStartRegex')}
              value={customRegex.jobStartRegex ?? ''} onChange={(e) => setCustomRegex(r => ({ ...r, jobStartRegex: e.target.value }))} />
            <TextField size="small" fullWidth label={t('logAnalyzer.upload.jobEndRegex')}
              value={customRegex.jobEndRegex ?? ''} onChange={(e) => setCustomRegex(r => ({ ...r, jobEndRegex: e.target.value }))} />
            <TextField size="small" fullWidth label={t('logAnalyzer.upload.failureRegex')}
              value={customRegex.failureRegex ?? ''} onChange={(e) => setCustomRegex(r => ({ ...r, failureRegex: e.target.value }))} />
            <TextField size="small" fullWidth label={t('logAnalyzer.upload.sensitiveFields')}
              value={customRegex.sensitiveFieldNames ?? ''} onChange={(e) => setCustomRegex(r => ({ ...r, sensitiveFieldNames: e.target.value }))}
              helperText={t('logAnalyzer.upload.sensitiveFieldsHelp')} />
            <TextField size="small" fullWidth label={t('logAnalyzer.upload.criticalIssueExclusions')}
              value={customRegex.criticalIssueExclusions ?? ''} onChange={(e) => setCustomRegex(r => ({ ...r, criticalIssueExclusions: e.target.value }))}
              helperText={t('logAnalyzer.upload.criticalIssueExclusionsHelp')} />

            <Typography variant="subtitle2" sx={{ mt: 1 }}>{t('logAnalyzer.upload.customFields')}</Typography>
            {customFieldInputs.map((cf, idx) => (
              <Stack key={idx} direction="row" spacing={1.5} alignItems="flex-start">
                <TextField size="small" label={t('logAnalyzer.customFields.name')} placeholder="Entity Changes" value={cf.name}
                  onChange={(e) => { const next = [...customFieldInputs]; next[idx] = { ...next[idx], name: e.target.value }; setCustomFieldInputs(next) }}
                  sx={{ minWidth: 180, flex: 1 }} />
                <TextField size="small" label={t('logAnalyzer.customFields.regex')} placeholder="Updated -> (?<entity>\\w+):" value={cf.regex}
                  onChange={(e) => { const next = [...customFieldInputs]; next[idx] = { ...next[idx], regex: e.target.value }; setCustomFieldInputs(next) }}
                  sx={{ flex: 3 }}
                  helperText={t('logAnalyzer.customFields.regexHelp')} />
                <FormControlLabel
                  control={<Switch size="small" checked={cf.countOnly}
                    onChange={(_, v) => { const next = [...customFieldInputs]; next[idx] = { ...next[idx], countOnly: v }; setCustomFieldInputs(next) }} />}
                  label={<Typography variant="body2" sx={{ whiteSpace: 'nowrap' }}>{t('logAnalyzer.customFields.countOnly')}</Typography>}
                  sx={{ mt: 0.5 }}
                />
                <IconButton size="small" onClick={() => setCustomFieldInputs(customFieldInputs.filter((_, i) => i !== idx))} sx={{ mt: 0.5 }}>
                  <Clear sx={{ fontSize: 16 }} />
                </IconButton>
              </Stack>
            ))}
            <Button size="small" onClick={() => setCustomFieldInputs([...customFieldInputs, { name: '', regex: '', countOnly: false }])}>
              + {t('logAnalyzer.upload.addCustomField')}
            </Button>
          </Stack>
        )}
      </DialogContent>

      <DialogActions sx={{ justifyContent: 'space-between', px: 2 }}>
        {tab === 1 ? (
          <Stack direction="row" spacing={1}>
            <Button size="small" onClick={enableAll}>{t('logAnalyzer.analysisOptions.enableAll')}</Button>
            <Button size="small" onClick={disableAll}>{t('logAnalyzer.analysisOptions.disableAll')}</Button>
          </Stack>
        ) : (
          <Box />
        )}
        <Button variant="contained" onClick={handleStart} disabled={!containerSource && files.length === 0}>
          {t('logAnalyzer.analysisOptions.startAnalysis')}
        </Button>
      </DialogActions>
    </Dialog>
  )
}
