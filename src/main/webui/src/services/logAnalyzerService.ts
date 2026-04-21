import fetchWithAuth from './fetchWithAuth'

const API = '/api/logs/analyzer'

async function handleResponse<T>(res: Response): Promise<T> {
  if (!res.ok) {
    const body = await res.json().catch(() => ({ error: res.statusText }))
    throw new Error(body.error || res.statusText)
  }
  return res.json()
}

// ---- Types ----

export interface CustomField {
  name: string
  regex: string
  countOnly: boolean
}

export interface CustomFieldMatch {
  lineNumber: number
  timestamp: string | null
  thread: string | null
  sourceFile: string
  fullMessage: string
  groups: Record<string, string>
  fullMessageTruncated: boolean
  fullMessageSize: number
}

export interface CustomFieldSummary {
  fieldName: string
  matchCount: number
  countOnly: boolean
}

export interface LogPreset {
  name: string
  logLineRegex: string
  timestampFormat: string
  apiCallRegex: string
  jobStartRegex: string | null
  jobEndRegex: string | null
  failureRegex: string | null
  sensitiveFieldNames: string[]
  customFields: CustomField[]
  criticalIssueExclusions: string[]
}

export interface AnalyzerStatus {
  enabled: boolean
  presets: LogPreset[]
  defaultPreset: string
  containerTail: number
  maxFiles: number
  currentFiles: number
}

export interface AnalysisSummary {
  id: string
  label: string | null
  sourceFiles: { filename: string; size: number }[]
  totalLineCount: number
  uploadedAt: string
  timeRangeStart: string
  timeRangeEnd: string
  threadCount: number
  endpointCount: number
  apiCallCount: number
  orphanRequestCount: number
  errorCount: number
  levelCounts: Record<string, number>
  jobExecutionCount: number
  repeatedFailureCount: number
  criticalIssueCount: number
  criticalIssueSummaries: { category: string; severity: string; count: number }[]
  npeAnalysisCount: number
  npeLocationCount: number
  exceptionAnalysisCount: number
  exceptionTypeCount: number
  customFields: CustomFieldSummary[]
}

export interface ApiCallPair {
  endpoint: string
  correlationId: string | null
  thread: string
  requestTimestamp: string
  responseTimestamp: string
  durationMs: number
  requestPayload: string
  responsePayload: string
  requestLineNumber: number
  responseLineNumber: number
  sourceFile: string
  slow: boolean
  requestPayloadTruncated: boolean
  responsePayloadTruncated: boolean
  requestPayloadSize: number
  responsePayloadSize: number
}

export interface EndpointStats {
  endpoint: string
  callCount: number
  avgDurationMs: number
  minDurationMs: number
  maxDurationMs: number
  p95DurationMs: number
  slowCount: number
}

export interface LogLine {
  lineNumber: number
  timestamp: string | null
  level: string | null
  logger: string | null
  thread: string | null
  message: string | null
  sourceFile: string
  messageTruncated: boolean
  messageSize: number
}

export interface OrphanRequest {
  endpoint: string
  thread: string
  timestamp: string | null
  payload: string | null
  lineNumber: number
  sourceFile: string
  payloadTruncated: boolean
  payloadSize: number
}

export interface JobExecution {
  jobName: string
  triggerName: string | null
  thread: string
  startTimestamp: string
  endTimestamp: string
  durationMs: number
  result: string
  startLineNumber: number
  endLineNumber: number
  sourceFile: string
}

export interface RepeatedFailure {
  entityId: string
  reason: string | null
  occurrences: number
  firstSeen: string
  lastSeen: string
  details: { timestamp: string; lineNumber: number; message: string; sourceFile: string; messageTruncated: boolean; messageSize: number }[]
}

export interface NpeOccurrence {
  originClass: string
  method: string
  sourceFile: string
  sourceLine: number
  message: string | null
  timestamp: string | null
  logLineNumber: number
  logSourceFile: string
  stackTrace: string[]
  messageTruncated: boolean
  messageSize: number
}

export interface NpeLocationSummary {
  origin: string
  originClass: string
  method: string
  sourceFile: string
  sourceLine: number
  count: number
  firstSeen: string | null
  lastSeen: string | null
  occurrences: NpeOccurrence[]
}

