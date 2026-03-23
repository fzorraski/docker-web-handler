import { describe, it, expect, vi, beforeEach } from 'vitest'
import {
  prepareRunContainer,
  cancelRunContainer,
} from '../services/sseService'

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

describe('sseService — new container', () => {
  // ---- prepareRunContainer ----

  describe('prepareRunContainer', () => {
    it('sends POST and returns ticket', async () => {
      mockFetch.mockReturnValue(jsonResponse({ ticket: 'abc-123' }))

      const body = {
        repository: 'postgres',
        tag: '16',
        containerName: 'my-pg',
        envVars: ['POSTGRES_DB=test'],
      }
      const ticket = await prepareRunContainer(body)

      expect(mockFetch).toHaveBeenCalledWith('/api/containers/sse/run/prepare', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      })
      expect(ticket).toBe('abc-123')
    })

    it('sends full body with optional fields', async () => {
      mockFetch.mockReturnValue(jsonResponse({ ticket: 'xyz' }))

      await prepareRunContainer({
        repository: 'postgres',
        tag: '16',
        containerName: 'my-pg',
        envVars: [],
        expiresAt: '2025-12-31T23:59:00',
        memoryMb: 512,
        databaseName: 'mydb',
        deleteDatabaseOnExpiration: true,
        dumpId: 'dump-123',
        createDatabase: true,
        selectedOptionalScripts: ['init.sql'],
        operationsPassword: 'secret',
        migrationMode: 'API',
        migrationSql: null,
        migrationSourceVersion: '1.0',
        migrationTargetVersion: '2.0',
        webhookNotify: true,
      })

      const sent = JSON.parse(mockFetch.mock.calls[0][1].body)
      expect(sent.repository).toBe('postgres')
      expect(sent.memoryMb).toBe(512)
      expect(sent.deleteDatabaseOnExpiration).toBe(true)
      expect(sent.webhookNotify).toBe(true)
      expect(sent.migrationMode).toBe('API')
    })

    it('throws with error body on failure', async () => {
      mockFetch.mockReturnValue(
        Promise.resolve({
          ok: false,
          statusText: 'Forbidden',
          json: () => Promise.resolve({ error: 'Invalid operations password.' }),
        } as Response),
      )

      await expect(
        prepareRunContainer({ repository: 'pg', tag: '16', containerName: '', envVars: [] }),
      ).rejects.toThrow('Invalid operations password.')
    })

    it('falls back to statusText on parse failure', async () => {
      mockFetch.mockReturnValue(
        Promise.resolve({
          ok: false,
          statusText: 'Bad Request',
          json: () => Promise.reject(new Error('fail')),
        } as Response),
      )

      await expect(
        prepareRunContainer({ repository: 'pg', tag: '16', containerName: '', envVars: [] }),
      ).rejects.toThrow('Bad Request')
    })
  })

  // ---- cancelRunContainer ----

  describe('cancelRunContainer', () => {
    it('sends POST and returns true when cancelled', async () => {
      mockFetch.mockReturnValue(jsonResponse({ cancelled: true }))
      const result = await cancelRunContainer('abc-123')
      expect(mockFetch).toHaveBeenCalledWith('/api/containers/sse/run/cancel/abc-123', {
        method: 'POST',
      })
      expect(result).toBe(true)
    })

    it('returns false when not cancelled', async () => {
      mockFetch.mockReturnValue(jsonResponse({ cancelled: false }))
      expect(await cancelRunContainer('unknown')).toBe(false)
    })

    it('returns false on http error', async () => {
      mockFetch.mockReturnValue(Promise.resolve({ ok: false } as Response))
      expect(await cancelRunContainer('abc')).toBe(false)
    })
  })
})
