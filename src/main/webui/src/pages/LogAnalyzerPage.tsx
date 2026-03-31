import { useState, useEffect, useCallback, useMemo } from 'react'
import {
  Autocomplete, Box, Typography, Button, Paper, Tabs, Tab,
  Chip, TextField, Collapse, IconButton, Switch, FormControlLabel,
  LinearProgress, Stack,
} from '@mui/material'
import {
  CloudUpload, ExpandMore, MergeType, Clear,
} from '@mui/icons-material'
import { useTranslation } from 'react-i18next'
import { useLocation } from 'react-router-dom'
import { useNotification } from '../components/NotificationProvider'
import { SummaryCard } from '../components/log-analyzer/SummaryCard'
import { ApiCallsTab } from '../components/log-analyzer/ApiCallsTab'
import { EndpointStatsTab } from '../components/log-analyzer/EndpointStatsTab'
import { RawLogTab } from '../components/log-analyzer/RawLogTab'
import { ThreadViewTab } from '../components/log-analyzer/ThreadViewTab'
import { JobsTab } from '../components/log-analyzer/JobsTab'
import { FailuresTab } from '../components/log-analyzer/FailuresTab'
import { CustomFieldTab } from '../components/log-analyzer/CustomFieldTab'
import type {
  AnalysisSummary, LogPreset, UploadOptions,
} from '../services/logAnalyzerService'
import * as logService from '../services/logAnalyzerService'