export interface ExceptionOccurrence {
  exceptionType: string
  originClass: string
  method: string
  sourceFile: string
  sourceLine: number
  message: string | null
  timestamp: string | null
  logLineNumber: number
  logSourceFile: string
  stackTrace: string[]
  messageTruncated: boolean
  messageSize: number
}

export interface ExceptionLocationSummary {
  exceptionType: string
  origin: string
  originClass: string
  method: string
  sourceFile: string
  sourceLine: number
  count: number
  firstSeen: string | null
  lastSeen: string | null
  occurrences: ExceptionOccurrence[]
}

export interface CriticalIssue {
  category: string
  severity: string
  pattern: string
  lineNumber: number
  timestamp: string | null
  message: string
  sourceFile: string
  messageTruncated: boolean
  messageSize: number
}

export interface CriticalBurst {
  category: string
  severity: string
  burstStart: string | null
  burstEnd: string | null
  issueCount: number
  issues: CriticalIssue[]
}

export interface CriticalIssueSummary {
  category: string
  severity: string
  count: number
  firstSeen: string | null
  lastSeen: string | null
  issues: CriticalIssue[]
  bursts: CriticalBurst[]
}

export interface PaginatedResponse<T> {
  data: T[]
  total: number
  page: number
  size: number
}

export interface ThreadInfo {
  thread: string
  lineCount: number
}

export interface EndpointBucket {
  endpoint: string
  count: number
  avgDurationMs: number
  p95DurationMs: number
}

export interface TimeBucket {
  timestamp: string
  requestCount: number
  avgDurationMs: number
  p95DurationMs: number
  maxDurationMs: number
  concurrentPeak: number
  endpoints: EndpointBucket[]
}

export interface EndpointImpact {
  endpoint: string
  callCount: number
  avgDurationMs: number
  totalDurationMs: number
  p95DurationMs: number
  slowCount: number
}

export interface PerformanceInsightsResponse {
  timeBuckets: TimeBucket[]
  topEndpointsByImpact: EndpointImpact[]
  bucketWidth: string
  totalBuckets: number
}

export interface BucketStats {
  bucketLabel: string
  bucketEpoch: number
  count: number
  sum: number
  min: number
  max: number
  p95: number
  status: string
  baseline: number
  ratio: number
}

export interface AnomalyResult {
  signalType: string
  bucketLabel: string
  observedValue: number
  baselineValue: number
  ratio: number
  count: number
  maxInBucket: number
}

export interface CorrelatedAnomaly {
  windowStart: string
  windowEnd: string
  signalTypes: string[]
  score: number
  severity: string
  causalChain: string[]
  anomalies: AnomalyResult[]
}

export interface AnomalyDetectionResponse {
  signalType: string
  metric: string
  method: string
  bucketSize: number
  threshold: number
  totalBuckets: number
  anomalyBuckets: number
  peakValue: number
  peakBucketLabel: string
  p95Value: number
  buckets: BucketStats[]
  anomalies: AnomalyResult[]
  correlations: CorrelatedAnomaly[]
}

// ---- API calls ----

export async function getStatus(): Promise<AnalyzerStatus> {
  const res = await fetchWithAuth(`${API}/status`)
  return handleResponse(res)
}

export async function isLogAnalyzerEnabled(): Promise<boolean> {
  try {
    const status = await getStatus()
    return status.enabled
  } catch {
    return false
  }
}

export interface AnalysisOptions {
  apiCalls: boolean
  jobs: boolean
  failures: boolean
  criticalIssues: boolean
  npeAnalysis: boolean
  exceptionAnalysis: boolean
  customFields: boolean
}

export interface UploadOptions {
  preset?: string
  logLineRegex?: string
  apiCallRegex?: string
  timestampFormat?: string
  jobStartRegex?: string
  jobEndRegex?: string
  failureRegex?: string
  sensitiveFieldNames?: string
  criticalIssueExclusions?: string
  slowThresholdMs?: number
  customFields?: string
  analysisOptions?: AnalysisOptions
}

