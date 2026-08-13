import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import {
  isDumpEnabled,
  listDumps,
  deleteDump,
  deleteDumpsBulk,
  getStorageInfo,
  getActiveRestores,
  cancelRestore,
  getDumpRepositories,
  updateDumpExpiration,
  updateDumpMetadata,
  cleanupIdleDumps,
  getPostRestoreScripts,
  uploadDump,
} from '../services/dumpService'

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

describe('dumpService', () => {
  // ---- isDumpEnabled ----

  describe('isDumpEnabled', () => {
    it('returns true when enabled', async () => {
      mockFetch.mockReturnValue(jsonResponse(true))
      expect(await isDumpEnabled()).toBe(true)
    })

    it('returns false on error', async () => {
      mockFetch.mockReturnValue(jsonResponse(null, false))
      expect(await isDumpEnabled()).toBe(false)
    })
  })

  // ---- listDumps ----

  describe('listDumps', () => {
    it('returns dump list', async () => {
      const dumps = [{ id: 'abc', originalFilename: 'backup.sql' }]
      mockFetch.mockReturnValue(jsonResponse(dumps))
      expect(await listDumps()).toEqual(dumps)
    })

    it('returns empty array on error', async () => {
      mockFetch.mockReturnValue(jsonResponse(null, false))
      expect(await listDumps()).toEqual([])
    })
  })

  // ---- deleteDump ----

  describe('deleteDump', () => {
    it('sends DELETE with password header', async () => {
      mockFetch.mockReturnValue(jsonResponse({ success: true }))
      const result = await deleteDump('abc-123', 'secret')

      expect(mockFetch).toHaveBeenCalledWith(
        '/api/database/dumps/delete/abc-123',
        { method: 'DELETE', headers: { 'X-Dump-Password': 'secret' } },
      )
      expect(result.success).toBe(true)
    })

    it('returns error on failure', async () => {
      mockFetch.mockReturnValue(
        Promise.resolve({
          ok: false,
          statusText: 'Forbidden',
          json: () => Promise.resolve({ error: 'Invalid password' }),
        } as Response),
      )
      const result = await deleteDump('abc-123', 'wrong')
      expect(result.success).toBe(false)
      expect(result.error).toBe('Invalid password')
    })
  })

  // ---- deleteDumpsBulk ----

  describe('deleteDumpsBulk', () => {
    it('sends DELETE with JSON body', async () => {
      mockFetch.mockReturnValue(jsonResponse({ success: true, deleted: 2 }))
      const result = await deleteDumpsBulk(['id1', 'id2'], 'secret')

      expect(mockFetch).toHaveBeenCalledWith('/api/database/dumps/delete/bulk', {
        method: 'DELETE',
        headers: { 'Content-Type': 'application/json', 'X-Dump-Password': 'secret' },
        body: JSON.stringify(['id1', 'id2']),
      })
      expect(result.deleted).toBe(2)
    })

    it('returns error on failure', async () => {
      mockFetch.mockReturnValue(
        Promise.resolve({
          ok: false,
          statusText: 'Forbidden',
          json: () => Promise.resolve({ error: 'bad' }),
        } as Response),
      )
      const result = await deleteDumpsBulk(['id1'], 'wrong')
      expect(result.success).toBe(false)
    })
  })

  // ---- getStorageInfo ----

  describe('getStorageInfo', () => {
    it('returns storage info', async () => {
      mockFetch.mockReturnValue(jsonResponse({ totalBytes: 5000, fileCount: 2, maxBytes: 100000 }))
      const result = await getStorageInfo()
      expect(result.totalBytes).toBe(5000)
      expect(result.fileCount).toBe(2)
    })

    it('returns zeros on error', async () => {
      mockFetch.mockReturnValue(jsonResponse(null, false))
      const result = await getStorageInfo()
      expect(result.totalBytes).toBe(0)
    })
  })

  // ---- getActiveRestores ----

  describe('getActiveRestores', () => {
    it('returns active restores', async () => {
      const restores = [{ repository: 'pg', targetDatabase: 'mydb', dumpFilename: 'backup.sql' }]
      mockFetch.mockReturnValue(jsonResponse(restores))
      expect(await getActiveRestores()).toEqual(restores)
    })

    it('returns empty on error', async () => {
      mockFetch.mockReturnValue(jsonResponse(null, false))
      expect(await getActiveRestores()).toEqual([])
    })
  })

  // ---- cancelRestore ----

  describe('cancelRestore', () => {
    it('sends POST with repository and targetDatabase', async () => {
      mockFetch.mockReturnValue(Promise.resolve({ ok: true } as Response))
      const result = await cancelRestore('pg', 'mydb')
      expect(result).toBe(true)
      expect(mockFetch).toHaveBeenCalledWith('/api/database/dumps/restore/cancel', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ repository: 'pg', targetDatabase: 'mydb' }),
      })
    })

    it('returns false on error', async () => {
      mockFetch.mockReturnValue(Promise.resolve({ ok: false } as Response))
      expect(await cancelRestore('pg', 'mydb')).toBe(false)
    })
  })

  // ---- getDumpRepositories ----

  describe('getDumpRepositories', () => {
    it('returns repository list', async () => {
      mockFetch.mockReturnValue(jsonResponse(['postgres', 'mysql']))
      expect(await getDumpRepositories()).toEqual(['postgres', 'mysql'])
    })

    it('returns empty on error', async () => {
      mockFetch.mockReturnValue(jsonResponse(null, false))
      expect(await getDumpRepositories()).toEqual([])
    })
  })

  // ---- updateDumpExpiration ----

  describe('updateDumpExpiration', () => {
    it('sends PUT with expiresAt', async () => {
      mockFetch.mockReturnValue(jsonResponse({ success: true }))
      const result = await updateDumpExpiration('abc', '2025-12-31T23:59:00', 'secret')

      expect(mockFetch).toHaveBeenCalledWith('/api/database/dumps/expiration/abc', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json', 'X-Dump-Password': 'secret' },
        body: JSON.stringify({ expiresAt: '2025-12-31T23:59:00' }),
      })
      expect(result.success).toBe(true)
    })

    it('sends null expiresAt', async () => {
      mockFetch.mockReturnValue(jsonResponse({ success: true }))
      await updateDumpExpiration('abc', null, 'secret')
      const body = JSON.parse(mockFetch.mock.calls[0][1].body)
      expect(body.expiresAt).toBeNull()
    })
  })

  // ---- updateDumpMetadata ----

  describe('updateDumpMetadata', () => {
    it('sends PUT with metadata', async () => {
      mockFetch.mockReturnValue(jsonResponse({ success: true }))
      const result = await updateDumpMetadata('abc', '2.0', 'mydb', 'secret', 'desc')
      expect(result.success).toBe(true)
      const body = JSON.parse(mockFetch.mock.calls[0][1].body)
      expect(body.version).toBe('2.0')
      expect(body.databaseName).toBe('mydb')
      expect(body.description).toBe('desc')
    })
  })

  // ---- cleanupIdleDumps ----

  describe('cleanupIdleDumps', () => {
    it('sends POST with password and minDays', async () => {
      mockFetch.mockReturnValue(jsonResponse({ success: true, deleted: 3 }))
      const result = await cleanupIdleDumps('secret', 30)

      expect(result.success).toBe(true)
      expect(result.deleted).toBe(3)
      const body = JSON.parse(mockFetch.mock.calls[0][1].body)
      expect(body.password).toBe('secret')
      expect(body.minDays).toBe(30)
    })

    it('returns error on failure', async () => {
      mockFetch.mockReturnValue(
        Promise.resolve({
          ok: false,
          statusText: 'Forbidden',
          json: () => Promise.resolve({ error: 'Invalid password' }),
        } as Response),
      )
      const result = await cleanupIdleDumps('wrong', 30)
      expect(result.success).toBe(false)
    })
  })

  // ---- getPostRestoreScripts ----

  describe('getPostRestoreScripts', () => {
    it('fetches scripts with encoded repo', async () => {
      mockFetch.mockReturnValue(jsonResponse({
        enabled: true, mandatory: [{ filename: 'init.sql' }], optional: [], onFailure: 'stop',
      }))
      const result = await getPostRestoreScripts('my/repo')
      expect(mockFetch).toHaveBeenCalledWith(
        '/api/database/dumps/post-restore-scripts?repository=my%2Frepo',
      )
      expect(result.enabled).toBe(true)
    })

    it('returns defaults on error', async () => {
      mockFetch.mockReturnValue(jsonResponse(null, false))
      const result = await getPostRestoreScripts('pg')
      expect(result.enabled).toBe(false)
    })
  })

  // ---- uploadDump ----

  describe('uploadDump', () => {
    // pins the wire format against the backend's parseTenantList, which splits
    // the sharedWithTenants part on commas
    class MockXhr {
      static last: MockXhr | null = null
      upload = { addEventListener: () => {} }
      status = 200
      responseText = '{"id":"dump-1"}'
      body: FormData | null = null
      private listeners: Record<string, () => void> = {}
      addEventListener(type: string, fn: () => void) { this.listeners[type] = fn }
      open() {}
      send(body: FormData) {
        this.body = body
        MockXhr.last = this
        this.listeners.load?.()
      }
    }

    const file = new File(['dump'], 'backup.sql')
    const realXhr = global.XMLHttpRequest

    beforeEach(() => {
      MockXhr.last = null
      global.XMLHttpRequest = MockXhr as unknown as typeof XMLHttpRequest
    })

    // restore it: leaving the stub in place would silently apply to any
    // describe block added after this one
    afterEach(() => {
      global.XMLHttpRequest = realXhr
    })

    it('sends the owning tenant and joins the shared list with commas', async () => {
      await uploadDump(file, 'pw', { tenantId: 'a', sharedWithTenants: ['b', 'c'] })
      const body = MockXhr.last!.body!
      expect(body.get('tenantId')).toBe('a')
      expect(body.get('sharedWithTenants')).toBe('b,c')
      expect(body.has('noTenant')).toBe(false)
    })

    it('omits every tenant field when the selector was never shown', async () => {
      await uploadDump(file, 'pw', { sharedWithTenants: [] })
      const body = MockXhr.last!.body!
      expect(body.has('tenantId')).toBe(false)
      expect(body.has('sharedWithTenants')).toBe(false)
      expect(body.has('noTenant')).toBe(false)
    })

    it('asks for no tenant explicitly when the user chose "visible to everyone"', async () => {
      // an omitted tenantId alone means "unspecified", which the backend
      // answers with the actor's own first membership
      await uploadDump(file, 'pw', { noTenant: true, sharedWithTenants: [] })
      const body = MockXhr.last!.body!
      expect(body.get('noTenant')).toBe('true')
      expect(body.has('tenantId')).toBe(false)
    })
  })
})
