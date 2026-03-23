import { describe, it, expect } from 'vitest'
import { formatBytes, formatBytesRate, formatScriptSize } from '../utils/format'

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