export default function LogAnalyzerPage() {
  const { t } = useTranslation()
  const { notify } = useNotification()

  const [presets, setPresets] = useState<LogPreset[]>([])
  const [defaultPreset, setDefaultPreset] = useState('WILDFLY')
  const [analyses, setAnalyses] = useState<AnalysisSummary[]>([])
  const location = useLocation()
  const [selectedId, setSelectedId] = useState<string | null>(
    (location.state as { analysisId?: string } | null)?.analysisId ?? null
  )
  const [uploading, setUploading] = useState(false)
  const [activeTab, setActiveTab] = useState(0)
  const [jumpToLine, setJumpToLine] = useState<number | null>(null)

  // Upload form
  const [selectedPreset, setSelectedPreset] = useState('')
  const [slowThreshold, setSlowThreshold] = useState(1000)
  const [advancedOpen, setAdvancedOpen] = useState(false)
  const [customRegex, setCustomRegex] = useState<Partial<UploadOptions>>({})

  // Custom fields
  const [customFieldInputs, setCustomFieldInputs] = useState<Array<{ name: string; regex: string; countOnly: boolean }>>([])

  // Compose
  const [composeIds, setComposeIds] = useState<Set<string>>(new Set())

  useEffect(() => {
    logService.getStatus().then((s) => {
      setPresets(s.presets)
      setDefaultPreset(s.defaultPreset)
      setSelectedPreset(s.defaultPreset)
    }).catch(() => {})
    refreshList()
  }, [])

  // Reset tab index when selectedId changes
  useEffect(() => {
    setActiveTab(0)
  }, [selectedId])

  const refreshList = useCallback(() => {
    logService.listAnalyses().then(setAnalyses).catch(() => {})
  }, [])

  const selected = useMemo(
    () => analyses.find((a) => a.id === selectedId) ?? null,
    [analyses, selectedId],
  )

  const handleUpload = useCallback(async (fileList: FileList | null) => {
    if (!fileList || fileList.length === 0) return
    setUploading(true)
    try {
      const files = Array.from(fileList)
      const currentPreset = presets.find(p => p.name.toUpperCase() === selectedPreset.toUpperCase())
      const opts: UploadOptions = {
        preset: selectedPreset,
        slowThresholdMs: slowThreshold,
        ...customRegex,
      }
      if (currentPreset && customRegex.logLineRegex && customRegex.logLineRegex !== currentPreset.logLineRegex) {
        opts.logLineRegex = customRegex.logLineRegex
      }
      if (customFieldInputs.length > 0) {
        const validFields = customFieldInputs.filter(cf => cf.name.trim() && cf.regex.trim())
        if (validFields.length > 0) {
          opts.customFields = JSON.stringify(validFields)
        }
      }
      const result = await logService.uploadFiles(files, opts)
      setSelectedId(result.id)
      refreshList()
      notify(t('logAnalyzer.upload.success'), 'success')
    } catch (err) {
      notify(err instanceof Error ? err.message : t('logAnalyzer.upload.error'), 'error')
    } finally {
      setUploading(false)
    }
  }, [selectedPreset, slowThreshold, customRegex, presets, refreshList, notify, t])

  const handleDelete = useCallback(async (id: string) => {
    try {
      await logService.deleteAnalysis(id)
      if (selectedId === id) setSelectedId(null)
      refreshList()
    } catch (err) {
      notify(err instanceof Error ? err.message : String(err), 'error')
    }
  }, [selectedId, refreshList, notify, t])

  const handleCompose = useCallback(async () => {
    if (composeIds.size < 2) return
    try {
      const result = await logService.composeAnalyses(Array.from(composeIds), selectedPreset, slowThreshold)
      setSelectedId(result.id)
      setComposeIds(new Set())
      refreshList()
      notify(t('logAnalyzer.compose.success'), 'success')
    } catch (err) {
      notify(err instanceof Error ? err.message : t('logAnalyzer.compose.error'), 'error')
    }
  }, [composeIds, selectedPreset, slowThreshold, refreshList, notify, t])

  const presetObj = useMemo(
    () => presets.find(p => p.name.toUpperCase() === selectedPreset.toUpperCase()),
    [presets, selectedPreset],
  )

  const handleJumpComplete = useCallback(() => setJumpToLine(null), [])

  const handleJumpToLine = useCallback((lineNumber: number) => {
    setJumpToLine(lineNumber)
  }, [])

  const tabs = useMemo(() => {
    if (!selected) return []
    const list = [
      { key: 'apiCalls', label: t('logAnalyzer.tabs.apiCalls'), component: <ApiCallsTab analysisId={selected.id} sensitiveFields={presetObj?.sensitiveFieldNames ?? []} onJumpToLine={handleJumpToLine} /> },
      { key: 'stats', label: t('logAnalyzer.tabs.endpointStats'), component: <EndpointStatsTab analysisId={selected.id} /> },
      { key: 'rawLog', label: t('logAnalyzer.tabs.rawLog'), component: null },
      { key: 'threadView', label: t('logAnalyzer.tabs.threadView'), component: <ThreadViewTab analysisId={selected.id} /> },
    ]
    if (selected.jobExecutionCount > 0) list.push({ key: 'jobs', label: t('logAnalyzer.tabs.jobs'), component: <JobsTab analysisId={selected.id} onJumpToLine={handleJumpToLine} /> })
    if (selected.repeatedFailureCount > 0) list.push({ key: 'failures', label: t('logAnalyzer.tabs.failures'), component: <FailuresTab analysisId={selected.id} onJumpToLine={handleJumpToLine} /> })
    if (selected.customFields) {
      for (const cf of selected.customFields) {
        if (cf.matchCount > 0 && !cf.countOnly) {
          list.push({
            key: `custom-${cf.fieldName}`,
            label: cf.fieldName,
            component: <CustomFieldTab analysisId={selected.id} fieldName={cf.fieldName} onJumpToLine={handleJumpToLine} />,
          })
        }
      }
    }
    return list
  }, [selected, t, presetObj, handleJumpToLine])

  const rawLogTabIndex = useMemo(() => tabs.findIndex(t => t.key === 'rawLog'), [tabs])

  // Switch to rawLog tab when jumpToLine is set (after handleJumpToLine is called)
  useEffect(() => {
    if (jumpToLine != null && rawLogTabIndex >= 0) {
      setActiveTab(rawLogTabIndex)
    }
  }, [jumpToLine, rawLogTabIndex])

  return (
    <Box sx={{ maxWidth: 1600, mx: 'auto', p: 3 }}>
      <Typography variant="h4" fontWeight={700} mb={3}>
        {t('logAnalyzer.title')}
      </Typography>

      {/* ---- Upload Panel ---- */}
      <Paper sx={{ p: 3, mb: 3 }}>
        <Stack direction={{ xs: 'column', md: 'row' }} spacing={2} alignItems="flex-start">
          <Button
            variant="contained"
            component="label"
            startIcon={<CloudUpload />}
            disabled={uploading}
          >
            {t('logAnalyzer.upload.selectFiles')}
            <input type="file" hidden multiple accept=".log,.txt,.out" onChange={(e) => handleUpload(e.target.files)} />
          </Button>
          <Autocomplete
            size="small"
            sx={{ minWidth: 200 }}
            disableClearable
            options={presets}
            getOptionLabel={(p) => p.name}
            value={presets.find(p => p.name.toUpperCase() === selectedPreset.toUpperCase()) ?? presets[0] ?? undefined}
            onChange={(_, p) => {
              if (!p) return
              setSelectedPreset(p.name.toUpperCase())
              setCustomRegex({
                logLineRegex: p.logLineRegex,
                apiCallRegex: p.apiCallRegex ?? undefined,
                timestampFormat: p.timestampFormat,
                jobStartRegex: p.jobStartRegex ?? undefined,
                jobEndRegex: p.jobEndRegex ?? undefined,
                failureRegex: p.failureRegex ?? undefined,
                sensitiveFieldNames: p.sensitiveFieldNames?.join(','),
              })
              setCustomFieldInputs(p.customFields?.map(cf => ({ name: cf.name, regex: cf.regex, countOnly: cf.countOnly })) ?? [])
            }}
            isOptionEqualToValue={(o, v) => o.name === v.name}
            renderInput={(params) => <TextField {...params} label={t('logAnalyzer.upload.preset')} />}
          />
          <TextField
            size="small"
            label={t('logAnalyzer.upload.slowThreshold')}
            type="number"
            value={slowThreshold}
            onChange={(e) => setSlowThreshold(Number(e.target.value))}
            sx={{ width: 130 }}
            slotProps={{ htmlInput: { min: 0 } }}
          />
          <Button size="small" onClick={() => setAdvancedOpen(!advancedOpen)} endIcon={<ExpandMore />}>
            {t('logAnalyzer.upload.advanced')}
          </Button>
        </Stack>
        {uploading && <LinearProgress sx={{ mt: 2 }} />}

        <Collapse in={advancedOpen}>
          <Stack spacing={2} sx={{ mt: 2 }}>
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
            <Typography variant="subtitle2" sx={{ mt: 2 }}>{t('logAnalyzer.upload.customFields')}</Typography>
            {customFieldInputs.map((cf, idx) => (
              <Stack key={idx} direction="row" spacing={1} alignItems="center">
                <TextField size="small" label={t('logAnalyzer.customFields.name')} placeholder="Entity Changes" value={cf.name}
                  onChange={(e) => { const next = [...customFieldInputs]; next[idx] = { ...next[idx], name: e.target.value }; setCustomFieldInputs(next) }}
                  sx={{ flex: 1 }} />
                <TextField size="small" label={t('logAnalyzer.customFields.regex')} placeholder="Updated -> (?<entity>\w+):" value={cf.regex}
                  onChange={(e) => { const next = [...customFieldInputs]; next[idx] = { ...next[idx], regex: e.target.value }; setCustomFieldInputs(next) }}
                  sx={{ flex: 2 }}
                  helperText={t('logAnalyzer.customFields.regexHelp')} />
                <FormControlLabel
                  control={<Switch size="small" checked={cf.countOnly}
                    onChange={(_, v) => { const next = [...customFieldInputs]; next[idx] = { ...next[idx], countOnly: v }; setCustomFieldInputs(next) }} />}
                  label={<Typography variant="body2">{t('logAnalyzer.customFields.countOnly')}</Typography>}
                />
                <IconButton size="small" onClick={() => setCustomFieldInputs(customFieldInputs.filter((_, i) => i !== idx))}>
                  <Clear sx={{ fontSize: 16 }} />
                </IconButton>
              </Stack>
            ))}
            <Button size="small" onClick={() => setCustomFieldInputs([...customFieldInputs, { name: '', regex: '', countOnly: false }])}>
              + {t('logAnalyzer.upload.addCustomField')}
            </Button>
          </Stack>
        </Collapse>

        {/* File list */}
        {analyses.length > 0 && (
          <Box sx={{ mt: 2 }}>
            <Stack direction="row" alignItems="center" spacing={1} mb={1}>
              <Typography variant="subtitle2">{t('logAnalyzer.upload.analyses')}</Typography>
              {composeIds.size >= 2 && (
                <Button size="small" startIcon={<MergeType />} onClick={handleCompose}>
                  {t('logAnalyzer.compose.button')} ({composeIds.size})
                </Button>
              )}
            </Stack>
            {analyses.map((a) => (
              <Chip
                key={a.id}
                label={`${a.sourceFiles.map(f => f.filename).join(', ')} (${a.totalLineCount.toLocaleString()} lines)`}
                onClick={() => setSelectedId(a.id)}
                onDelete={() => handleDelete(a.id)}
                variant={selectedId === a.id ? 'filled' : 'outlined'}
                color={selectedId === a.id ? 'primary' : 'default'}
                sx={{ mr: 1, mb: 1, cursor: 'pointer' }}
                icon={
                  <input
                    type="checkbox"
                    checked={composeIds.has(a.id)}
                    onChange={(e) => {
                      e.stopPropagation()
                      setComposeIds(prev => {
                        const next = new Set(prev)
                        if (next.has(a.id)) next.delete(a.id)
                        else next.add(a.id)
                        return next
                      })
                    }}
                    onClick={(e) => e.stopPropagation()}
                    style={{ marginLeft: 8, cursor: 'pointer' }}
                  />
                }
              />
            ))}
          </Box>
        )}
      </Paper>

      {/* ---- Dashboard ---- */}
      {selected && (
        <>
          <Stack direction="row" spacing={2} mb={3} flexWrap="wrap" useFlexGap>
            <SummaryCard label={t('logAnalyzer.dashboard.totalLines')} value={selected.totalLineCount.toLocaleString()} />
            <SummaryCard label={t('logAnalyzer.dashboard.apiCalls')} value={selected.apiCallCount.toLocaleString()} />
            <SummaryCard label={t('logAnalyzer.dashboard.threads')} value={selected.threadCount} />
            <SummaryCard label={t('logAnalyzer.dashboard.endpoints')} value={selected.endpointCount} />
            <SummaryCard label={t('logAnalyzer.dashboard.errors')} value={selected.errorCount} color="error.main" />
            {selected.jobExecutionCount > 0 && (
              <SummaryCard label={t('logAnalyzer.dashboard.jobs')} value={selected.jobExecutionCount} />
            )}
            {selected.repeatedFailureCount > 0 && (
              <SummaryCard label={t('logAnalyzer.dashboard.failures')} value={selected.repeatedFailureCount} color="warning.main" />
            )}
            {selected.customFields?.filter(cf => cf.matchCount > 0).map(cf => (
              <SummaryCard key={cf.fieldName} label={cf.fieldName} value={cf.matchCount.toLocaleString()} color="primary.main" />
            ))}
          </Stack>

          {/* Level counts */}
          <Stack direction="row" spacing={1} mb={3} flexWrap="wrap" useFlexGap>
            {Object.entries(selected.levelCounts).map(([level, count]) => (
              <Chip key={level} label={`${level}: ${count.toLocaleString()}`} size="small"
                color={level === 'ERROR' || level === 'FATAL' || level === 'SEVERE' ? 'error' : level === 'WARNING' || level === 'WARN' ? 'warning' : 'default'} />
            ))}
          </Stack>

          {/* ---- Tabs ---- */}
          <Paper>
            <Tabs value={activeTab} onChange={(_, v) => setActiveTab(v)} variant="scrollable" scrollButtons="auto">
              {tabs.map(tab => <Tab key={tab.key} label={tab.label} />)}
            </Tabs>
            <Box sx={{ p: 2 }}>
              {tabs[activeTab] && (
                tabs[activeTab].key === 'rawLog'
                  ? <RawLogTab analysisId={selected!.id} levelCounts={selected!.levelCounts} jumpToLine={jumpToLine} onJumpComplete={handleJumpComplete} />
                  : tabs[activeTab].component
              )}
            </Box>
          </Paper>
        </>
      )}
    </Box>
  )
}
