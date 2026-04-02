import { useState, useEffect, useCallback } from 'react'
import {
  Dialog, DialogTitle, DialogContent, DialogActions, Button,
  Box, Typography, Switch, Stack,
} from '@mui/material'
import {
  SyncAlt, Work, ErrorOutline, WarningAmber, BugReport, Code, Extension,
} from '@mui/icons-material'
import { useTranslation } from 'react-i18next'

export interface AnalysisOptions {
  apiCalls: boolean
  jobs: boolean
  failures: boolean
  criticalIssues: boolean
  npeAnalysis: boolean
  exceptionAnalysis: boolean
  customFields: boolean
}

const STORAGE_KEY = 'log-analyzer-options'

const ALL_ON: AnalysisOptions = {
  apiCalls: true, jobs: true, failures: true,
  criticalIssues: true, npeAnalysis: true,
  exceptionAnalysis: true, customFields: true,
}

function loadOptions(): AnalysisOptions {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (raw) {
      const parsed = JSON.parse(raw)
      return { ...ALL_ON, ...parsed }
    }
  } catch { /* ignore */ }
  return { ...ALL_ON }
}

function saveOptions(options: AnalysisOptions) {
  localStorage.setItem(STORAGE_KEY, JSON.stringify(options))
}

const OPTION_ROWS = [
  { key: 'apiCalls' as const, icon: <SyncAlt />, labelKey: 'logAnalyzer.analysisOptions.apiCalls' as const, descKey: 'logAnalyzer.analysisOptions.apiCallsDesc' as const },
  { key: 'jobs' as const, icon: <Work />, labelKey: 'logAnalyzer.analysisOptions.jobs' as const, descKey: 'logAnalyzer.analysisOptions.jobsDesc' as const },
  { key: 'failures' as const, icon: <ErrorOutline />, labelKey: 'logAnalyzer.analysisOptions.failures' as const, descKey: 'logAnalyzer.analysisOptions.failuresDesc' as const },
  { key: 'criticalIssues' as const, icon: <WarningAmber />, labelKey: 'logAnalyzer.analysisOptions.criticalIssues' as const, descKey: 'logAnalyzer.analysisOptions.criticalIssuesDesc' as const },
  { key: 'npeAnalysis' as const, icon: <BugReport />, labelKey: 'logAnalyzer.analysisOptions.npeAnalysis' as const, descKey: 'logAnalyzer.analysisOptions.npeAnalysisDesc' as const },
  { key: 'exceptionAnalysis' as const, icon: <Code />, labelKey: 'logAnalyzer.analysisOptions.exceptionAnalysis' as const, descKey: 'logAnalyzer.analysisOptions.exceptionAnalysisDesc' as const },
  { key: 'customFields' as const, icon: <Extension />, labelKey: 'logAnalyzer.analysisOptions.customFields' as const, descKey: 'logAnalyzer.analysisOptions.customFieldsDesc' as const },
]

export function AnalysisOptionsDialog({ open, onClose, onStart }: {
  open: boolean
  onClose: () => void
  onStart: (options: AnalysisOptions) => void
}) {
  const { t } = useTranslation()
  const [options, setOptions] = useState<AnalysisOptions>(ALL_ON)

  useEffect(() => {
    if (open) {
      setOptions(loadOptions())
    }
  }, [open])

  const toggle = useCallback((key: keyof AnalysisOptions) => {
    setOptions(prev => ({ ...prev, [key]: !prev[key] }))
  }, [])

  const enableAll = useCallback(() => setOptions({ ...ALL_ON }), [])
  const disableAll = useCallback(() => setOptions({
    apiCalls: false, jobs: false, failures: false,
    criticalIssues: false, npeAnalysis: false,
    exceptionAnalysis: false, customFields: false,
  }), [])

  const handleStart = useCallback(() => {
    saveOptions(options)
    onStart(options)
  }, [options, onStart])

  return (
    <Dialog open={open} onClose={onClose} maxWidth="sm" fullWidth>
      <DialogTitle>{t('logAnalyzer.analysisOptions.title')}</DialogTitle>
      <DialogContent dividers>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
          {t('logAnalyzer.analysisOptions.description')}
        </Typography>
        <Stack spacing={0.5}>
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
                <Typography variant="body2" fontWeight={500}>{t(row.labelKey)}</Typography>
                <Typography variant="caption" color="text.secondary">{t(row.descKey)}</Typography>
              </Box>
              <Switch
                checked={options[row.key]}
                onChange={() => toggle(row.key)}
                size="small"
              />
            </Box>
          ))}
        </Stack>
      </DialogContent>
      <DialogActions sx={{ justifyContent: 'space-between', px: 2 }}>
        <Stack direction="row" spacing={1}>
          <Button size="small" onClick={enableAll}>{t('logAnalyzer.analysisOptions.enableAll')}</Button>
          <Button size="small" onClick={disableAll}>{t('logAnalyzer.analysisOptions.disableAll')}</Button>
        </Stack>
        <Button variant="contained" onClick={handleStart}>
          {t('logAnalyzer.analysisOptions.startAnalysis')}
        </Button>
      </DialogActions>
    </Dialog>
  )
}
