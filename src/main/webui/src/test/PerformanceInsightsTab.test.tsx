import { describe, it, expect } from 'vitest'

/**
 * Tests for the "View API Calls" time range calculation used in
 * PerformanceInsightsTab's bucket dialog button.
 *
 * The component computes the bucket time range as:
 *   start = new Date(selectedBucket.timestamp)
 *   end   = new Date(start + BUCKET_MINUTES[bucketWidth] * 60_000)
 */

const BUCKET_MINUTES: Record<string, number> = { '1m': 1, '5m': 5, '15m': 15, '1h': 60 }

function computeBucketRange(timestamp: string, bucketWidth: string): { from: string; to: string } {
  const start = new Date(timestamp)
  const end = new Date(start.getTime() + (BUCKET_MINUTES[bucketWidth] ?? 15) * 60_000)
  return { from: start.toISOString(), to: end.toISOString() }
}

describe('Performance Insights – bucket time range calculation', () => {

  it('computes correct range for 15m bucket', () => {
    const { from, to } = computeBucketRange('2026-04-17T07:30:00.000Z', '15m')
    expect(from).toBe('2026-04-17T07:30:00.000Z')
    expect(to).toBe('2026-04-17T07:45:00.000Z')
  })

  it('computes correct range for 1m bucket', () => {
    const { from, to } = computeBucketRange('2026-04-17T08:15:00.000Z', '1m')
    expect(from).toBe('2026-04-17T08:15:00.000Z')
    expect(to).toBe('2026-04-17T08:16:00.000Z')
  })

  it('computes correct range for 5m bucket', () => {
    const { from, to } = computeBucketRange('2026-04-17T10:00:00.000Z', '5m')
    expect(from).toBe('2026-04-17T10:00:00.000Z')
    expect(to).toBe('2026-04-17T10:05:00.000Z')
  })

  it('computes correct range for 1h bucket', () => {
    const { from, to } = computeBucketRange('2026-04-17T08:00:00.000Z', '1h')
    expect(from).toBe('2026-04-17T08:00:00.000Z')
    expect(to).toBe('2026-04-17T09:00:00.000Z')
  })

  it('falls back to 15m for unknown bucket width', () => {
    const { from, to } = computeBucketRange('2026-04-17T08:00:00.000Z', 'unknown')
    expect(from).toBe('2026-04-17T08:00:00.000Z')
    expect(to).toBe('2026-04-17T08:15:00.000Z')
  })

  it('handles midnight boundary correctly', () => {
    const { from, to } = computeBucketRange('2026-04-17T23:50:00.000Z', '15m')
    expect(from).toBe('2026-04-17T23:50:00.000Z')
    expect(to).toBe('2026-04-18T00:05:00.000Z')
  })

  it('preserves millisecond precision from timestamp', () => {
    const { from, to } = computeBucketRange('2026-04-17T07:30:00.123Z', '15m')
    expect(from).toBe('2026-04-17T07:30:00.123Z')
    expect(to).toBe('2026-04-17T07:45:00.123Z')
  })
})
