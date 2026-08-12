import { useState, useEffect, useCallback, useMemo, useRef } from 'react'
import {
  Box, Typography, Button, Paper, Tabs, Tab,
  Chip, IconButton, Menu, MenuItem, ListItemIcon, ListItemText,
  Stack, Dialog, DialogTitle, DialogContent, DialogActions,
  Alert, AlertTitle, LinearProgress, Tooltip, Checkbox, CircularProgress,
  alpha, useTheme,
} from '@mui/material'
import {
  CloudUpload, MergeType, Cancel, DeleteForever, Visibility, Warning, Download, Summarize,
  Description, CalendarToday, PersonOutline,
  Article, SyncAlt, Hub, AccountTree, ErrorOutline, BugReport,
  Work, WarningAmber, HelpOutline, Code, Extension,
} from '@mui/icons-material'
import { useTranslation } from 'react-i18next'
import { useLocation } from 'react-router-dom'
import { useNotification } from '../components/NotificationProvider'
import { useAuth } from '../components/AuthProvider'
import { P } from '../utils/permissions'
import { useSseOperation } from '../hooks/useSseOperation'
import OperationProgress, { LOG_ANALYSIS_STEPS, LOG_COMPOSE_STEPS } from '../components/OperationProgress'
import { prepareLogAnalysis, streamLogAnalysis, cancelLogAnalysis, prepareComposeAnalysis, streamComposeAnalysis, cancelComposeAnalysis, subscribeLogAnalysisUpdates, setLogAnalysisViewing } from '../services/sseService'
import { AnalysisOptionsDialog, applyPreset } from '../components/log-analyzer/AnalysisOptionsDialog'
import type { AnalysisConfiguration } from '../components/log-analyzer/AnalysisOptionsDialog'
import { SummaryCard } from '../components/log-analyzer/SummaryCard'
import { ApiCallsTab } from '../components/log-analyzer/ApiCallsTab'
import { EndpointStatsTab } from '../components/log-analyzer/EndpointStatsTab'
import { RawLogTab } from '../components/log-analyzer/RawLogTab'
import { JobsTab } from '../components/log-analyzer/JobsTab'
import { FailuresTab } from '../components/log-analyzer/FailuresTab'
import { OrphanRequestsTab } from '../components/log-analyzer/OrphanRequestsTab'
import { OrphanJobsTab } from '../components/log-analyzer/OrphanJobsTab'
import { CriticalIssuesTab } from '../components/log-analyzer/CriticalIssuesTab'
import { NpeAnalysisTab } from '../components/log-analyzer/NpeAnalysisTab'
import { ExceptionAnalysisTab } from '../components/log-analyzer/ExceptionAnalysisTab'
import { CustomFieldTab } from '../components/log-analyzer/CustomFieldTab'
import { PerformanceInsightsTab } from '../components/log-analyzer/PerformanceInsightsTab'
import { AnomalyDetectionTab } from '../components/log-analyzer/AnomalyDetectionTab'
import { SystemHealthTab } from '../components/log-analyzer/SystemHealthTab'
import { DuplicateRequestsTab } from '../components/log-analyzer/DuplicateRequestsTab'
import type {
  AnalysisSummary, LogPreset, UploadOptions,
} from '../services/logAnalyzerService'
import * as logService from '../services/logAnalyzerService'
import { formatBytes } from '../utils/format'

const ACCEPTED_EXTENSIONS = ['.log', '.txt', '.out']

