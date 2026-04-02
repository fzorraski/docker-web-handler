import { useState, useEffect, useCallback } from 'react'
import {
  Dialog, DialogTitle, DialogContent, DialogActions, Button,
  Box, Typography, Switch, Stack, Tooltip,
} from '@mui/material'
import {
  SyncAlt, Work, ErrorOutline, WarningAmber, BugReport, Code, Extension, AccountTree,
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
  threadView: boolean
}

const STORAGE_KEY = 'log-analyzer-options'

const ALL_ON: AnalysisOptions = {
  apiCalls: true, jobs: true, failures: true,
  criticalIssues: true, npeAnalysis: true,
  exceptionAnalysis: true, customFields: true,
  threadView: true,
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

type CostLevel = 'low' | 'medium' | 'high'
const COST_COLORS: Record<CostLevel, string> = { low: '#4caf50', medium: '#ff9800', high: '#f44336' }
const COST_DOT = '●'

const OPTION_ROWS = [
  { key: 'apiCalls' as const, icon: <SyncAlt />, cost: 'high' as CostLevel, labelKey: 'logAnalyzer.analysisOptions.apiCalls' as const, descKey: 'logAnalyzer.analysisOptions.apiCallsDesc' as const },
  { key: 'jobs' as const, icon: <Work />, cost: 'low' as CostLevel, labelKey: 'logAnalyzer.analysisOptions.jobs' as const, descKey: 'logAnalyzer.analysisOptions.jobsDesc' as const },
  { key: 'failures' as const, icon: <ErrorOutline />, cost: 'low' as CostLevel, labelKey: 'logAnalyzer.analysisOptions.failures' as const, descKey: 'logAnalyzer.analysisOptions.failuresDesc' as const },
  { key: 'criticalIssues' as const, icon: <WarningAmber />, cost: 'medium' as CostLevel, labelKey: 'logAnalyzer.analysisOptions.criticalIssues' as const, descKey: 'logAnalyzer.analysisOptions.criticalIssuesDesc' as const },
  { key: 'npeAnalysis' as const, icon: <BugReport />, cost: 'high' as CostLevel, labelKey: 'logAnalyzer.analysisOptions.npeAnalysis' as const, descKey: 'logAnalyzer.analysisOptions.npeAnalysisDesc' as const },
  { key: 'exceptionAnalysis' as const, icon: <Code />, cost: 'high' as CostLevel, labelKey: 'logAnalyzer.analysisOptions.exceptionAnalysis' as const, descKey: 'logAnalyzer.analysisOptions.exceptionAnalysisDesc' as const },
  { key: 'customFields' as const, icon: <Extension />, cost: 'medium' as CostLevel, labelKey: 'logAnalyzer.analysisOptions.customFields' as const, descKey: 'logAnalyzer.analysisOptions.customFieldsDesc' as const },
  { key: 'threadView' as const, icon: <AccountTree />, cost: 'medium' as CostLevel, labelKey: 'logAnalyzer.analysisOptions.threadView' as const, descKey: 'logAnalyzer.analysisOptions.threadViewDesc' as const },
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
    threadView: false,
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
                <Stack direction="row" spacing={0.5} alignItems="center">
                  <Typography variant="body2" fontWeight={500}>{t(row.labelKey)}</Typography>
                  <Tooltip title={t(`logAnalyzer.analysisOptions.cost_${row.cost}` as const)} arrow>
                    <Typography sx={{ fontSize: '0.5rem', color: COST_COLORS[row.cost], cursor: 'default', lineHeight: 1 }}>
                      {COST_DOT}
                    </Typography>
                  </Tooltip>
                </Stack>
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
