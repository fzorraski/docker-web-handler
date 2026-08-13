import { describe, it, expect } from 'vitest'
import { formatBytes, formatBytesRate, formatScriptSize, truncate } from '../utils/format'

describe('formatBytes', () => {
  it('formats bytes', () => {
    expect(formatBytes(500)).toBe('500 B')
  })

  it('formats kilobytes', () => {
    expect(formatBytes(2048)).toBe('2.0 KB')
  })

  it('formats megabytes', () => {
    expect(formatBytes(5 * 1024 * 1024)).toBe('5.00 MB')
  })

  it('formats gigabytes', () => {
    expect(formatBytes(2 * 1024 * 1024 * 1024)).toBe('2.00 GB')
  })

  it('formats zero', () => {
    expect(formatBytes(0)).toBe('0 B')
  })
})

describe('formatBytesRate', () => {
  it('appends /s suffix', () => {
    expect(formatBytesRate(1024)).toBe('1.0 KB/s')
  })
})

describe('formatScriptSize', () => {
  it('formats bytes', () => {
    expect(formatScriptSize(100)).toBe('100 B')
  })

  it('formats kilobytes', () => {
    expect(formatScriptSize(2048)).toBe('2.0 KB')
  })

  it('formats megabytes', () => {
    expect(formatScriptSize(5 * 1024 * 1024)).toBe('5.0 MB')
  })
})

describe('truncate', () => {
  it('returns short values untouched, so callers can detect truncation by identity', () => {
    expect(truncate('20.98.0', 18)).toBe('20.98.0')
    expect(truncate('123456789012345678', 18)).toBe('123456789012345678')
  })

  it('cuts to the limit including the ellipsis', () => {
    const digest = 'bd7214219d260d7efb82525207dae8dde38cfa1f9a2506b2d2a473fd48d5a9f0'
    const cut = truncate(digest, 18)
    expect(cut).toHaveLength(18)
    expect(cut.endsWith('\u2026')).toBe(true)
    expect(digest.startsWith(cut.slice(0, -1))).toBe(true)
  })
})
