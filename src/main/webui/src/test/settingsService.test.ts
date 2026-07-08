import { describe, it, expect, vi, beforeEach } from 'vitest'
import { listSettings, updateSettings, resetSetting } from '../services/settingsService'

const mockFetch = vi.fn()
global.fetch = mockFetch

function jsonResponse(data: unknown, ok = true) {
  return Promise.resolve({
    ok,
    statusText: ok ? 'OK' : 'Bad Request',
    json: () => Promise.resolve(data),
  } as Response)
}

const SETTINGS = [
  { key: 'terminalEnabled', type: 'boolean', value: true, defaultValue: false, overridden: true },
  { key: 'terminalMaxSessions', type: 'integer', value: 5, defaultValue: 5, overridden: false },
]

beforeEach(() => {
  mockFetch.mockReset()
})

describe('settingsService', () => {
  it('lists settings', async () => {
    mockFetch.mockReturnValue(jsonResponse(SETTINGS))
    expect(await listSettings()).toEqual(SETTINGS)
    expect(mockFetch).toHaveBeenCalledWith('/api/settings')
  })

  it('updates settings with PUT and returns the new state', async () => {
    mockFetch.mockReturnValue(jsonResponse(SETTINGS))
    const result = await updateSettings({ terminalMaxSessions: 10 })
    expect(mockFetch).toHaveBeenCalledWith('/api/settings', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ terminalMaxSessions: 10 }),
    })
    expect(result).toEqual(SETTINGS)
  })

  it('resets a setting with DELETE', async () => {
    mockFetch.mockReturnValue(jsonResponse(SETTINGS))
    await resetSetting('terminalEnabled')
    expect(mockFetch).toHaveBeenCalledWith('/api/settings/terminalEnabled', { method: 'DELETE' })
  })

  it('throws the backend message on error', async () => {
    mockFetch.mockReturnValue(jsonResponse({ code: 'INVALID_INPUT', message: 'Unknown setting: bogus' }, false))
    await expect(resetSetting('bogus')).rejects.toThrow('Unknown setting: bogus')
  })
})