export async function uploadFiles(
  files: File[],
  options: UploadOptions = {},
): Promise<AnalysisSummary> {
  const form = new FormData()
  files.forEach((f) => form.append('files', f))
  if (options.preset) form.append('preset', options.preset)
  if (options.logLineRegex) form.append('logLineRegex', options.logLineRegex)
  if (options.apiCallRegex) form.append('apiCallRegex', options.apiCallRegex)
  if (options.timestampFormat) form.append('timestampFormat', options.timestampFormat)
  if (options.jobStartRegex) form.append('jobStartRegex', options.jobStartRegex)
  if (options.jobEndRegex) form.append('jobEndRegex', options.jobEndRegex)
  if (options.failureRegex) form.append('failureRegex', options.failureRegex)
  if (options.sensitiveFieldNames) form.append('sensitiveFieldNames', options.sensitiveFieldNames)
  if (options.slowThresholdMs != null) form.append('slowThresholdMs', String(options.slowThresholdMs))
  if (options.customFields) form.append('customFields', options.customFields)
  if (options.analysisOptions) form.append('options', JSON.stringify(options.analysisOptions))

  const res = await fetchWithAuth(`${API}/upload`, { method: 'POST', body: form })
  return handleResponse(res)
}

export async function analyzeContainerLogs(
  containerId: string,
  params: { containerName?: string; preset?: string; slowThresholdMs?: number; lines?: number; direction?: 'head' | 'tail' } = {},
): Promise<AnalysisSummary> {
  const q = new URLSearchParams()
  if (params.containerName) q.set('containerName', params.containerName)
  if (params.preset) q.set('preset', params.preset)
  if (params.slowThresholdMs != null) q.set('slowThresholdMs', String(params.slowThresholdMs))
  if (params.lines != null) q.set('lines', String(params.lines))
  if (params.direction) q.set('direction', params.direction)
  const res = await fetchWithAuth(`${API}/from-container/${encodeURIComponent(containerId)}?${q}`, { method: 'POST' })
  return handleResponse(res)
}

export async function composeAnalyses(
  ids: string[],
  preset?: string,
  slowThresholdMs?: number,
): Promise<AnalysisSummary> {
  const res = await fetchWithAuth(`${API}/compose`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ ids, preset, slowThresholdMs }),
  })
  return handleResponse(res)
}

export async function getAnalysis(id: string): Promise<AnalysisSummary> {
  const res = await fetchWithAuth(`${API}/${id}`)
  return handleResponse(res)
}

export async function deleteAnalysis(id: string): Promise<void> {
  const res = await fetchWithAuth(`${API}/${id}`, { method: 'DELETE' })
  if (!res.ok) throw new Error('Delete failed')
}

export async function getApiCalls(
  id: string,
  params: { endpoint?: string; thread?: string; minDuration?: number; maxDuration?: number; slowOnly?: boolean; search?: string; exclude?: string; timeFrom?: string; timeTo?: string; sort?: string; sortDir?: string; page?: number; size?: number; signal?: AbortSignal } = {},
): Promise<PaginatedResponse<ApiCallPair>> {
  const q = new URLSearchParams()
  if (params.endpoint) q.set('endpoint', params.endpoint)
  if (params.thread) q.set('thread', params.thread)
  if (params.minDuration != null) q.set('minDuration', String(params.minDuration))
  if (params.maxDuration != null) q.set('maxDuration', String(params.maxDuration))
  if (params.slowOnly) q.set('slowOnly', 'true')
  if (params.search) q.set('search', params.search)
  if (params.exclude) q.set('exclude', params.exclude)
  if (params.timeFrom) q.set('timeFrom', params.timeFrom)
  if (params.timeTo) q.set('timeTo', params.timeTo)
  if (params.sort) q.set('sort', params.sort)
  if (params.sortDir) q.set('sortDir', params.sortDir)
  if (params.page != null) q.set('page', String(params.page))
  if (params.size != null) q.set('size', String(params.size))
  const res = await fetchWithAuth(`${API}/${id}/api-calls?${q}`, { signal: params.signal })
  return handleResponse(res)
}

export async function getApiStats(id: string): Promise<EndpointStats[]> {
  const res = await fetchWithAuth(`${API}/${id}/api-stats`)
  return handleResponse(res)
}

