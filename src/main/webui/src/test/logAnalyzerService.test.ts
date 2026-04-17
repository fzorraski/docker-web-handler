import { describe, it, expect, vi, beforeEach } from 'vitest'
import {
  getJobFilters,
  getJobs,
  getLines,
  getApiCalls,
  getOrphanRequests,
  getCustomFieldResults,
  getAnomalyDetection,
  getAnomalySignalTypes,
} from '../services/logAnalyzerService'
import {
  prepareLogAnalysis,
  cancelLogAnalysis,
  setLogAnalysisViewing,
} from '../services/sseService'

const mockFetch = vi.fn()
global.fetch = mockFetch

function jsonResponse(data: unknown, ok = true) {
  return Promise.resolve({
    ok,
    status: ok ? 200 : 500,
    statusText: ok ? 'OK' : 'Internal Server Error',
    json: () => Promise.resolve(data),
  } as Response)
}

beforeEach(() => {
  mockFetch.mockReset()
})

describe('logAnalyzerService', () => {

  // ---- getJobFilters ----

  describe('getJobFilters', () => {
    it('fetches filter metadata', async () => {
      const data = { jobNames: ['BackupJob', 'CleanupJob'], threads: ['scheduler-1'] }
      mockFetch.mockReturnValue(jsonResponse(data))

      const result = await getJobFilters('abc-123')

      expect(mockFetch).toHaveBeenCalledWith('/api/logs/analyzer/abc-123/jobs/filters')
      expect(result).toEqual(data)
    })

    it('throws on error response', async () => {
      mockFetch.mockReturnValue(jsonResponse({ error: 'not found' }, false))

      await expect(getJobFilters('bad-id')).rejects.toThrow('not found')
    })
  })

  // ---- getJobs ----

  describe('getJobs', () => {
    it('builds query params from all filter options', async () => {
      const data = { data: [], total: 0, page: 0, size: 25 }
      mockFetch.mockReturnValue(jsonResponse(data))

      await getJobs('abc', { jobName: 'BackupJob', thread: 'sched-1', sort: 'duration', page: 2, size: 50 })

      const url = mockFetch.mock.calls[0][0] as string
      expect(url).toContain('jobName=BackupJob')
      expect(url).toContain('thread=sched-1')
      expect(url).toContain('sort=duration')
      expect(url).toContain('page=2')
      expect(url).toContain('size=50')
    })

    it('omits undefined params', async () => {
      mockFetch.mockReturnValue(jsonResponse({ data: [], total: 0, page: 0, size: 25 }))

      await getJobs('abc', { page: 0, size: 25 })

      const url = mockFetch.mock.calls[0][0] as string
      expect(url).not.toContain('jobName=')
      expect(url).not.toContain('thread=')
      expect(url).not.toContain('sort=')
      expect(url).toContain('page=0')
      expect(url).toContain('size=25')
    })

    it('returns paginated response', async () => {
      const data = {
        data: [{ jobName: 'CleanupJob', durationMs: 5000, thread: 'sched-1' }],
        total: 1, page: 0, size: 25,
      }
      mockFetch.mockReturnValue(jsonResponse(data))

      const result = await getJobs('abc', { page: 0, size: 25 })

      expect(result.data).toHaveLength(1)
      expect(result.total).toBe(1)
    })

    it('throws on error response', async () => {
      mockFetch.mockReturnValue(jsonResponse({ error: 'fail' }, false))

      await expect(getJobs('abc')).rejects.toThrow('fail')
    })
  })

  // ---- getLines ----

  describe('getLines', () => {
    it('builds query params from all filter options', async () => {
      mockFetch.mockReturnValue(jsonResponse({ data: [], total: 0, page: 0, size: 100 }))

      await getLines('abc', { thread: 'main', level: 'ERROR', search: 'timeout', exclude: 'debug,health', page: 1, size: 500 })

      const url = mockFetch.mock.calls[0][0] as string
      expect(url).toContain('thread=main')
      expect(url).toContain('level=ERROR')
      expect(url).toContain('search=timeout')
      expect(url).toContain('exclude=debug%2Chealth')
      expect(url).toContain('page=1')
      expect(url).toContain('size=500')
    })

    it('omits undefined params', async () => {
      mockFetch.mockReturnValue(jsonResponse({ data: [], total: 0, page: 0, size: 100 }))

      await getLines('abc', { page: 0, size: 100 })

      const url = mockFetch.mock.calls[0][0] as string
      expect(url).not.toContain('thread=')
      expect(url).not.toContain('level=')
      expect(url).not.toContain('search=')
      expect(url).not.toContain('exclude=')
    })

    it('includes exclude param when provided', async () => {
      mockFetch.mockReturnValue(jsonResponse({ data: [], total: 0, page: 0, size: 100 }))

      await getLines('abc', { exclude: 'integration 002', page: 0, size: 100 })

      const url = mockFetch.mock.calls[0][0] as string
      expect(url).toContain('exclude=integration+002')
    })

    it('passes abort signal to fetch', async () => {
      mockFetch.mockReturnValue(jsonResponse({ data: [], total: 0, page: 0, size: 100 }))
      const controller = new AbortController()

      await getLines('abc', { page: 0, size: 100, signal: controller.signal })

      const init = mockFetch.mock.calls[0][1] as RequestInit
      expect(init.signal).toBe(controller.signal)
    })

    it('throws on error response', async () => {
      mockFetch.mockReturnValue(jsonResponse({ error: 'not found' }, false))

      await expect(getLines('bad', {})).rejects.toThrow('not found')
    })
  })

  // ---- getApiCalls ----

  describe('getApiCalls', () => {
    it('builds query params from all filter options', async () => {
      mockFetch.mockReturnValue(jsonResponse({ data: [], total: 0, page: 0, size: 50 }))

      await getApiCalls('abc', { endpoint: '/api/test', thread: 'http-1', search: 'err', sort: 'duration', sortDir: 'desc', page: 2, size: 50 })

      const url = mockFetch.mock.calls[0][0] as string
      expect(url).toContain('endpoint=%2Fapi%2Ftest')
      expect(url).toContain('thread=http-1')
      expect(url).toContain('search=err')
      expect(url).toContain('sort=duration')
      expect(url).toContain('sortDir=desc')
      expect(url).toContain('page=2')
      expect(url).toContain('size=50')
    })

    it('passes abort signal to fetch', async () => {
      mockFetch.mockReturnValue(jsonResponse({ data: [], total: 0, page: 0, size: 50 }))
      const controller = new AbortController()

      await getApiCalls('abc', { page: 0, size: 50, signal: controller.signal })

      const init = mockFetch.mock.calls[0][1] as RequestInit
      expect(init.signal).toBe(controller.signal)
    })

    it('throws on error response', async () => {
      mockFetch.mockReturnValue(jsonResponse({ error: 'fail' }, false))

      await expect(getApiCalls('abc')).rejects.toThrow('fail')
    })
  })

  // ---- getOrphanRequests ----

  describe('getOrphanRequests', () => {
    it('builds query params', async () => {
      mockFetch.mockReturnValue(jsonResponse({ data: [], total: 0, page: 0, size: 25 }))

      await getOrphanRequests('abc', { endpoint: '/api/orders', thread: 'http-2', page: 1, size: 25 })

      const url = mockFetch.mock.calls[0][0] as string
      expect(url).toContain('endpoint=%2Fapi%2Forders')
      expect(url).toContain('thread=http-2')
      expect(url).toContain('page=1')
      expect(url).toContain('size=25')
    })

    it('passes abort signal to fetch', async () => {
      mockFetch.mockReturnValue(jsonResponse({ data: [], total: 0, page: 0, size: 25 }))
      const controller = new AbortController()

      await getOrphanRequests('abc', { page: 0, size: 25, signal: controller.signal })

      const init = mockFetch.mock.calls[0][1] as RequestInit
      expect(init.signal).toBe(controller.signal)
    })

    it('throws on error response', async () => {
      mockFetch.mockReturnValue(jsonResponse({ error: 'fail' }, false))

      await expect(getOrphanRequests('abc')).rejects.toThrow('fail')
    })
  })

  // ---- getCustomFieldResults ----

  describe('getCustomFieldResults', () => {
    it('builds query params with field name', async () => {
      mockFetch.mockReturnValue(jsonResponse({ data: [], total: 0, page: 0, size: 50 }))

      await getCustomFieldResults('abc', 'Entity Changes', { search: 'INSERT', thread: 'main', sort: 'line', sortDir: 'asc', page: 0, size: 50 })

      const url = mockFetch.mock.calls[0][0] as string
      expect(url).toContain('/custom-fields/Entity%20Changes')
      expect(url).toContain('search=INSERT')
      expect(url).toContain('thread=main')
      expect(url).toContain('sort=line')
      expect(url).toContain('sortDir=asc')
    })

    it('passes abort signal to fetch', async () => {
      mockFetch.mockReturnValue(jsonResponse({ data: [], total: 0, page: 0, size: 50 }))
      const controller = new AbortController()

      await getCustomFieldResults('abc', 'Test', { page: 0, size: 50, signal: controller.signal })

      const init = mockFetch.mock.calls[0][1] as RequestInit
      expect(init.signal).toBe(controller.signal)
    })

    it('throws on error response', async () => {
      mockFetch.mockReturnValue(jsonResponse({ error: 'fail' }, false))

      await expect(getCustomFieldResults('abc', 'Test')).rejects.toThrow('fail')
    })
  })

  // ---- getJobs signal passthrough ----

  describe('getJobs signal', () => {
    it('passes abort signal to fetch', async () => {
      mockFetch.mockReturnValue(jsonResponse({ data: [], total: 0, page: 0, size: 25 }))
      const controller = new AbortController()

      await getJobs('abc', { page: 0, size: 25, signal: controller.signal })

      const init = mockFetch.mock.calls[0][1] as RequestInit
      expect(init.signal).toBe(controller.signal)
    })
  })

  // ---- getAnomalyDetection ----

  describe('getAnomalyDetection', () => {
    it('builds query params', async () => {
      const data = {
        signalType: 'ERROR_COUNT', bucketSize: 300, threshold: 3.0,
        totalBuckets: 0, anomalyBuckets: 0, peakValue: 0,
        peakBucketLabel: '', p95Value: 0,
        buckets: [], anomalies: [], correlations: [],
      }
      mockFetch.mockReturnValue(jsonResponse(data))

      await getAnomalyDetection('abc', {
        signalType: 'API_LATENCY', bucketSize: 600, threshold: 2.5, baselineWindow: 10,
      })

      const url = mockFetch.mock.calls[0][0] as string
      expect(url).toContain('signalType=API_LATENCY')
      expect(url).toContain('bucketSize=600')
      expect(url).toContain('threshold=2.5')
      expect(url).toContain('baselineWindow=10')
    })

    it('omits query string when no params', async () => {
      const data = {
        signalType: 'ERROR_COUNT', bucketSize: 300, threshold: 3.0,
        totalBuckets: 0, anomalyBuckets: 0, peakValue: 0,
        peakBucketLabel: '', p95Value: 0,
        buckets: [], anomalies: [], correlations: [],
      }
      mockFetch.mockReturnValue(jsonResponse(data))

      await getAnomalyDetection('abc')

      const url = mockFetch.mock.calls[0][0] as string
      expect(url).toBe('/api/logs/analyzer/abc/anomaly-detection')
    })

    it('returns detection response', async () => {
      const data = {
        signalType: 'ERROR_COUNT', bucketSize: 300, threshold: 3.0,
        totalBuckets: 6, anomalyBuckets: 1, peakValue: 50,
        peakBucketLabel: '10:15', p95Value: 30,
        buckets: [{ bucketLabel: '10:00', count: 5 }],
        anomalies: [{ signalType: 'ERROR_COUNT', bucketLabel: '10:15' }],
        correlations: [],
      }
      mockFetch.mockReturnValue(jsonResponse(data))

      const result = await getAnomalyDetection('abc', { signalType: 'ERROR_COUNT' })

      expect(result.totalBuckets).toBe(6)
      expect(result.anomalyBuckets).toBe(1)
      expect(result.buckets).toHaveLength(1)
      expect(result.anomalies).toHaveLength(1)
    })
  })

  // ---- getAnomalySignalTypes ----

  describe('getAnomalySignalTypes', () => {
    it('fetches signal types as string array', async () => {
      mockFetch.mockReturnValue(jsonResponse(['ERROR_COUNT', 'API_LATENCY', 'NPE']))

      const result = await getAnomalySignalTypes('abc')

      expect(mockFetch).toHaveBeenCalledWith('/api/logs/analyzer/abc/anomaly-detection/signal-types')
      expect(result).toEqual(['ERROR_COUNT', 'API_LATENCY', 'NPE'])
    })

    it('throws on error', async () => {
      mockFetch.mockReturnValue(jsonResponse({ error: 'not found' }, false))

      await expect(getAnomalySignalTypes('bad')).rejects.toThrow('not found')
    })
  })

  // ---- prepareLogAnalysis ----

  describe('prepareLogAnalysis', () => {
    it('sends multipart form with files and options', async () => {
      mockFetch.mockReturnValue(jsonResponse({ ticket: 'abc-123' }))

      const file = new File(['log content'], 'test.log', { type: 'text/plain' })
      const ticket = await prepareLogAnalysis([file], { preset: 'WILDFLY', slowThresholdMs: '1000' })

      expect(ticket).toBe('abc-123')
      expect(mockFetch).toHaveBeenCalledTimes(1)
      const [url, init] = mockFetch.mock.calls[0]
      expect(url).toBe('/api/logs/analyzer/sse/upload/prepare')
      expect(init.method).toBe('POST')
      expect(init.body).toBeInstanceOf(FormData)
    })

    it('includes all option fields in form data', async () => {
      mockFetch.mockReturnValue(jsonResponse({ ticket: 'xyz' }))

      const file = new File(['data'], 'app.log')
      await prepareLogAnalysis([file], {
        preset: 'WILDFLY',
        logLineRegex: '.*',
        customFields: '[{"name":"Test","regex":".*","countOnly":false}]',
      })

      const formData = mockFetch.mock.calls[0][1].body as FormData
      expect(formData.get('preset')).toBe('WILDFLY')
      expect(formData.get('logLineRegex')).toBe('.*')
      expect(formData.get('customFields')).toContain('Test')
    })

    it('skips undefined options', async () => {
      mockFetch.mockReturnValue(jsonResponse({ ticket: 'xyz' }))

      await prepareLogAnalysis([new File([''], 'f.log')], { preset: 'WILDFLY', logLineRegex: undefined })

      const formData = mockFetch.mock.calls[0][1].body as FormData
      expect(formData.get('preset')).toBe('WILDFLY')
      expect(formData.get('logLineRegex')).toBeNull()
    })

    it('throws on error response', async () => {
      mockFetch.mockReturnValue(jsonResponse({ error: 'disabled' }, false))

      await expect(prepareLogAnalysis([new File([''], 'f.log')], {})).rejects.toThrow('disabled')
    })
  })

  // ---- cancelLogAnalysis ----

  describe('cancelLogAnalysis', () => {
    it('sends POST to cancel endpoint', async () => {
      mockFetch.mockReturnValue(jsonResponse({ cancelled: true }))

      const result = await cancelLogAnalysis('ticket-123')

      expect(result).toBe(true)
      expect(mockFetch).toHaveBeenCalledWith(
        '/api/logs/analyzer/sse/upload/cancel/ticket-123',
        { method: 'POST' },
      )
    })

    it('returns false on error', async () => {
      mockFetch.mockReturnValue(jsonResponse({}, false))

      const result = await cancelLogAnalysis('bad-ticket')

      expect(result).toBe(false)
    })
  })

  // ---- setLogAnalysisViewing ----

  describe('setLogAnalysisViewing', () => {
    it('sends viewing state to server', async () => {
      mockFetch.mockReturnValue(jsonResponse({ ok: true }))

      await setLogAnalysisViewing('client-token-1', 'analysis-abc')

      expect(mockFetch).toHaveBeenCalledWith(
        '/api/logs/analyzer/sse/viewing',
        expect.objectContaining({
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
        }),
      )
      const body = JSON.parse(mockFetch.mock.calls[0][1].body)
      expect(body.clientToken).toBe('client-token-1')
      expect(body.analysisId).toBe('analysis-abc')
    })

    it('sends null analysisId to clear viewing', async () => {
      mockFetch.mockReturnValue(jsonResponse({ ok: true }))

      await setLogAnalysisViewing('client-token-1', null)

      const body = JSON.parse(mockFetch.mock.calls[0][1].body)
      expect(body.analysisId).toBeNull()
    })

    it('does not throw on network error', async () => {
      mockFetch.mockRejectedValue(new Error('network error'))

      // Should not throw — fire-and-forget
      await expect(setLogAnalysisViewing('t', 'a')).resolves.toBeUndefined()
    })
  })
})
