import { describe, it, expect, vi, beforeEach } from 'vitest'
import {
  prepareRunContainer,
  cancelRunContainer,
  prepareUpgradeContainer,
  cancelUpgradeContainer,
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

describe('sseService — upgrade container', () => {
  // ---- prepareUpgradeContainer ----

  describe('prepareUpgradeContainer', () => {
    it('sends POST with tag change and returns ticket', async () => {
      mockFetch.mockReturnValue(jsonResponse({ ticket: 'upgrade-123' }))

      const ticket = await prepareUpgradeContainer({
        containerId: 'abc123def4',
        newTag: '20.88.3',
        password: 'secret',
      })

      expect(mockFetch).toHaveBeenCalledWith('/api/containers/sse/upgrade/prepare', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          containerId: 'abc123def4',
          newTag: '20.88.3',
          password: 'secret',
        }),
      })
      expect(ticket).toBe('upgrade-123')
    })

    it('sends migration fields when included', async () => {
      mockFetch.mockReturnValue(jsonResponse({ ticket: 'upgrade-456' }))

      await prepareUpgradeContainer({
        containerId: 'abc123def4',
        newTag: '20.88.3',
        password: 'secret',
        migrationMode: 'API',
        migrationSourceVersion: '20.88.2',
        migrationTargetVersion: '20.88.3',
      })

      const sent = JSON.parse(mockFetch.mock.calls[0][1].body)
      expect(sent.migrationMode).toBe('API')
      expect(sent.migrationSourceVersion).toBe('20.88.2')
      expect(sent.migrationTargetVersion).toBe('20.88.3')
    })

    it('sends null newTag for migration-only', async () => {
      mockFetch.mockReturnValue(jsonResponse({ ticket: 'mig-only' }))

      await prepareUpgradeContainer({
        containerId: 'abc123def4',
        password: 'secret',
        migrationMode: 'MANUAL',
        migrationSql: 'ALTER TABLE...',
      })

      const sent = JSON.parse(mockFetch.mock.calls[0][1].body)
      expect(sent.newTag).toBeUndefined()
      expect(sent.migrationMode).toBe('MANUAL')
      expect(sent.migrationSql).toBe('ALTER TABLE...')
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
        prepareUpgradeContainer({ containerId: 'abc', password: 'wrong' }),
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
        prepareUpgradeContainer({ containerId: 'abc', password: '' }),
      ).rejects.toThrow('Bad Request')
    })
  })

  // ---- cancelUpgradeContainer ----

  describe('cancelUpgradeContainer', () => {
    it('sends POST and returns true when cancelled', async () => {
      mockFetch.mockReturnValue(jsonResponse({ cancelled: true }))
      const result = await cancelUpgradeContainer('upgrade-123')
      expect(mockFetch).toHaveBeenCalledWith('/api/containers/sse/upgrade/cancel/upgrade-123', {
        method: 'POST',
      })
      expect(result).toBe(true)
    })

    it('returns false when not cancelled', async () => {
      mockFetch.mockReturnValue(jsonResponse({ cancelled: false }))
      expect(await cancelUpgradeContainer('unknown')).toBe(false)
    })

    it('returns false on http error', async () => {
      mockFetch.mockReturnValue(Promise.resolve({ ok: false } as Response))
      expect(await cancelUpgradeContainer('abc')).toBe(false)
    })
  })
})