export async function getLines(
  id: string,
  params: { thread?: string; level?: string; search?: string; exclude?: string; page?: number; size?: number; signal?: AbortSignal } = {},
): Promise<PaginatedResponse<LogLine>> {
  const q = new URLSearchParams()
  if (params.thread) q.set('thread', params.thread)
  if (params.level) q.set('level', params.level)
  if (params.search) q.set('search', params.search)
  if (params.exclude) q.set('exclude', params.exclude)
  if (params.page != null) q.set('page', String(params.page))
  if (params.size != null) q.set('size', String(params.size))
  const res = await fetchWithAuth(`${API}/${id}/lines?${q}`, { signal: params.signal })
  return handleResponse(res)
}

export async function resolveLinePage(
  id: string,
  params: { line: number; thread?: string; level?: string; search?: string; exclude?: string; size?: number; signal?: AbortSignal },
): Promise<{ page: number; found: boolean }> {
  const q = new URLSearchParams({ line: String(params.line) })
  if (params.thread) q.set('thread', params.thread)
  if (params.level) q.set('level', params.level)
  if (params.search) q.set('search', params.search)
  if (params.exclude) q.set('exclude', params.exclude)
  if (params.size != null) q.set('size', String(params.size))
  const res = await fetchWithAuth(`${API}/${id}/lines/resolve-page?${q}`, { signal: params.signal })
  return handleResponse(res)
}

export async function getLineRange(
  id: string, from: number, to: number,
  params: { level?: string; page?: number; size?: number } = {},
): Promise<PaginatedResponse<LogLine>> {
  const q = new URLSearchParams({ from: String(from), to: String(to) })
  if (params.level) q.set('level', params.level)
  if (params.page != null) q.set('page', String(params.page))
  if (params.size != null) q.set('size', String(params.size))
  const res = await fetchWithAuth(`${API}/${id}/lines/range?${q}`)
  return handleResponse(res)
}

export async function getThreads(id: string): Promise<ThreadInfo[]> {
  const res = await fetchWithAuth(`${API}/${id}/threads`)
  return handleResponse(res)
}

export async function getEndpoints(id: string): Promise<string[]> {
  const res = await fetchWithAuth(`${API}/${id}/endpoints`)
  return handleResponse(res)
}

export async function getJobFilters(id: string): Promise<{ jobNames: string[]; threads: string[] }> {
  const res = await fetchWithAuth(`${API}/${id}/jobs/filters`)
  return handleResponse(res)
}

export async function getJobs(
  id: string,
  params: { jobName?: string; thread?: string; sort?: string; page?: number; size?: number; signal?: AbortSignal } = {},
): Promise<PaginatedResponse<JobExecution>> {
  const q = new URLSearchParams()
  if (params.jobName) q.set('jobName', params.jobName)
  if (params.thread) q.set('thread', params.thread)
  if (params.sort) q.set('sort', params.sort)
  if (params.page != null) q.set('page', String(params.page))
  if (params.size != null) q.set('size', String(params.size))
  const res = await fetchWithAuth(`${API}/${id}/jobs?${q}`, { signal: params.signal })
  return handleResponse(res)
}

export async function getFailures(
  id: string,
  params: { page?: number; size?: number } = {},
): Promise<PaginatedResponse<RepeatedFailure>> {
  const q = new URLSearchParams()
  if (params.page != null) q.set('page', String(params.page))
  if (params.size != null) q.set('size', String(params.size))
  const res = await fetchWithAuth(`${API}/${id}/failures?${q}`)
  return handleResponse(res)
}

export async function getOrphanRequests(
  id: string,
  params: { endpoint?: string; thread?: string; page?: number; size?: number; signal?: AbortSignal } = {},
): Promise<PaginatedResponse<OrphanRequest>> {
  const q = new URLSearchParams()
  if (params.endpoint) q.set('endpoint', params.endpoint)
  if (params.thread) q.set('thread', params.thread)
  if (params.page != null) q.set('page', String(params.page))
  if (params.size != null) q.set('size', String(params.size))
  const res = await fetchWithAuth(`${API}/${id}/orphan-requests?${q}`, { signal: params.signal })
  return handleResponse(res)
}

export async function listAnalyses(): Promise<AnalysisSummary[]> {
  const res = await fetchWithAuth(`${API}/list`)
  return handleResponse(res)
}

