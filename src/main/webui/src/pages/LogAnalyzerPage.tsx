import { useState, useEffect, useCallback, useMemo, useRef } from 'react'
import {
  Autocomplete, Box, Typography, Button, Paper, Tabs, Tab,
  Chip, TextField, Collapse, IconButton, Switch, FormControlLabel,
  Stack, Dialog, DialogTitle, DialogContent, DialogActions,
  Alert, AlertTitle, LinearProgress, Tooltip, Badge,
} from '@mui/material'
import {
  CloudUpload, ExpandMore, MergeType, Clear, Cancel, DeleteForever, Visibility, Warning,
} from '@mui/icons-material'
import { useTranslation } from 'react-i18next'
import { useLocation } from 'react-router-dom'
import { useNotification } from '../components/NotificationProvider'
import { useSseOperation } from '../hooks/useSseOperation'
import OperationProgress, { LOG_ANALYSIS_STEPS } from '../components/OperationProgress'
import { prepareLogAnalysis, streamLogAnalysis, cancelLogAnalysis, subscribeLogAnalysisUpdates, setLogAnalysisViewing } from '../services/sseService'
import { AnalysisOptionsDialog } from '../components/log-analyzer/AnalysisOptionsDialog'
import type { AnalysisOptions } from '../components/log-analyzer/AnalysisOptionsDialog'
import { SummaryCard } from '../components/log-analyzer/SummaryCard'
import { ApiCallsTab } from '../components/log-analyzer/ApiCallsTab'
import { EndpointStatsTab } from '../components/log-analyzer/EndpointStatsTab'
import { RawLogTab } from '../components/log-analyzer/RawLogTab'
import { ThreadViewTab } from '../components/log-analyzer/ThreadViewTab'
import { JobsTab } from '../components/log-analyzer/JobsTab'
import { FailuresTab } from '../components/log-analyzer/FailuresTab'
import { CriticalIssuesTab } from '../components/log-analyzer/CriticalIssuesTab'
import { NpeAnalysisTab } from '../components/log-analyzer/NpeAnalysisTab'
import { ExceptionAnalysisTab } from '../components/log-analyzer/ExceptionAnalysisTab'
import { CustomFieldTab } from '../components/log-analyzer/CustomFieldTab'
import { PerformanceInsightsTab } from '../components/log-analyzer/PerformanceInsightsTab'
import { AnomalyDetectionTab } from '../components/log-analyzer/AnomalyDetectionTab'
import type {
  AnalysisSummary, LogPreset, UploadOptions,
} from '../services/logAnalyzerService'
import * as logService from '../services/logAnalyzerService'

