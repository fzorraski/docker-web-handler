import { describe, it, expect } from 'vitest'
import { getLastUsedColor, getLastUsedLabel } from '../utils/lastUsedColor'

describe('getLastUsedColor', () => {
  it('returns success for active items', () => {
    expect(getLastUsedColor(null, true)).toBe('success')
  })

  it('returns default for null date', () => {
    expect(getLastUsedColor(null)).toBe('default')
  })

  it('returns default for undefined date', () => {
    expect(getLastUsedColor(undefined)).toBe('default')
  })

  it('returns success for date less than 7 days ago (dd/MM/yyyy format)', () => {
    const now = new Date()
    const d = new Date(now.getTime() - 2 * 24 * 60 * 60 * 1000) // 2 days ago
    const formatted = `${pad(d.getDate())}/${pad(d.getMonth() + 1)}/${d.getFullYear()} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
    expect(getLastUsedColor(formatted)).toBe('success')
  })

  it('returns info for date 7-30 days ago', () => {
    const now = new Date()
    const d = new Date(now.getTime() - 15 * 24 * 60 * 60 * 1000) // 15 days ago
    expect(getLastUsedColor(d.toISOString())).toBe('info')
  })

  it('returns warning for date 30-60 days ago', () => {
    const now = new Date()
    const d = new Date(now.getTime() - 45 * 24 * 60 * 60 * 1000) // 45 days ago
    expect(getLastUsedColor(d.toISOString())).toBe('warning')
  })

  it('returns error for date more than 60 days ago', () => {
    const now = new Date()
    const d = new Date(now.getTime() - 90 * 24 * 60 * 60 * 1000) // 90 days ago
    expect(getLastUsedColor(d.toISOString())).toBe('error')
  })

  it('returns default for invalid date string', () => {
    expect(getLastUsedColor('not-a-date')).toBe('default')
  })

  it('active overrides date', () => {
    const oldDate = new Date(Date.now() - 90 * 24 * 60 * 60 * 1000).toISOString()
    expect(getLastUsedColor(oldDate, true)).toBe('success')
  })
})

describe('getLastUsedLabel', () => {
  it('returns active label when active', () => {
    expect(getLastUsedLabel(null, true, 'Active', 'Never')).toBe('Active')
  })

  it('returns never label when no date and not active', () => {
    expect(getLastUsedLabel(null, false, 'Active', 'Never')).toBe('Never')
  })

  it('returns never for undefined date', () => {
    expect(getLastUsedLabel(undefined, false, 'Active', 'Never')).toBe('Never')
  })

  it('formats ISO 8601 date to local string', () => {
    const iso = '2025-06-15T10:30:00Z'
    const result = getLastUsedLabel(iso, false, 'Active', 'Never')
    // Should contain formatted date, not 'Never' or 'Active'
    expect(result).not.toBe('Never')
    expect(result).not.toBe('Active')
  })

  it('returns raw string for non-ISO format', () => {
    const raw = '15/06/2025 10:30:00'
    expect(getLastUsedLabel(raw, false, 'Active', 'Never')).toBe(raw)
  })

  it('active label overrides date', () => {
    expect(getLastUsedLabel('2025-01-01T00:00:00Z', true, 'Active', 'Never')).toBe('Active')
  })
})

function pad(n: number): string {
  return n.toString().padStart(2, '0')
}