export async function getPerformanceInsights(id: string, endpoint?: string): Promise<PerformanceInsightsResponse> {
  const q = new URLSearchParams()
  if (endpoint) q.set('endpoint', endpoint)
  const qs = q.toString()
  const res = await fetchWithAuth(`${API}/${id}/performance-insights${qs ? '?' + qs : ''}`)
  return handleResponse(res)
}

export async function getCriticalIssues(id: string): Promise<CriticalIssueSummary[]> {
  const res = await fetchWithAuth(`${API}/${id}/critical-issues`)
  return handleResponse(res)
}

export interface BurstCategorySummary {
  category: string
  severity: string
  burstCount: number
  totalBurstIssues: number
  firstStart: string
  lastEnd: string
}

export interface BurstMeta {
  burstStart: string
  burstEnd: string
  issueCount: number
}

export async function getCriticalBursts(id: string, threshold?: number, windowMinutes?: number): Promise<BurstCategorySummary[]> {
  const q = new URLSearchParams()
  if (threshold != null) q.set('threshold', String(threshold))
  if (windowMinutes != null) q.set('window', String(windowMinutes))
  const qs = q.toString()
  const res = await fetchWithAuth(`${API}/${id}/critical-issues/bursts${qs ? '?' + qs : ''}`)
  return handleResponse(res)
}

export async function getCriticalBurstsByCategory(
  id: string, category: string,
  params: { page?: number; size?: number } = {},
): Promise<PaginatedResponse<BurstMeta>> {
  const q = new URLSearchParams()
  if (params.page != null) q.set('page', String(params.page))
  if (params.size != null) q.set('size', String(params.size))
  const qs = q.toString()
  const res = await fetchWithAuth(`${API}/${id}/critical-issues/bursts/${encodeURIComponent(category)}${qs ? '?' + qs : ''}`)
  return handleResponse(res)
}

export async function getCriticalBurstIssues(
  id: string, category: string, burstIndex: number,
  params: { page?: number; size?: number } = {},
): Promise<PaginatedResponse<CriticalIssue>> {
  const q = new URLSearchParams()
  if (params.page != null) q.set('page', String(params.page))
  if (params.size != null) q.set('size', String(params.size))
  const qs = q.toString()
  const res = await fetchWithAuth(`${API}/${id}/critical-issues/bursts/${encodeURIComponent(category)}/${burstIndex}${qs ? '?' + qs : ''}`)
  return handleResponse(res)
}

export async function getNpeAnalysis(
  id: string,
  params: { page?: number; size?: number } = {},
): Promise<PaginatedResponse<NpeLocationSummary>> {
  const q = new URLSearchParams()
  if (params.page != null) q.set('page', String(params.page))
  if (params.size != null) q.set('size', String(params.size))
  const res = await fetchWithAuth(`${API}/${id}/npe-analysis?${q}`)
  return handleResponse(res)
}

export async function getNpeOccurrences(
  id: string, origin: string,
  params: { page?: number; size?: number } = {},
): Promise<PaginatedResponse<NpeOccurrence>> {
  const q = new URLSearchParams()
  if (params.page != null) q.set('page', String(params.page))
  if (params.size != null) q.set('size', String(params.size))
  const res = await fetchWithAuth(`${API}/${id}/npe-analysis/${encodeURIComponent(origin)}/occurrences?${q}`)
  return handleResponse(res)
}

export async function getExceptionAnalysis(
  id: string,
  params: { page?: number; size?: number } = {},
): Promise<PaginatedResponse<ExceptionLocationSummary>> {
  const q = new URLSearchParams()
  if (params.page != null) q.set('page', String(params.page))
  if (params.size != null) q.set('size', String(params.size))
  const res = await fetchWithAuth(`${API}/${id}/exception-analysis?${q}`)
  return handleResponse(res)
}

export async function getExceptionOccurrences(
  id: string, origin: string,
  params: { page?: number; size?: number } = {},
): Promise<PaginatedResponse<ExceptionOccurrence>> {
  const q = new URLSearchParams()
  if (params.page != null) q.set('page', String(params.page))
  if (params.size != null) q.set('size', String(params.size))
  const res = await fetchWithAuth(`${API}/${id}/exception-analysis/${encodeURIComponent(origin)}/occurrences?${q}`)
  return handleResponse(res)
}