export default function LogAnalyzerPage() {
  const { t } = useTranslation()
  const { notify } = useNotification()
  const clientTokenRef = useRef(crypto.randomUUID())

  const [presets, setPresets] = useState<LogPreset[]>([])
  const [defaultPreset, setDefaultPreset] = useState('WILDFLY')
  const [maxFiles, setMaxFiles] = useState(5)
  const [analyses, setAnalyses] = useState<AnalysisSummary[]>([])
  const location = useLocation()
  const [selectedId, setSelectedId] = useState<string | null>(
    (location.state as { analysisId?: string } | null)?.analysisId ?? null
  )
  const sse = useSseOperation()
  const [analysisTicket, setAnalysisTicket] = useState<string | null>(null)
  const [activeTab, setActiveTab] = useState(0)
  const [jumpToLine, setJumpToLine] = useState<number | null>(null)
  const [jumpToRange, setJumpToRange] = useState<{ from: number; to: number } | null>(null)
  const [insightsEndpoint, setInsightsEndpoint] = useState<string | null>(null)
  const [insightsTimestamp, setInsightsTimestamp] = useState<string | null>(null)

  // Upload form
  const [selectedPreset, setSelectedPreset] = useState('')
  const [slowThreshold, setSlowThreshold] = useState(1000)
  const [advancedOpen, setAdvancedOpen] = useState(false)
  const [customRegex, setCustomRegex] = useState<Partial<UploadOptions>>({})

  // Custom fields
  const [customFieldInputs, setCustomFieldInputs] = useState<Array<{ name: string; regex: string; countOnly: boolean }>>([])

  // Analysis options dialog
  const [optionsDialogOpen, setOptionsDialogOpen] = useState(false)
  const [pendingFiles, setPendingFiles] = useState<File[]>([])
  const [lastAnalysisOptions, setLastAnalysisOptions] = useState<AnalysisOptions | null>(null)

  // Active analyses from other users (broadcast)
  const [activeAnalyses, setActiveAnalyses] = useState<string[]>([])

  // Delete confirmation
  const [deleteTarget, setDeleteTarget] = useState<{ id: string; label: string } | null>(null)

  // Viewer counts from broadcast (analysisId → number of viewers)
  const [viewerCounts, setViewerCounts] = useState<Record<string, number>>({})

  // Capacity confirmation: when at max, ask before evicting the oldest
  const [capacityConfirmOpen, setCapacityConfirmOpen] = useState(false)
  const [pendingAnalysisOptions, setPendingAnalysisOptions] = useState<AnalysisOptions | null>(null)

  // Compose
  const [composeIds, setComposeIds] = useState<Set<string>>(new Set())

  useEffect(() => {
    logService.getStatus().then((s) => {
      setPresets(s.presets)
      setDefaultPreset(s.defaultPreset)
      setSelectedPreset(s.defaultPreset)
      if (s.maxFiles) setMaxFiles(s.maxFiles)
    }).catch(() => {})
    refreshList()
  }, [])

  // Reset tab index when selectedId changes
  useEffect(() => {
    setActiveTab(0)
  }, [selectedId])

  // Notify server which analysis this client is viewing
  useEffect(() => {
    setLogAnalysisViewing(clientTokenRef.current, selectedId)
    return () => { setLogAnalysisViewing(clientTokenRef.current, null) }
  }, [selectedId])

  const refreshList = useCallback(() => {
    logService.listAnalyses().then(setAnalyses).catch(() => {})
  }, [])

  // Subscribe to log analysis broadcasts from other users
  useEffect(() => {
    return subscribeLogAnalysisUpdates(
      (ev) => setActiveAnalyses(prev => [...prev, ev.filenames]),
      (ev) => {
        setActiveAnalyses(prev => prev.filter(f => f !== ev.filenames))
        refreshList()
      },
      (ev) => {
        if (ev.analysisId && selectedId === ev.analysisId) setSelectedId(null)
        refreshList()
      },
      (counts) => setViewerCounts(counts),
    )
  }, [refreshList, selectedId])

  const selected = useMemo(
    () => analyses.find((a) => a.id === selectedId) ?? null,
    [analyses, selectedId],
  )

  const handleUpload = useCallback((fileList: FileList | null) => {
    if (!fileList || fileList.length === 0) return
    setPendingFiles(Array.from(fileList))
    setOptionsDialogOpen(true)
  }, [])

  const doStartAnalysis = useCallback(async (analysisOptions: AnalysisOptions) => {
    setLastAnalysisOptions(analysisOptions)
    if (pendingFiles.length === 0) return

    const currentPreset = presets.find(p => p.name.toUpperCase() === selectedPreset.toUpperCase())
    const { threadView: _tv, ...backendOptions } = analysisOptions
    const regexKeys = ['logLineRegex', 'apiCallRegex', 'timestampFormat', 'jobStartRegex', 'jobEndRegex', 'failureRegex', 'sensitiveFieldNames'] as const
    const formFields: Record<string, string | undefined> = {
      preset: selectedPreset,
      slowThresholdMs: String(slowThreshold),
      options: JSON.stringify(backendOptions),
    }
    for (const key of regexKeys) {
      const val = customRegex[key]
      if (val != null) formFields[key] = String(val)
    }
    if (currentPreset && customRegex.logLineRegex && customRegex.logLineRegex !== currentPreset.logLineRegex) {
      formFields.logLineRegex = customRegex.logLineRegex
    }
    if (customFieldInputs.length > 0) {
      const validFields = customFieldInputs.filter(cf => cf.name.trim() && cf.regex.trim())
      if (validFields.length > 0) {
        formFields.customFields = JSON.stringify(validFields)
      }
    }

    try {
      const ticket = await prepareLogAnalysis(pendingFiles, formFields)
      setAnalysisTicket(ticket)
      sse.start(
        (onEvent, onDone, onError) => streamLogAnalysis(ticket, onEvent, onDone, onError),
        (event) => {
          if (event.detail) {
            setSelectedId(event.detail)
            refreshList()
          }
          notify(t('logAnalyzer.upload.success'), 'success')
          setPendingFiles([])
          sse.reset()
        },
        () => {
          setPendingFiles([])
        },
      )
    } catch (err) {
      notify(err instanceof Error ? err.message : t('logAnalyzer.upload.error'), 'error')
      setPendingFiles([])
    }
  }, [pendingFiles, selectedPreset, slowThreshold, customRegex, presets, customFieldInputs, refreshList, notify, t, sse])

  const handleStartAnalysis = useCallback(async (analysisOptions: AnalysisOptions) => {
    setOptionsDialogOpen(false)
    if (analyses.length >= maxFiles) {
      setPendingAnalysisOptions(analysisOptions)
      setCapacityConfirmOpen(true)
    } else {
      doStartAnalysis(analysisOptions)
    }
  }, [analyses.length, maxFiles, doStartAnalysis])

  const handleCapacityConfirm = useCallback(() => {
    setCapacityConfirmOpen(false)
    if (pendingAnalysisOptions) {
      doStartAnalysis(pendingAnalysisOptions)
      setPendingAnalysisOptions(null)
    }
  }, [pendingAnalysisOptions, doStartAnalysis])

  const handleCapacityCancel = useCallback(() => {
    setCapacityConfirmOpen(false)
    setPendingAnalysisOptions(null)
  }, [])

  const handleCancelAnalysis = useCallback(() => {
    if (analysisTicket) cancelLogAnalysis(analysisTicket)
  }, [analysisTicket])

  const handleDeleteClick = useCallback((id: string, label: string) => {
    setDeleteTarget({ id, label })
  }, [])

  const handleDeleteConfirm = useCallback(async () => {
    if (!deleteTarget) return
    const { id } = deleteTarget
    setDeleteTarget(null)
    try {
      await logService.deleteAnalysis(id)
      if (selectedId === id) setSelectedId(null)
      refreshList()
    } catch (err) {
      notify(err instanceof Error ? err.message : String(err), 'error')
    }
  }, [deleteTarget, selectedId, refreshList, notify])

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

  const handleJumpToRange = useCallback((from: number, to: number) => {
    setJumpToRange({ from, to })
  }, [])

  const handleRangeComplete = useCallback(() => { setJumpToRange(null); setJumpToLine(null) }, [])

  const handleViewInsights = useCallback((endpoint: string) => {
    setInsightsEndpoint(endpoint)
    setInsightsTimestamp(null)
  }, [])

  const handleViewInsightsForCall = useCallback((endpoint: string, timestamp: string) => {
    setInsightsEndpoint(null)
    setInsightsTimestamp(timestamp)
  }, [])

  const handleInsightsConsumed = useCallback(() => {
    setInsightsEndpoint(null)
    setInsightsTimestamp(null)
  }, [])

  const tabs = useMemo(() => {
    if (!selected) return []
    const list = [
      { key: 'apiCalls', label: t('logAnalyzer.tabs.apiCalls'), component: <ApiCallsTab analysisId={selected.id} sensitiveFields={presetObj?.sensitiveFieldNames ?? []} onJumpToLine={handleJumpToLine} onJumpToRange={handleJumpToRange} onViewInsights={handleViewInsightsForCall} /> },
      { key: 'stats', label: t('logAnalyzer.tabs.endpointStats'), component: <EndpointStatsTab analysisId={selected.id} onViewInsights={handleViewInsights} /> },
      { key: 'insights', label: t('logAnalyzer.tabs.performanceInsights'), component: null },
      { key: 'anomalyDetection', label: t('logAnalyzer.tabs.anomalyDetection'), component: <AnomalyDetectionTab analysisId={selected.id} /> },
      { key: 'rawLog', label: t('logAnalyzer.tabs.rawLog'), component: null },
      ...(lastAnalysisOptions?.threadView !== false ? [{ key: 'threadView', label: t('logAnalyzer.tabs.threadView'), component: <ThreadViewTab analysisId={selected.id} /> }] : []),
    ]
    if (selected.criticalIssueCount > 0) list.push({ key: 'criticalIssues', label: t('logAnalyzer.tabs.criticalIssues'), component: <CriticalIssuesTab analysisId={selected.id} onJumpToLine={handleJumpToLine} /> })
    if (selected.npeAnalysisCount > 0) list.push({ key: 'npeAnalysis', label: t('logAnalyzer.tabs.npeAnalysis'), component: <NpeAnalysisTab analysisId={selected.id} onJumpToLine={handleJumpToLine} /> })
    if (selected.exceptionAnalysisCount > 0) list.push({ key: 'exceptionAnalysis', label: t('logAnalyzer.tabs.exceptionAnalysis'), component: <ExceptionAnalysisTab analysisId={selected.id} onJumpToLine={handleJumpToLine} /> })
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
  const insightsTabIndex = useMemo(() => tabs.findIndex(t => t.key === 'insights'), [tabs])

  const goToTab = useCallback((key: string) => {
    const idx = tabs.findIndex(t => t.key === key)
    if (idx >= 0) setActiveTab(idx)
  }, [tabs])

  // Switch to rawLog tab when jumpToLine is set
  useEffect(() => {
    if (jumpToLine != null && rawLogTabIndex >= 0) {
      setActiveTab(rawLogTabIndex)
    }
  }, [jumpToLine, rawLogTabIndex])

  // Switch to rawLog tab and scroll to range start when jumpToRange is set
  useEffect(() => {
    if (jumpToRange != null && rawLogTabIndex >= 0) {
      setActiveTab(rawLogTabIndex)
      setJumpToLine(jumpToRange.from)
    }
  }, [jumpToRange, rawLogTabIndex])

  // Switch to insights tab when endpoint is selected from stats
  useEffect(() => {
    if ((insightsEndpoint != null || insightsTimestamp != null) && insightsTabIndex >= 0) {
      setActiveTab(insightsTabIndex)
    }
  }, [insightsEndpoint, insightsTimestamp, insightsTabIndex])

  return (
    <Box sx={{ maxWidth: 1600, mx: 'auto', p: 3 }}>
      <Typography variant="h4" fontWeight={700} mb={3}>
        {t('logAnalyzer.title')}
      </Typography>

      {activeAnalyses.length > 0 && (
        <Alert severity="info" variant="outlined" sx={{ mb: 3 }}>
          <AlertTitle>{t('logAnalyzer.broadcast.alertTitle')}</AlertTitle>
          {activeAnalyses.map((filenames, i) => (
            <Stack key={i} direction="row" alignItems="center" spacing={1}>
              <LinearProgress sx={{ width: 80 }} />
              <Typography variant="body2">{filenames}</Typography>
            </Stack>
          ))}
        </Alert>
      )}

      {/* ---- Upload Panel ---- */}
      <Paper sx={{ p: 3, mb: 3 }}>
        <Stack direction={{ xs: 'column', md: 'row' }} spacing={2} alignItems="flex-start">
          <Button
            variant="contained"
            component="label"
            startIcon={<CloudUpload />}
            disabled={sse.isRunning}
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
            value={presets.find(p => p.name.toUpperCase() === selectedPreset.toUpperCase()) ?? presets[0] ?? null}
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
        <Dialog open={sse.isRunning || (sse.events.length > 0 && !sse.isDone)} maxWidth="sm" fullWidth
          onClose={(_e, reason) => { if (reason !== 'backdropClick' || !sse.isRunning) { sse.reset() } }}>
          <DialogTitle>{t('logAnalyzer.upload.analyzing')}</DialogTitle>
          <DialogContent>
            <OperationProgress events={sse.events} steps={LOG_ANALYSIS_STEPS} />
          </DialogContent>
          <DialogActions>
            {sse.isRunning ? (
              <Button color="error" startIcon={<Cancel />} onClick={handleCancelAnalysis}>
                {t('logAnalyzer.upload.cancel')}
              </Button>
            ) : (
              <Button variant="contained" onClick={() => sse.reset()}>
                {t('logAnalyzer.upload.close')}
              </Button>
            )}
          </DialogActions>
        </Dialog>

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
              <Chip size="small" variant="outlined"
                label={`${analyses.length}/${maxFiles}`}
                color={analyses.length >= maxFiles ? 'warning' : 'default'}
                title={analyses.length >= maxFiles ? t('logAnalyzer.upload.capacityFull') : ''} />
              {composeIds.size >= 2 && (
                <Button size="small" startIcon={<MergeType />} onClick={handleCompose}>
                  {t('logAnalyzer.compose.button')} ({composeIds.size})
                </Button>
              )}
            </Stack>
            {analyses.map((a) => {
              const vc = viewerCounts[a.id] ?? 0
              const otherViewers = selectedId === a.id ? Math.max(0, vc - 1) : vc
              return (
              <Badge key={a.id} badgeContent={otherViewers > 0 ? otherViewers : undefined}
                color="info" overlap="rectangular"
                anchorOrigin={{ vertical: 'top', horizontal: 'right' }}
                sx={{ mr: 1, mb: 1, '& .MuiBadge-badge': { fontSize: '0.65rem', height: 16, minWidth: 16, right: 4, top: 4 } }}>
              <Chip
                label={
                  <Stack direction="row" alignItems="center" spacing={0.5}>
                    <span>{a.sourceFiles.map(f => f.filename).join(', ')} ({a.totalLineCount.toLocaleString()} lines)</span>
                    {otherViewers > 0 && <Visibility sx={{ fontSize: 14, opacity: 0.7 }} />}
                  </Stack>
                }
                onClick={() => setSelectedId(a.id)}
                onDelete={() => handleDeleteClick(a.id, a.sourceFiles.map(f => f.filename).join(', '))}
                variant={selectedId === a.id ? 'filled' : 'outlined'}
                color={selectedId === a.id ? 'primary' : 'default'}
                sx={{ cursor: 'pointer' }}
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
              </Badge>
              )
            })}
          </Box>
        )}
      </Paper>

      {/* ---- Dashboard ---- */}
      {selected && (
        <>
          <Stack direction="row" spacing={2} mb={3} flexWrap="wrap" useFlexGap>
            <SummaryCard label={t('logAnalyzer.dashboard.totalLines')} value={selected.totalLineCount.toLocaleString()} onClick={() => goToTab('rawLog')} />
            <SummaryCard label={t('logAnalyzer.dashboard.apiCalls')} value={selected.apiCallCount.toLocaleString()} onClick={() => goToTab('apiCalls')} />
            <SummaryCard label={t('logAnalyzer.dashboard.threads')} value={selected.threadCount} onClick={() => goToTab('threadView')} />
            <SummaryCard label={t('logAnalyzer.dashboard.endpoints')} value={selected.endpointCount} onClick={() => goToTab('stats')} />
            <SummaryCard label={t('logAnalyzer.dashboard.errors')} value={selected.errorCount} color="error.main" onClick={() => goToTab('rawLog')} />
            {selected.jobExecutionCount > 0 && (
              <SummaryCard label={t('logAnalyzer.dashboard.jobs')} value={selected.jobExecutionCount} onClick={() => goToTab('jobs')} />
            )}
            {selected.repeatedFailureCount > 0 && (
              <SummaryCard label={t('logAnalyzer.dashboard.failures')} value={selected.repeatedFailureCount} color="warning.main" onClick={() => goToTab('failures')} />
            )}
            {selected.criticalIssueCount > 0 && (
              <SummaryCard label={t('logAnalyzer.dashboard.criticalIssues')} value={selected.criticalIssueCount} color="warning.main" onClick={() => goToTab('criticalIssues')} />
            )}
            {selected.npeAnalysisCount > 0 && (
              <SummaryCard label={t('logAnalyzer.dashboard.npeAnalysis')} value={`${selected.npeAnalysisCount} (${selected.npeLocationCount})`} color="error.main" onClick={() => goToTab('npeAnalysis')} />
            )}
            {selected.exceptionAnalysisCount > 0 && (
              <SummaryCard label={t('logAnalyzer.dashboard.exceptionAnalysis')} value={`${selected.exceptionAnalysisCount} (${selected.exceptionTypeCount})`} color="error.main" onClick={() => goToTab('exceptionAnalysis')} />
            )}
            {selected.customFields?.filter(cf => cf.matchCount > 0).map(cf => (
              <SummaryCard key={cf.fieldName} label={cf.fieldName} value={cf.matchCount.toLocaleString()} color="primary.main" onClick={() => goToTab(cf.countOnly ? 'rawLog' : `custom-${cf.fieldName}`)} />
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
            {tabs.map((tab, idx) => (
              <Box key={tab.key} sx={{ p: 2, display: idx === activeTab ? 'block' : 'none' }}>
                {tab.key === 'rawLog'
                  ? <RawLogTab analysisId={selected!.id} levelCounts={selected!.levelCounts} jumpToLine={jumpToLine} onJumpComplete={handleJumpComplete} highlightRange={jumpToRange} onRangeComplete={handleRangeComplete} />
                  : tab.key === 'insights'
                    ? <PerformanceInsightsTab analysisId={selected!.id} initialEndpoint={insightsEndpoint} initialTimestamp={insightsTimestamp} onEndpointConsumed={handleInsightsConsumed} active={idx === activeTab} />
                    : tab.component}
              </Box>
            ))}
          </Paper>
        </>
      )}

      <AnalysisOptionsDialog
        open={optionsDialogOpen}
        onClose={() => { setOptionsDialogOpen(false); setPendingFiles([]) }}
        onStart={handleStartAnalysis}
      />

      <Dialog open={deleteTarget != null} onClose={() => setDeleteTarget(null)} maxWidth="xs" fullWidth>
        <DialogTitle sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
          <DeleteForever color="error" /> {t('logAnalyzer.delete.title')}
        </DialogTitle>
        <DialogContent>
          <Typography variant="body2">
            {t('logAnalyzer.delete.confirm', { filename: deleteTarget?.label ?? '' })}
          </Typography>
          {deleteTarget && (() => {
            const vc = viewerCounts[deleteTarget.id] ?? 0
            const others = selectedId === deleteTarget.id ? Math.max(0, vc - 1) : vc
            return others > 0 ? (
              <Alert severity="warning" sx={{ mt: 2 }} icon={<Visibility />}>
                {t('logAnalyzer.delete.viewerWarning', { count: others })}
              </Alert>
            ) : null
          })()}
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setDeleteTarget(null)}>{t('logAnalyzer.upload.cancel')}</Button>
          <Button variant="contained" color="error" onClick={handleDeleteConfirm}>
            {t('logAnalyzer.delete.remove')}
          </Button>
        </DialogActions>
      </Dialog>

      <Dialog open={capacityConfirmOpen} onClose={handleCapacityCancel} maxWidth="xs" fullWidth>
        <DialogTitle sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
          <Warning color="warning" /> {t('logAnalyzer.capacity.title')}
        </DialogTitle>
        <DialogContent>
          <Typography variant="body2" mb={2}>
            {t('logAnalyzer.capacity.message', { max: maxFiles })}
          </Typography>
          {(() => {
            const oldest = analyses.reduce((o, a) => a.uploadedAt < o.uploadedAt ? a : o, analyses[0])
            return oldest ? (
              <Alert severity="warning" variant="outlined">
                <Typography variant="body2" fontWeight={500}>
                  {oldest.sourceFiles.map(f => f.filename).join(', ')}
                </Typography>
                <Typography variant="caption" color="text.secondary">
                  {oldest.totalLineCount.toLocaleString()} lines
                </Typography>
              </Alert>
            ) : null
          })()}
        </DialogContent>
        <DialogActions>
          <Button onClick={handleCapacityCancel}>{t('logAnalyzer.upload.cancel')}</Button>
          <Button variant="contained" color="warning" onClick={handleCapacityConfirm}>
            {t('logAnalyzer.capacity.proceed')}
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  )
}
