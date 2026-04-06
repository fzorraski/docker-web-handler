import { describe, it, expect, vi, beforeEach } from 'vitest'
import {
  getJobFilters,
  getJobs,
  getAnomalyDetection,
  getAnomalySignalTypes,
} from '../services/logAnalyzerService'

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
})
