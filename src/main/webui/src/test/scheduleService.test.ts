import { describe, it, expect, vi, beforeEach } from 'vitest'
import {
  isSchedulingEnabled,
  listSchedules,
  getSchedule,
  getSchedulesByContainer,
  createSchedule,
  updateSchedule,
  toggleSchedule,
  deleteSchedule,
  executeScheduleNow,
} from '../services/scheduleService'

const mockFetch = vi.fn()
global.fetch = mockFetch

function jsonResponse(data: unknown, ok = true) {
  return Promise.resolve({
    ok,
    statusText: ok ? 'OK' : 'Internal Server Error',
    json: () => Promise.resolve(data),
  } as Response)
}

beforeEach(() => {
  mockFetch.mockReset()
})

describe('scheduleService', () => {
  // ---- isSchedulingEnabled ----

  describe('isSchedulingEnabled', () => {
    it('returns true', async () => {
      mockFetch.mockReturnValue(jsonResponse(true))
      expect(await isSchedulingEnabled()).toBe(true)
    })

    it('throws on error', async () => {
      mockFetch.mockReturnValue(jsonResponse({ error: 'fail' }, false))
      await expect(isSchedulingEnabled()).rejects.toThrow()
    })
  })

  // ---- listSchedules ----

  describe('listSchedules', () => {
    it('fetches schedule list', async () => {
      const schedules = [{ id: 'abc', name: 'test', action: 'STOP' }]
      mockFetch.mockReturnValue(jsonResponse(schedules))
      const result = await listSchedules()
      expect(mockFetch).toHaveBeenCalledWith('/api/schedules/list')
      expect(result).toEqual(schedules)
    })
  })

  // ---- getSchedule ----

  describe('getSchedule', () => {
    it('fetches by id', async () => {
      const schedule = { id: 'abc', name: 'test' }
      mockFetch.mockReturnValue(jsonResponse(schedule))
      const result = await getSchedule('abc')
      expect(mockFetch).toHaveBeenCalledWith('/api/schedules/abc')
      expect(result).toEqual(schedule)
    })

    it('encodes id', async () => {
      mockFetch.mockReturnValue(jsonResponse({}))
      await getSchedule('a/b')
      expect(mockFetch).toHaveBeenCalledWith('/api/schedules/a%2Fb')
    })

    it('throws on not found', async () => {
      mockFetch.mockReturnValue(jsonResponse({ error: 'Schedule not found.' }, false))
      await expect(getSchedule('unknown')).rejects.toThrow('Schedule not found.')
    })
  })

  // ---- getSchedulesByContainer ----

  describe('getSchedulesByContainer', () => {
    it('fetches by containerId', async () => {
      mockFetch.mockReturnValue(jsonResponse([{ id: 'abc' }]))
      const result = await getSchedulesByContainer('abc123def4')
      expect(mockFetch).toHaveBeenCalledWith('/api/schedules/container/abc123def4')
      expect(result).toHaveLength(1)
    })
  })

  // ---- createSchedule ----

  describe('createSchedule', () => {
    it('sends POST with full request', async () => {
      const req = {
        name: 'Nightly stop',
        action: 'STOP',
        scheduleType: 'ONE_TIME',
        scheduledAt: '2025-12-31T23:59:00Z',
        containerId: 'abc123def4',
        operationsPassword: 'secret',
      }
      mockFetch.mockReturnValue(jsonResponse({ id: 'new-id', name: 'Nightly stop' }))

      const result = await createSchedule(req)

      expect(mockFetch).toHaveBeenCalledWith('/api/schedules/create', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(req),
      })
      expect(result.name).toBe('Nightly stop')
    })

    it('throws on validation error', async () => {
      mockFetch.mockReturnValue(jsonResponse({ error: 'Invalid action.' }, false))
      await expect(createSchedule({ name: 'x', action: 'BAD', scheduleType: 'ONE_TIME' }))
        .rejects.toThrow('Invalid action.')
    })
  })

  // ---- updateSchedule ----

  describe('updateSchedule', () => {
    it('sends PUT with body', async () => {
      mockFetch.mockReturnValue(jsonResponse({ id: 'abc', name: 'Updated' }))
      const result = await updateSchedule('abc', { name: 'Updated' })

      expect(mockFetch).toHaveBeenCalledWith('/api/schedules/abc', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name: 'Updated' }),
      })
      expect(result.name).toBe('Updated')
    })
  })

  // ---- toggleSchedule ----

  describe('toggleSchedule', () => {
    it('sends POST with password header', async () => {
      mockFetch.mockReturnValue(jsonResponse({ id: 'abc', enabled: false }))
      const result = await toggleSchedule('abc', 'secret')

      expect(mockFetch).toHaveBeenCalledWith('/api/schedules/abc/toggle', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'X-Schedule-Password': 'secret' },
        body: '{}',
      })
      expect(result.enabled).toBe(false)
    })

    it('throws on invalid password', async () => {
      mockFetch.mockReturnValue(jsonResponse({ error: 'Invalid operations password.' }, false))
      await expect(toggleSchedule('abc', 'wrong')).rejects.toThrow('Invalid operations password.')
    })
  })

  // ---- deleteSchedule ----

  describe('deleteSchedule', () => {
    it('sends DELETE with password header', async () => {
      mockFetch.mockReturnValue(jsonResponse({ success: true }))
      const result = await deleteSchedule('abc', 'secret')

      expect(mockFetch).toHaveBeenCalledWith('/api/schedules/abc', {
        method: 'DELETE',
        headers: { 'X-Schedule-Password': 'secret' },
      })
      expect(result.success).toBe(true)
    })

    it('throws on failure', async () => {
      mockFetch.mockReturnValue(jsonResponse({ error: 'Scheduling is disabled.' }, false))
      await expect(deleteSchedule('abc', 'secret')).rejects.toThrow('Scheduling is disabled.')
    })
  })

  // ---- executeScheduleNow ----

  describe('executeScheduleNow', () => {
    it('sends POST with password header', async () => {
      mockFetch.mockReturnValue(jsonResponse({ message: 'Execution triggered.' }))
      const result = await executeScheduleNow('abc', 'secret')

      expect(mockFetch).toHaveBeenCalledWith('/api/schedules/abc/execute-now', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'X-Schedule-Password': 'secret' },
        body: '{}',
      })
      expect(result.message).toBe('Execution triggered.')
    })

    it('throws on disabled schedule', async () => {
      mockFetch.mockReturnValue(jsonResponse({ error: 'Cannot execute a disabled schedule.' }, false))
      await expect(executeScheduleNow('abc', 'secret'))
        .rejects.toThrow('Cannot execute a disabled schedule.')
    })
  })
})
