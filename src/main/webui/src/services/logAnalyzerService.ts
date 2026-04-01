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
}

export interface AnalyzerStatus {
  enabled: boolean
  presets: LogPreset[]
  defaultPreset: string
  containerTail: number
}

export interface AnalysisSummary {
  id: string
  sourceFiles: { filename: string; size: number }[]
  totalLineCount: number
  uploadedAt: string
  timeRangeStart: string
  timeRangeEnd: string
  threadCount: number
  endpointCount: number
  apiCallCount: number
  errorCount: number
  levelCounts: Record<string, number>
  jobExecutionCount: number
  repeatedFailureCount: number
  criticalIssueCount: number
  criticalIssueSummaries: { category: string; severity: string; count: number }[]
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
  details: { timestamp: string; lineNumber: number; message: string; sourceFile: string }[]
}

export interface CriticalIssue {
  category: string
  severity: string
  pattern: string
  lineNumber: number
  timestamp: string | null
  message: string
  sourceFile: string
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

export interface UploadOptions {
  preset?: string
  logLineRegex?: string
  apiCallRegex?: string
  timestampFormat?: string
  jobStartRegex?: string
  jobEndRegex?: string
  failureRegex?: string
  sensitiveFieldNames?: string
  slowThresholdMs?: number
  customFields?: string
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
  params: { endpoint?: string; thread?: string; minDuration?: number; search?: string; sort?: string; page?: number; size?: number } = {},
): Promise<PaginatedResponse<ApiCallPair>> {
  const q = new URLSearchParams()
  if (params.endpoint) q.set('endpoint', params.endpoint)
  if (params.thread) q.set('thread', params.thread)
  if (params.minDuration != null) q.set('minDuration', String(params.minDuration))
  if (params.search) q.set('search', params.search)
  if (params.sort) q.set('sort', params.sort)
  if (params.page != null) q.set('page', String(params.page))
  if (params.size != null) q.set('size', String(params.size))
  const res = await fetchWithAuth(`${API}/${id}/api-calls?${q}`)
  return handleResponse(res)
}

export async function getApiStats(id: string): Promise<EndpointStats[]> {
  const res = await fetchWithAuth(`${API}/${id}/api-stats`)
  return handleResponse(res)
}

export async function getLines(
  id: string,
  params: { thread?: string; level?: string; search?: string; page?: number; size?: number } = {},
): Promise<PaginatedResponse<LogLine>> {
  const q = new URLSearchParams()
  if (params.thread) q.set('thread', params.thread)
  if (params.level) q.set('level', params.level)
  if (params.search) q.set('search', params.search)
  if (params.page != null) q.set('page', String(params.page))
  if (params.size != null) q.set('size', String(params.size))
  const res = await fetchWithAuth(`${API}/${id}/lines?${q}`)
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

export async function getJobs(
  id: string,
  params: { page?: number; size?: number } = {},
): Promise<PaginatedResponse<JobExecution>> {
  const q = new URLSearchParams()
  if (params.page != null) q.set('page', String(params.page))
  if (params.size != null) q.set('size', String(params.size))
  const res = await fetchWithAuth(`${API}/${id}/jobs?${q}`)
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

export async function getCustomFieldResults(
  id: string,
  fieldName: string,
  params: { page?: number; size?: number } = {},
): Promise<PaginatedResponse<CustomFieldMatch>> {
  const q = new URLSearchParams()
  if (params.page != null) q.set('page', String(params.page))
  if (params.size != null) q.set('size', String(params.size))
  const res = await fetchWithAuth(`${API}/${id}/custom-fields/${encodeURIComponent(fieldName)}?${q}`)
  return handleResponse(res)
}