function formatDateTime(ts: string | null): string {
  if (!ts) return ''
  try {
    const d = new Date(ts)
    return d.toLocaleString(undefined, { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' })
  } catch { return ts }
}

function sameDay(a: string, b: string): boolean {
  try {
    const da = new Date(a), db = new Date(b)
    return da.getFullYear() === db.getFullYear() && da.getMonth() === db.getMonth() && da.getDate() === db.getDate()
  } catch { return false }
}

function formatTimeRange(start: string, end: string): string {
  if (sameDay(start, end)) {
    const d = new Date(start)
    const date = d.toLocaleDateString(undefined, { month: 'short', day: 'numeric' })
    const t1 = d.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' })
    const t2 = new Date(end).toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' })
    return `${date}, ${t1} — ${t2}`
  }
  return `${formatDateTime(start)} — ${formatDateTime(end)}`
}

export default function LogAnalyzerPage() {
  const { t } = useTranslation()
  const { notify } = useNotification()
  const { hasPermission } = useAuth()
  const canAnalyze = hasPermission(P.LOGS_ANALYZE)
  const theme = useTheme()
  const clientTokenRef = useRef(
    typeof globalThis.crypto !== 'undefined' && typeof globalThis.crypto.randomUUID === 'function'
      ? globalThis.crypto.randomUUID()
      : `${Date.now()}-${Math.random().toString(36).slice(2)}`
  )

  const [presets, setPresets] = useState<LogPreset[]>([])
  const [defaultPreset, setDefaultPreset] = useState('WILDFLY')
  const [maxFiles, setMaxFiles] = useState(5)
  const [analyses, setAnalyses] = useState<AnalysisSummary[]>([])
  const location = useLocation()
  const locationState = location.state as { analysisId?: string; openUpload?: boolean; containerId?: string; containerName?: string } | null
  const [selectedId, setSelectedId] = useState<string | null>(
    locationState?.analysisId ?? null
  )
  const sse = useSseOperation()
  const [analysisTicket, setAnalysisTicket] = useState<string | null>(null)
  const [composeTicket, setComposeTicket] = useState<string | null>(null)
  const [activeTab, setActiveTab] = useState(0)
  const [jumpToLine, setJumpToLine] = useState<number | null>(null)
  const [jumpToRange, setJumpToRange] = useState<{ from: number; to: number } | null>(null)
  const [initialLevel, setInitialLevel] = useState<string | null>(null)
  const [insightsEndpoint, setInsightsEndpoint] = useState<string | null>(null)
  const [insightsTimestamp, setInsightsTimestamp] = useState<string | null>(null)
  const [apiCallsTimeFrom, setApiCallsTimeFrom] = useState<string | null>(null)
  const [apiCallsTimeTo, setApiCallsTimeTo] = useState<string | null>(null)

  // Upload form
  const [selectedPreset, setSelectedPreset] = useState('')
  const [slowThreshold, setSlowThreshold] = useState(1000)
  const [customRegex, setCustomRegex] = useState<Partial<UploadOptions>>({})

  // Custom fields
  const [customFieldInputs, setCustomFieldInputs] = useState<Array<{ name: string; regex: string; countOnly: boolean }>>([])

  // Analysis options dialog
  const [optionsDialogOpen, setOptionsDialogOpen] = useState(false)
  const [pendingFiles, setPendingFiles] = useState<File[]>([])
  const [lastAnalysisOptions, setLastAnalysisOptions] = useState<AnalysisConfiguration | null>(null)
  const [pendingContainer, setPendingContainer] = useState<{ id: string; name: string } | null>(null)

  // Upload progress (0-100, -1 = not uploading)
  const [uploadProgress, setUploadProgress] = useState(-1)

  // Active analyses from other users (broadcast)
  const [activeAnalyses, setActiveAnalyses] = useState<string[]>([])

  // Delete confirmation
  const [deleteTarget, setDeleteTarget] = useState<{ id: string; label: string } | null>(null)
  const [reportMenuAnchor, setReportMenuAnchor] = useState<HTMLElement | null>(null)

  // Viewer counts from broadcast (analysisId -> number of viewers)
  const [viewerCounts, setViewerCounts] = useState<Record<string, number>>({})

  // Capacity confirmation
  const [capacityConfirmOpen, setCapacityConfirmOpen] = useState(false)
  const [pendingAnalysisOptions, setPendingAnalysisOptions] = useState<AnalysisConfiguration | null>(null)

  // Compose
  const [composeIds, setComposeIds] = useState<Set<string>>(new Set())

  // Drag and drop
  const [isDragging, setIsDragging] = useState(false)
  const dragCounter = useRef(0)

  useEffect(() => {
    logService.getStatus().then((s) => {
      setPresets(s.presets)
      setDefaultPreset(s.defaultPreset)
      setSelectedPreset(s.defaultPreset)
      if (s.maxFiles) setMaxFiles(s.maxFiles)
      const defaultP = s.presets.find(p => p.name.toUpperCase() === s.defaultPreset.toUpperCase())
      if (defaultP) {
        const { customRegex: cr, customFieldInputs: cfi } = applyPreset(defaultP)
        setCustomRegex(cr)
        setCustomFieldInputs(cfi)
      }
    }).catch(() => {})
    logService.listAnalyses().then(setAnalyses).catch(() => {})
  }, [])

  // Auto-open upload dialog when navigated with openUpload flag or container analysis
  useEffect(() => {
    if (locationState?.openUpload) {
      if (locationState.containerId) {
        setPendingContainer({ id: locationState.containerId, name: locationState.containerName ?? locationState.containerId })
      }
      setOptionsDialogOpen(true)
      window.history.replaceState({}, '')
    }
  }, []) // eslint-disable-line react-hooks/exhaustive-deps

  // Reset tab index when selectedId changes
  useEffect(() => {
    setActiveTab(0)
  }, [selectedId])

  // Notify server which analysis this client is viewing + heartbeat every 60s
  useEffect(() => {
    setLogAnalysisViewing(clientTokenRef.current, selectedId)
    const interval = selectedId
      ? setInterval(() => setLogAnalysisViewing(clientTokenRef.current, selectedId), 60_000)
      : undefined
    return () => {
      clearInterval(interval)
      setLogAnalysisViewing(clientTokenRef.current, null)
    }
  }, [selectedId])

  // Clear viewer entry on page unload (reload/close) via sendBeacon
  useEffect(() => {
    const token = clientTokenRef.current
    const handleUnload = () => {
      navigator.sendBeacon(
        '/api/logs/analyzer/sse/viewing',
        new Blob([JSON.stringify({ clientToken: token, analysisId: null })], { type: 'application/json' }),
      )
    }
    window.addEventListener('beforeunload', handleUnload)
    return () => window.removeEventListener('beforeunload', handleUnload)
  }, [])

  const refreshList = useCallback(() => {
    logService.listAnalyses().then(setAnalyses).catch(() => {})
  }, [])

  // Subscribe to log analysis broadcasts from other users
  const selectedIdRef = useRef(selectedId)
  selectedIdRef.current = selectedId

  useEffect(() => {
    return subscribeLogAnalysisUpdates(
      (ev) => setActiveAnalyses(prev => [...prev, ev.filenames]),
      (ev) => {
        setActiveAnalyses(prev => prev.filter(f => f !== ev.filenames))
        refreshList()
      },
      (ev) => {
        if (ev.analysisId && selectedIdRef.current === ev.analysisId) setSelectedId(null)
        refreshList()
      },
      (counts) => setViewerCounts(counts),
    )
  }, [refreshList])

  const selected = useMemo(
    () => analyses.find((a) => a.id === selectedId) ?? null,
    [analyses, selectedId],
  )

  const handleUpload = useCallback((fileList: FileList | null) => {
    if (!fileList || fileList.length === 0) return
    setPendingFiles([fileList[0]])
    setOptionsDialogOpen(true)
  }, [])

  // Drag and drop handlers
  const handleDragEnter = useCallback((e: React.DragEvent) => {
    e.preventDefault()
    e.stopPropagation()
    dragCounter.current++
    if (e.dataTransfer.types.includes('Files')) {
      setIsDragging(true)
    }
  }, [])

  const handleDragLeave = useCallback((e: React.DragEvent) => {
    e.preventDefault()
    e.stopPropagation()
    dragCounter.current--
    if (dragCounter.current === 0) {
      setIsDragging(false)
    }
  }, [])

  const handleDragOver = useCallback((e: React.DragEvent) => {
    e.preventDefault()
    e.stopPropagation()
  }, [])

  const handleDrop = useCallback((e: React.DragEvent) => {
    e.preventDefault()
    e.stopPropagation()
    dragCounter.current = 0
    setIsDragging(false)
    const dt = new DataTransfer()
    for (const f of Array.from(e.dataTransfer.files)) {
      if (ACCEPTED_EXTENSIONS.some(ext => f.name.toLowerCase().endsWith(ext))) dt.items.add(f)
    }
    if (dt.files.length > 0) {
      handleUpload(dt.files)
    }
  }, [handleUpload])

  const doStartAnalysis = useCallback(async (config: AnalysisConfiguration) => {
    setLastAnalysisOptions(config)

    // Container log analysis (from Deep Analysis button)
    if (pendingContainer) {
      try {
        const result = await logService.analyzeContainerLogs(pendingContainer.id, {
          containerName: pendingContainer.name,
          preset: config.selectedPreset,
          slowThresholdMs: config.slowThreshold,
        })
        setPendingContainer(null)
        setSelectedId(result.id)
        refreshList()
        notify(t('logAnalyzer.upload.success'), 'success')
      } catch (err) {
        notify(err instanceof Error ? err.message : t('logAnalyzer.upload.error'), 'error')
      }
      return
    }

    if (pendingFiles.length === 0) return

    // Update page-level state from dialog config
    setSelectedPreset(config.selectedPreset)
    setSlowThreshold(config.slowThreshold)
    setCustomRegex(config.customRegex)
    setCustomFieldInputs(config.customFieldInputs)

    const currentPreset = presets.find(p => p.name.toUpperCase() === config.selectedPreset.toUpperCase())
    const regexKeys = ['logLineRegex', 'apiCallRegex', 'timestampFormat', 'jobStartRegex', 'jobEndRegex', 'failureRegex', 'sensitiveFieldNames', 'criticalIssueExclusions', 'upstreamDurationField'] as const
    const formFields: Record<string, string | undefined> = {
      label: config.label || undefined,
      preset: config.selectedPreset,
      slowThresholdMs: String(config.slowThreshold),
      options: JSON.stringify(config.analysisOptions),
    }
    for (const key of regexKeys) {
      const val = config.customRegex[key]
      if (val != null) formFields[key] = String(val)
    }
    if (currentPreset && config.customRegex.logLineRegex && config.customRegex.logLineRegex !== currentPreset.logLineRegex) {
      formFields.logLineRegex = config.customRegex.logLineRegex
    }
    if (config.customFieldInputs.length > 0) {
      const validFields = config.customFieldInputs.filter(cf => cf.name.trim() && cf.regex.trim())
      if (validFields.length > 0) {
        formFields.customFields = JSON.stringify(validFields)
      }
    }

    try {
      setUploadProgress(0)
      const ticket = await prepareLogAnalysis(pendingFiles, formFields, (pct) => setUploadProgress(pct))
      setUploadProgress(-1)
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
          setCancelling(false)
          setAnalysisTicket(null)
          sse.reset()
        },
        () => {
          setPendingFiles([])
          setCancelling(false)
          setAnalysisTicket(null)
        },
      )
    } catch (err) {
      setUploadProgress(-1)
      notify(err instanceof Error ? err.message : t('logAnalyzer.upload.error'), 'error')
      setPendingFiles([])
    }
  }, [pendingContainer, pendingFiles, presets, refreshList, notify, t, sse])

  const handleStartAnalysis = useCallback(async (config: AnalysisConfiguration) => {
    setOptionsDialogOpen(false)
    if (analyses.length >= maxFiles) {
      setPendingAnalysisOptions(config)
      setCapacityConfirmOpen(true)
    } else {
      doStartAnalysis(config)
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

  const [cancelling, setCancelling] = useState(false)

  const handleCancelAnalysis = useCallback(() => {
    if (analysisTicket) {
      setCancelling(true)
      cancelLogAnalysis(analysisTicket)
    } else if (composeTicket) {
      setCancelling(true)
      cancelComposeAnalysis(composeTicket)
    }
  }, [analysisTicket, composeTicket])

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
      const ticket = await prepareComposeAnalysis({
        ids: Array.from(composeIds),
        preset: selectedPreset,
        slowThresholdMs: slowThreshold,
      })
      setComposeTicket(ticket)
      sse.start(
        (onEvent, onDone, onError) => streamComposeAnalysis(ticket, onEvent, onDone, onError),
        (event) => {
          if (event.detail) {
            setSelectedId(event.detail)
            refreshList()
          }
          notify(t('logAnalyzer.compose.success'), 'success')
          setComposeIds(new Set())
          setCancelling(false)
          setComposeTicket(null)
          sse.reset()
        },
        () => {
          setCancelling(false)
          setComposeTicket(null)
        },
      )
    } catch (err) {
      notify(err instanceof Error ? err.message : t('logAnalyzer.compose.error'), 'error')
    }
  }, [composeIds, selectedPreset, slowThreshold, refreshList, notify, t, sse])

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

  const handleGoToApiCalls = useCallback((timeFrom: string, timeTo: string) => {
    setApiCallsTimeFrom(timeFrom)
    setApiCallsTimeTo(timeTo)
  }, [])

  const handleApiCallsTimeConsumed = useCallback(() => {
    setApiCallsTimeFrom(null)
    setApiCallsTimeTo(null)
  }, [])

  const tabs = useMemo(() => {
    if (!selected) return []
    const list = [
      { key: 'apiCalls', label: t('logAnalyzer.tabs.apiCalls'), component: null },
      { key: 'stats', label: t('logAnalyzer.tabs.endpointStats'), component: <EndpointStatsTab analysisId={selected.id} onViewInsights={handleViewInsights} hasConnectionDelay={selected.hasConnectionDelay} /> },
      { key: 'insights', label: t('logAnalyzer.tabs.performanceInsights'), component: null },
      { key: 'anomalyDetection', label: t('logAnalyzer.tabs.anomalyDetection'), component: <AnomalyDetectionTab analysisId={selected.id} /> },
      { key: 'systemHealth', label: t('logAnalyzer.tabs.systemHealth'), component: <SystemHealthTab analysisId={selected.id} /> },
      { key: 'rawLog', label: t('logAnalyzer.tabs.rawLog'), component: null },
    ]
    if (selected.apiCallCount > 0) list.splice(list.length - 1, 0, { key: 'duplicateRequests', label: t('logAnalyzer.tabs.duplicateRequests'), component: <DuplicateRequestsTab analysisId={selected.id} onJumpToLine={handleJumpToLine} /> })
    if (selected.criticalIssueCount > 0) list.push({ key: 'criticalIssues', label: t('logAnalyzer.tabs.criticalIssues'), component: <CriticalIssuesTab analysisId={selected.id} onJumpToLine={handleJumpToLine} /> })
    if (selected.npeAnalysisCount > 0) list.push({ key: 'npeAnalysis', label: t('logAnalyzer.tabs.npeAnalysis'), component: <NpeAnalysisTab analysisId={selected.id} onJumpToLine={handleJumpToLine} /> })
    if (selected.exceptionAnalysisCount > 0) list.push({ key: 'exceptionAnalysis', label: t('logAnalyzer.tabs.exceptionAnalysis'), component: <ExceptionAnalysisTab analysisId={selected.id} onJumpToLine={handleJumpToLine} /> })
    if (selected.jobExecutionCount > 0) list.push({ key: 'jobs', label: t('logAnalyzer.tabs.jobs'), component: <JobsTab analysisId={selected.id} onJumpToLine={handleJumpToLine} /> })
    if (selected.repeatedFailureCount > 0) list.push({ key: 'failures', label: t('logAnalyzer.tabs.failures'), component: <FailuresTab analysisId={selected.id} onJumpToLine={handleJumpToLine} /> })
    if (selected.orphanRequestCount > 0) list.push({ key: 'orphanRequests', label: t('logAnalyzer.tabs.orphanRequests'), component: <OrphanRequestsTab analysisId={selected.id} sensitiveFields={presetObj?.sensitiveFieldNames ?? []} onJumpToLine={handleJumpToLine} /> })
    if (selected.orphanJobCount > 0) list.push({ key: 'orphanJobs', label: t('logAnalyzer.tabs.orphanJobs'), component: <OrphanJobsTab analysisId={selected.id} onJumpToLine={handleJumpToLine} /> })
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

  // Switch to apiCalls tab when time range is set from Performance Insights
  const apiCallsTabIndex = useMemo(() => tabs.findIndex(t => t.key === 'apiCalls'), [tabs])
  useEffect(() => {
    if (apiCallsTimeFrom != null && apiCallsTimeTo != null && apiCallsTabIndex >= 0) {
      setActiveTab(apiCallsTabIndex)
    }
  }, [apiCallsTimeFrom, apiCallsTimeTo, apiCallsTabIndex])

  const hasAnalyses = analyses.length > 0

  return (
    <Box sx={{ maxWidth: 1600, mx: 'auto', p: 3 }}>

      {/* ================================================================
          BROADCAST ALERT
          ================================================================ */}
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

      {/* ================================================================
          UPLOAD ZONE — simple drag & drop target (requires LOGS_ANALYZE)
          ================================================================ */}
      {canAnalyze && (
      <Paper
        elevation={0}
        onDragEnter={handleDragEnter}
        onDragLeave={handleDragLeave}
        onDragOver={handleDragOver}
        onDrop={handleDrop}
        sx={{
          mb: 3, position: 'relative', overflow: 'hidden',
          border: '2px dashed',
          borderColor: isDragging ? 'primary.main' : 'divider',
          bgcolor: isDragging ? alpha(theme.palette.primary.main, 0.04) : 'transparent',
          transition: 'all 0.25s ease',
          ...(hasAnalyses ? { p: 2.5 } : { p: 5, textAlign: 'center' }),
        }}
      >
        {/* Drag overlay */}
        {isDragging && (
          <Box sx={{
            position: 'absolute', inset: 0, zIndex: 10,
            display: 'flex', alignItems: 'center', justifyContent: 'center',
            bgcolor: alpha(theme.palette.primary.main, 0.08),
            borderRadius: 'inherit',
          }}>
            <Stack alignItems="center" spacing={1}>
              <CloudUpload sx={{ fontSize: 48, color: 'primary.main' }} />
              <Typography variant="h6" color="primary.main" fontWeight={600}>
                {t('logAnalyzer.upload.dropzoneActive')}
              </Typography>
            </Stack>
          </Box>
        )}

        {/* Empty state */}
        {!hasAnalyses && (
          <Stack alignItems="center" spacing={2} sx={{ opacity: isDragging ? 0.15 : 1, transition: 'opacity 0.2s' }}>
            <CloudUpload sx={{ fontSize: 56, color: 'text.disabled' }} />
            <Typography variant="h5" fontWeight={600} color="text.secondary">
              {t('logAnalyzer.title')}
            </Typography>
            <Typography variant="body2" color="text.secondary">
              {t('logAnalyzer.upload.dropzone')}
            </Typography>
            <Button variant="contained" component="label" startIcon={<CloudUpload />} disabled={sse.isRunning} size="large">
              {t('logAnalyzer.upload.selectFiles')}
              <input type="file" hidden accept=".log,.txt,.out" onChange={(e) => { handleUpload(e.target.files); e.target.value = '' }} />
            </Button>
            <Typography variant="caption" color="text.disabled">
              {t('logAnalyzer.upload.supported')}
            </Typography>
          </Stack>
        )}

        {/* Compact mode */}
        {hasAnalyses && (
          <Stack direction="row" spacing={2} alignItems="center"
            sx={{ opacity: isDragging ? 0.15 : 1, transition: 'opacity 0.2s' }}
          >
            <Button variant="contained" component="label" startIcon={<CloudUpload />} disabled={sse.isRunning} sx={{ whiteSpace: 'nowrap' }}>
              {t('logAnalyzer.upload.selectFiles')}
              <input type="file" hidden accept=".log,.txt,.out" onChange={(e) => { handleUpload(e.target.files); e.target.value = '' }} />
            </Button>
            <Typography variant="caption" color="text.disabled">
              {t('logAnalyzer.upload.dropzoneHint')}
            </Typography>
          </Stack>
        )}
      </Paper>
      )}

      {/* Upload progress dialog */}
      <Dialog open={uploadProgress >= 0} maxWidth="xs" fullWidth>
        <DialogTitle>{t('logAnalyzer.upload.uploading')}</DialogTitle>
        <DialogContent>
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 2, py: 1 }}>
            <Box sx={{ position: 'relative', display: 'inline-flex' }}>
              <CircularProgress
                variant="determinate"
                value={uploadProgress}
                size={48}
                thickness={4}
                sx={{ color: 'primary.main', '& .MuiCircularProgress-circle': { strokeLinecap: 'round', transition: 'stroke-dashoffset 0.3s ease' } }}
              />
              <Box sx={{ position: 'absolute', inset: 0, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
                <Typography sx={{ fontSize: '0.75rem', fontWeight: 700, color: 'primary.main' }}>{uploadProgress}%</Typography>
              </Box>
            </Box>
            <Box sx={{ flex: 1 }}>
              <Typography variant="body2" color="text.secondary">
                {pendingFiles.map(f => f.name).join(', ')}
              </Typography>
              <LinearProgress
                variant="determinate"
                value={uploadProgress}
                sx={{ mt: 1, borderRadius: 2, height: 4, '& .MuiLinearProgress-bar': { borderRadius: 2, transition: 'transform 0.3s ease' } }}
              />
            </Box>
          </Box>
        </DialogContent>
      </Dialog>

      {/* SSE progress dialog */}
      <Dialog open={sse.isRunning || (sse.events.length > 0 && !sse.isDone)} maxWidth="sm" fullWidth
        onClose={(_e, reason) => { if (reason !== 'backdropClick' || !sse.isRunning) { sse.reset(); setCancelling(false) } }}>
        <DialogTitle>
          {sse.hasError
            ? (sse.events.some(e => e.type === 'ERROR' && e.step === 'Cancelled') ? t('logAnalyzer.upload.cancelled') : t('logAnalyzer.upload.failed'))
            : composeTicket ? t('logAnalyzer.compose.composing') : t('logAnalyzer.upload.analyzing')}
        </DialogTitle>
        <DialogContent>
          {sse.hasError && (
            <Alert severity={sse.events.some(e => e.type === 'ERROR' && e.step === 'Cancelled') ? 'warning' : 'error'} sx={{ mb: 2 }}>
              {sse.events.filter(e => e.type === 'ERROR').pop()?.message ?? t('logAnalyzer.upload.error')}
            </Alert>
          )}
          <OperationProgress events={sse.events} steps={composeTicket ? LOG_COMPOSE_STEPS : LOG_ANALYSIS_STEPS} />
        </DialogContent>
        <DialogActions>
          {sse.isRunning ? (
            <Button color="error" disabled={cancelling}
              startIcon={cancelling ? <CircularProgress size={18} color="inherit" /> : <Cancel />}
              onClick={handleCancelAnalysis}>
              {cancelling ? t('common.cancelling') : t('logAnalyzer.upload.cancel')}
            </Button>
          ) : (
            <Button variant="contained" onClick={() => { sse.reset(); setCancelling(false) }}>
              {t('logAnalyzer.upload.close')}
            </Button>
          )}
        </DialogActions>
      </Dialog>

      {/* ================================================================
          ANALYSIS SELECTOR — card-based file browser
          ================================================================ */}
      {hasAnalyses && (
        <Box sx={{ mb: 3 }}>
          <Stack direction="row" alignItems="center" spacing={1.5} mb={1.5}>
            <Typography variant="subtitle2" fontWeight={600} color="text.secondary" sx={{ textTransform: 'uppercase', fontSize: '0.7rem', letterSpacing: '0.08em' }}>
              {t('logAnalyzer.upload.analyses')}
            </Typography>
            <Chip size="small" variant="outlined"
              label={`${analyses.length} / ${maxFiles}`}
              color={analyses.length >= maxFiles ? 'warning' : 'default'}
              sx={{ fontWeight: 600, fontSize: '0.7rem' }}
              title={analyses.length >= maxFiles ? t('logAnalyzer.upload.capacityFull') : ''} />
            {composeIds.size >= 2 && (
              <Button size="small" variant="outlined" startIcon={<MergeType />} onClick={handleCompose}>
                {t('logAnalyzer.compose.button')} ({composeIds.size})
              </Button>
            )}
          </Stack>

          <Box sx={{
            display: 'flex', gap: 1.5, overflowX: 'auto', pb: 1,
            '&::-webkit-scrollbar': { height: 4 },
            '&::-webkit-scrollbar-thumb': { bgcolor: 'divider', borderRadius: 2 },
          }}>
            {analyses.map((a) => {
              const isSelected = selectedId === a.id
              const vc = viewerCounts[a.id] ?? 0
              const otherViewers = isSelected ? Math.max(0, vc - 1) : vc
              const totalSize = a.sourceFiles.reduce((sum, f) => sum + f.size, 0)
              const filenames = a.sourceFiles.map(f => f.filename).join(', ')
              const isComposing = composeIds.has(a.id)
              const uploadDate = a.uploadedAt ? new Date(a.uploadedAt).toLocaleString() : ''
              const uploader = a.uploadedBy || ''

              return (
                <Tooltip key={a.id} title={[
                  a.label,
                  uploadDate ? `${t('logAnalyzer.upload.uploadedAt')}: ${uploadDate}` : '',
                  uploader ? `${t('logAnalyzer.upload.uploadedBy')}: ${uploader}` : '',
                ].filter(Boolean).join('\n')} arrow placement="top" enterDelay={400}>
                <Paper
                  elevation={0}
                  onClick={() => setSelectedId(a.id)}
                  sx={{
                    minWidth: 260, maxWidth: 340, p: 2, cursor: 'pointer',
                    flex: '0 0 auto', position: 'relative',
                    border: '2px solid',
                    borderColor: isSelected ? 'primary.main' : 'divider',
                    bgcolor: isSelected ? alpha(theme.palette.primary.main, 0.04) : 'transparent',
                    transition: 'all 0.2s ease',
                    '&:hover': {
                      borderColor: isSelected ? 'primary.main' : 'primary.light',
                      bgcolor: isSelected ? alpha(theme.palette.primary.main, 0.06) : alpha(theme.palette.primary.main, 0.02),
                      '& .analysis-actions': { opacity: 1 },
                    },
                  }}
                >
                  {/* Header row: checkbox + filename + actions */}
                  <Stack direction="row" alignItems="flex-start" spacing={1}>
                    <Checkbox
                      size="small"
                      checked={isComposing}
                      onClick={(e) => e.stopPropagation()}
                      onChange={(e) => {
                        e.stopPropagation()
                        setComposeIds(prev => {
                          const next = new Set(prev)
                          if (next.has(a.id)) next.delete(a.id)
                          else next.add(a.id)
                          return next
                        })
                      }}
                      sx={{ p: 0, mt: 0.1 }}
                    />
                    <Box sx={{ flex: 1, minWidth: 0 }}>
                      <Stack direction="row" alignItems="center" spacing={0.5}>
                        <Description sx={{ fontSize: 16, color: isSelected ? 'primary.main' : 'text.secondary', flexShrink: 0 }} />
                        <Typography variant="subtitle2" noWrap fontWeight={600} title={filenames}
                          sx={{ color: isSelected ? 'primary.main' : 'text.primary' }}>
                          {filenames}
                        </Typography>
                      </Stack>
                    </Box>
                    <Stack direction="row" alignItems="center" spacing={0.5} className="analysis-actions"
                      sx={{ opacity: isSelected ? 1 : 0, transition: 'opacity 0.15s' }}>
                      {otherViewers > 0 && (
                        <Tooltip title={t('logAnalyzer.upload.viewers', { count: otherViewers })}>
                          <Chip size="small" icon={<Visibility sx={{ fontSize: 14 }} />} label={otherViewers}
                            sx={{ height: 22, '& .MuiChip-label': { px: 0.5, fontSize: '0.7rem' } }} />
                        </Tooltip>
                      )}
                      {canAnalyze && (
                        <IconButton size="small" onClick={(e) => { e.stopPropagation(); handleDeleteClick(a.id, filenames) }}
                          sx={{ p: 0.3 }}>
                          <DeleteForever sx={{ fontSize: 18 }} color="error" />
                        </IconButton>
                      )}
                    </Stack>
                  </Stack>

                  {/* Metadata */}
                  <Stack direction="row" spacing={2} sx={{ mt: 1, ml: 3.5 }} flexWrap="wrap" useFlexGap>
                    <Stack direction="row" alignItems="center" spacing={0.4}>
                      <Article sx={{ fontSize: 13, color: 'text.disabled' }} />
                      <Typography variant="caption" color="text.secondary">
                        {a.totalLineCount.toLocaleString()} {t('logAnalyzer.common.lines')}
                      </Typography>
                    </Stack>
                    {totalSize > 0 && (
                      <Typography variant="caption" color="text.disabled">
                        {formatBytes(totalSize)}
                      </Typography>
                    )}
                  </Stack>
                  {a.timeRangeStart && a.timeRangeEnd && (
                    <Stack direction="row" alignItems="center" spacing={0.4} sx={{ mt: 0.5, ml: 3.5 }}>
                      <CalendarToday sx={{ fontSize: 13, color: 'text.disabled' }} />
                      <Typography variant="caption" color="text.disabled">
                        {formatTimeRange(a.timeRangeStart, a.timeRangeEnd)}
                      </Typography>
                    </Stack>
                  )}
                  {uploader && (
                    <Stack direction="row" alignItems="center" spacing={0.4} sx={{ mt: 0.5, ml: 3.5 }}>
                      <PersonOutline sx={{ fontSize: 13, color: 'text.disabled' }} />
                      <Typography variant="caption" color="text.disabled" noWrap title={uploader}>
                        {uploader}
                      </Typography>
                    </Stack>
                  )}
                </Paper>
                </Tooltip>
              )
            })}
          </Box>
        </Box>
      )}

      {/* ================================================================
          DASHBOARD — grouped summary cards + level counts
          ================================================================ */}
      {selected && (
        <>
          <Stack direction="row" justifyContent="flex-end" mb={1}>
            <Button size="small" startIcon={<Summarize />} onClick={(e) => setReportMenuAnchor(e.currentTarget)}>
              {t('logAnalyzer.report.button')}
            </Button>
            <Menu anchorEl={reportMenuAnchor} open={!!reportMenuAnchor} onClose={() => setReportMenuAnchor(null)}>
              <MenuItem onClick={() => { setReportMenuAnchor(null); window.open(logService.getReportUrl(selected.id, 'compact')) }}>
                <ListItemIcon><Download fontSize="small" /></ListItemIcon>
                <ListItemText>{t('logAnalyzer.report.compact')}</ListItemText>
              </MenuItem>
              <MenuItem onClick={() => { setReportMenuAnchor(null); window.open(logService.getReportUrl(selected.id, 'complete')) }}>
                <ListItemIcon><Download fontSize="small" /></ListItemIcon>
                <ListItemText>{t('logAnalyzer.report.complete')}</ListItemText>
              </MenuItem>
            </Menu>
          </Stack>
          <Box sx={{
            display: 'grid',
            gridTemplateColumns: 'repeat(auto-fill, minmax(155px, 1fr))',
            gap: 1.5, mb: 2,
          }}>
            <SummaryCard icon={<Article />} label={t('logAnalyzer.dashboard.totalLines')} value={selected.totalLineCount.toLocaleString()} onClick={() => goToTab('rawLog')} />
            <SummaryCard icon={<SyncAlt />} label={t('logAnalyzer.dashboard.apiCalls')} value={selected.apiCallCount.toLocaleString()} onClick={() => goToTab('apiCalls')} />
            <SummaryCard icon={<Hub />} label={t('logAnalyzer.dashboard.endpoints')} value={selected.endpointCount} onClick={() => goToTab('stats')} />
            <SummaryCard icon={<AccountTree />} label={t('logAnalyzer.dashboard.threads')} value={selected.threadCount} onClick={() => goToTab('rawLog')} />
            <SummaryCard icon={<ErrorOutline />} label={t('logAnalyzer.dashboard.errors')} value={selected.errorCount} color="error.main" onClick={() => goToTab('rawLog')} />
            {selected.orphanRequestCount > 0 && (
              <SummaryCard icon={<HelpOutline />} label={t('logAnalyzer.dashboard.orphanRequests')} value={selected.orphanRequestCount} color="warning.main" onClick={() => goToTab('orphanRequests')} />
            )}
            {selected.orphanJobCount > 0 && (
              <SummaryCard icon={<HelpOutline />} label={t('logAnalyzer.dashboard.orphanJobs')} value={selected.orphanJobCount} color="warning.main" onClick={() => goToTab('orphanJobs')} />
            )}
            {selected.criticalIssueCount > 0 && (
              <SummaryCard icon={<WarningAmber />} label={t('logAnalyzer.dashboard.criticalIssues')} value={selected.criticalIssueCount} color="warning.main" onClick={() => goToTab('criticalIssues')} />
            )}
            {selected.npeAnalysisCount > 0 && (
              <SummaryCard icon={<BugReport />} label={t('logAnalyzer.dashboard.npeAnalysis')} value={`${selected.npeAnalysisCount} (${selected.npeLocationCount})`} color="error.main" onClick={() => goToTab('npeAnalysis')} />
            )}
            {selected.exceptionAnalysisCount > 0 && (
              <SummaryCard icon={<Code />} label={t('logAnalyzer.dashboard.exceptionAnalysis')} value={`${selected.exceptionAnalysisCount} (${selected.exceptionTypeCount})`} color="error.main" onClick={() => goToTab('exceptionAnalysis')} />
            )}
            {selected.jobExecutionCount > 0 && (
              <SummaryCard icon={<Work />} label={t('logAnalyzer.dashboard.jobs')} value={selected.jobExecutionCount} onClick={() => goToTab('jobs')} />
            )}
            {selected.repeatedFailureCount > 0 && (
              <SummaryCard icon={<ErrorOutline />} label={t('logAnalyzer.dashboard.failures')} value={selected.repeatedFailureCount} color="warning.main" onClick={() => goToTab('failures')} />
            )}
            {selected.customFields?.filter(cf => cf.matchCount > 0).map(cf => (
              <SummaryCard key={cf.fieldName} icon={<Extension />} label={cf.fieldName} value={cf.matchCount.toLocaleString()} color="primary.main" onClick={() => goToTab(cf.countOnly ? 'rawLog' : `custom-${cf.fieldName}`)} />
            ))}
          </Box>

          {/* Level counts — compact inline row */}
          {Object.keys(selected.levelCounts).length > 0 && (() => {
            const raw = selected.levelCounts
            const merged: Record<string, number> = {}
            for (const [level, count] of Object.entries(raw)) {
              if (level === 'WARNING') merged['WARN'] = (merged['WARN'] ?? 0) + count
              else merged[level] = (merged[level] ?? 0) + count
            }
            return (
            <Stack direction="row" spacing={0.75} mb={3} flexWrap="wrap" useFlexGap>
              {Object.entries(merged).map(([level, count]) => (
                <Chip key={level} label={`${level}: ${count.toLocaleString()}`} size="small" variant="outlined"
                  color={level === 'ERROR' || level === 'FATAL' || level === 'SEVERE' ? 'error' : level === 'WARN' ? 'warning' : 'default'}
                  onClick={() => { setInitialLevel(level); goToTab('rawLog') }}
                  sx={{ fontWeight: 500, fontSize: '0.72rem', cursor: 'pointer' }} />
              ))}
            </Stack>
            )
          })()}

          {/* ================================================================
              TABS — analysis content
              ================================================================ */}
          <Paper elevation={0} sx={{ border: '1px solid', borderColor: 'divider' }}>
            <Tabs value={activeTab} onChange={(_, v) => setActiveTab(v)} variant="scrollable" scrollButtons="auto"
              sx={{ borderBottom: 1, borderColor: 'divider', '& .MuiTab-root': { textTransform: 'none', fontWeight: 500 } }}>
              {tabs.map(tab => <Tab key={tab.key} label={tab.label} />)}
            </Tabs>
            {tabs.map((tab, idx) => (
              <Box key={tab.key} sx={{ p: 2, display: idx === activeTab ? 'block' : 'none' }}>
                {tab.key === 'rawLog'
                  ? <RawLogTab analysisId={selected!.id} initialLevel={initialLevel} levelCounts={selected!.levelCounts} jumpToLine={jumpToLine} onJumpComplete={handleJumpComplete} highlightRange={jumpToRange} onRangeComplete={handleRangeComplete} active={idx === activeTab} />
                  : tab.key === 'insights'
                    ? <PerformanceInsightsTab analysisId={selected!.id} initialEndpoint={insightsEndpoint} initialTimestamp={insightsTimestamp} onEndpointConsumed={handleInsightsConsumed} onGoToApiCalls={handleGoToApiCalls} active={idx === activeTab} />
                    : tab.key === 'apiCalls'
                      ? <ApiCallsTab analysisId={selected!.id} sensitiveFields={presetObj?.sensitiveFieldNames ?? []} timeRangeStart={selected!.timeRangeStart} timeRangeEnd={selected!.timeRangeEnd} onJumpToLine={handleJumpToLine} onJumpToRange={handleJumpToRange} onViewInsights={handleViewInsightsForCall} orphanRequestCount={selected!.orphanRequestCount} onGoToOrphans={() => goToTab('orphanRequests')} initialTimeFrom={apiCallsTimeFrom} initialTimeTo={apiCallsTimeTo} onTimeRangeConsumed={handleApiCallsTimeConsumed} hasConnectionDelay={selected!.hasConnectionDelay} />
                      : tab.component}
              </Box>
            ))}
          </Paper>
        </>
      )}

      {/* ================================================================
          DIALOGS
          ================================================================ */}
      <AnalysisOptionsDialog
        open={optionsDialogOpen}
        onClose={() => { setOptionsDialogOpen(false); setPendingFiles([]); setPendingContainer(null) }}
        onStart={handleStartAnalysis}
        files={pendingFiles}
        onFilesChange={setPendingFiles}
        presets={presets}
        initialConfig={{
          label: '',
          selectedPreset,
          slowThreshold,
          customRegex,
          customFieldInputs,
          analysisOptions: lastAnalysisOptions?.analysisOptions ?? {
            apiCalls: true, jobs: true, failures: true,
            criticalIssues: true, npeAnalysis: true,
            exceptionAnalysis: true, customFields: true,
          },
        }}
        containerSource={pendingContainer}
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
            if (!oldest) return null
            const vc = viewerCounts[oldest.id] ?? 0
            const others = selectedId === oldest.id ? Math.max(0, vc - 1) : vc
            return (
              <>
                <Alert severity="warning" variant="outlined">
                  <Typography variant="body2" fontWeight={500}>
                    {oldest.sourceFiles.map(f => f.filename).join(', ')}
                  </Typography>
                  <Typography variant="caption" color="text.secondary">
                    {oldest.totalLineCount.toLocaleString()} {t('logAnalyzer.common.lines')}
                  </Typography>
                </Alert>
                {others > 0 && (
                  <Alert severity="warning" sx={{ mt: 2 }} icon={<Visibility />}>
                    {t('logAnalyzer.capacity.viewerWarning', { count: others })}
                  </Alert>
                )}
              </>
            )
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