export async function getCustomFieldResults(
  id: string,
  fieldName: string,
  params: { page?: number; size?: number; search?: string; thread?: string; sort?: string; sortDir?: string; signal?: AbortSignal } = {},
): Promise<PaginatedResponse<CustomFieldMatch>> {
  const q = new URLSearchParams()
  if (params.page != null) q.set('page', String(params.page))
  if (params.size != null) q.set('size', String(params.size))
  if (params.search) q.set('search', params.search)
  if (params.thread) q.set('thread', params.thread)
  if (params.sort) q.set('sort', params.sort)
  if (params.sortDir) q.set('sortDir', params.sortDir)
  const res = await fetchWithAuth(`${API}/${id}/custom-fields/${encodeURIComponent(fieldName)}?${q}`, { signal: params.signal })
  return handleResponse(res)
}

export function getReportUrl(id: string, type: 'compact' | 'complete'): string {
  return `${API}/${id}/report/${type}`
}

export function getStatsExportUrl(id: string): string {
  return `${API}/${id}/api-stats/export`
}

export interface StatsExport {
  version: number
  label: string
  exportedAt: string
  timeRangeStart: string | null
  timeRangeEnd: string | null
  endpoints: EndpointStats[]
}

export async function compareStats(
  labelA: string, labelB: string, endpointsA: EndpointStats[], endpointsB: EndpointStats[],
): Promise<Blob> {
  const res = await fetchWithAuth(`${API}/compare-stats`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ labelA, labelB, endpointsA, endpointsB }),
  })
  if (!res.ok) throw new Error('Comparison failed')
  return res.blob()
}

export async function getAnomalyDetection(
  id: string,
  params: { signalType?: string; bucketSize?: number; threshold?: number; baselineWindow?: number; metric?: string; method?: string } = {},
): Promise<AnomalyDetectionResponse> {
  const q = new URLSearchParams()
  if (params.signalType) q.set('signalType', params.signalType)
  if (params.bucketSize != null) q.set('bucketSize', String(params.bucketSize))
  if (params.threshold != null) q.set('threshold', String(params.threshold))
  if (params.baselineWindow != null) q.set('baselineWindow', String(params.baselineWindow))
  if (params.metric) q.set('metric', params.metric)
  if (params.method) q.set('method', params.method)
  const qs = q.toString()
  const res = await fetchWithAuth(`${API}/${id}/anomaly-detection${qs ? '?' + qs : ''}`)
  return handleResponse(res)
}

export async function getAnomalySignalTypes(id: string): Promise<string[]> {
  const res = await fetchWithAuth(`${API}/${id}/anomaly-detection/signal-types`)
  return handleResponse(res)
}

export interface SystemHealthResponse {
  bucketSize: number
  metric: string
  signalTypes: string[]
  durationSignals: string[]
  buckets: { time: string; epoch: number; values: Record<string, number> }[]
}

export async function getSystemHealth(
  id: string,
  params: { bucketSize?: number; metric?: string } = {},
): Promise<SystemHealthResponse> {
  const q = new URLSearchParams()
  if (params.bucketSize != null) q.set('bucketSize', String(params.bucketSize))
  if (params.metric) q.set('metric', params.metric)
  const qs = q.toString()
  const res = await fetchWithAuth(`${API}/${id}/system-health${qs ? '?' + qs : ''}`)
  return handleResponse(res)
}

export async function downloadApiCallPayload(
  id: string, line: number, type: 'request' | 'response',
): Promise<Blob> {
  const res = await fetchWithAuth(`${API}/${id}/api-calls/payload?line=${line}&type=${type}`)
  if (!res.ok) throw new Error('Download failed')
  return res.blob()
}

export async function downloadOrphanPayload(id: string, line: number): Promise<Blob> {
  const res = await fetchWithAuth(`${API}/${id}/orphan-requests/payload?line=${line}`)
  if (!res.ok) throw new Error('Download failed')
  return res.blob()
}

export async function downloadLineContent(id: string, line: number): Promise<Blob> {
  const res = await fetchWithAuth(`${API}/${id}/lines/content?line=${line}`)
  if (!res.ok) throw new Error('Download failed')
  return res.blob()
}
