import { describe, it, expect, vi, beforeEach } from 'vitest'
import {
  listSnapshots,
  deleteSnapshot,
  deleteSnapshotsBulk,
  getSnapshotStorageInfo,
  getSnapshotRepositories,
  getActiveSnapshots,
  cancelSnapshot,
  updateSnapshotMetadata,
  updateSnapshotExpiration,
  cleanupIdleSnapshots,
} from '../services/snapshotService'

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

describe('snapshotService', () => {
  // ---- listSnapshots ----

  describe('listSnapshots', () => {
    it('returns snapshot list', async () => {
      const snapshots = [{ id: 'abc', repository: 'pg', sourceDatabaseName: 'mydb' }]
      mockFetch.mockReturnValue(jsonResponse(snapshots))
      expect(await listSnapshots()).toEqual(snapshots)
    })

    it('returns empty on error', async () => {
      mockFetch.mockReturnValue(jsonResponse(null, false))
      expect(await listSnapshots()).toEqual([])
    })
  })

  // ---- deleteSnapshot ----

  describe('deleteSnapshot', () => {
    it('sends DELETE with password header', async () => {
      mockFetch.mockReturnValue(jsonResponse({ success: true }))
      const result = await deleteSnapshot('abc-123', 'secret')

      expect(mockFetch).toHaveBeenCalledWith(
        '/api/database/snapshots/delete/abc-123',
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
      const result = await deleteSnapshot('abc', 'wrong')
      expect(result.success).toBe(false)
      expect(result.error).toBe('Invalid password')
    })
  })

  // ---- deleteSnapshotsBulk ----

  describe('deleteSnapshotsBulk', () => {
    it('sends DELETE with JSON body', async () => {
      mockFetch.mockReturnValue(jsonResponse({ success: true, deleted: 2 }))
      const result = await deleteSnapshotsBulk(['id1', 'id2'], 'secret')

      expect(result.deleted).toBe(2)
      expect(mockFetch).toHaveBeenCalledWith('/api/database/snapshots/delete/bulk', {
        method: 'DELETE',
        headers: { 'Content-Type': 'application/json', 'X-Dump-Password': 'secret' },
        body: JSON.stringify(['id1', 'id2']),
      })
    })

    it('returns error on failure', async () => {
      mockFetch.mockReturnValue(
        Promise.resolve({
          ok: false,
          statusText: 'Forbidden',
          json: () => Promise.resolve({ error: 'bad' }),
        } as Response),
      )
      const result = await deleteSnapshotsBulk(['id1'], 'wrong')
      expect(result.success).toBe(false)
    })
  })

  // ---- getSnapshotStorageInfo ----

  describe('getSnapshotStorageInfo', () => {
    it('returns storage info', async () => {
      mockFetch.mockReturnValue(jsonResponse({ totalBytes: 3000, fileCount: 1, maxBytes: 50000 }))
      const result = await getSnapshotStorageInfo()
      expect(result.totalBytes).toBe(3000)
    })

    it('returns zeros on error', async () => {
      mockFetch.mockReturnValue(jsonResponse(null, false))
      const result = await getSnapshotStorageInfo()
      expect(result.totalBytes).toBe(0)
    })
  })

  // ---- getSnapshotRepositories ----

  describe('getSnapshotRepositories', () => {
    it('returns list', async () => {
      mockFetch.mockReturnValue(jsonResponse(['pg']))
      expect(await getSnapshotRepositories()).toEqual(['pg'])
    })

    it('returns empty on error', async () => {
      mockFetch.mockReturnValue(jsonResponse(null, false))
      expect(await getSnapshotRepositories()).toEqual([])
    })
  })

  // ---- getActiveSnapshots ----

  describe('getActiveSnapshots', () => {
    it('returns active snapshots', async () => {
      const active = [{ repository: 'pg', sourceDatabaseName: 'mydb' }]
      mockFetch.mockReturnValue(jsonResponse(active))
      expect(await getActiveSnapshots()).toEqual(active)
    })

    it('returns empty on error', async () => {
      mockFetch.mockReturnValue(jsonResponse(null, false))
      expect(await getActiveSnapshots()).toEqual([])
    })
  })

  // ---- cancelSnapshot ----

  describe('cancelSnapshot', () => {
    it('sends POST with body', async () => {
      mockFetch.mockReturnValue(Promise.resolve({ ok: true } as Response))
      const result = await cancelSnapshot('pg', 'mydb')
      expect(result).toBe(true)
      expect(mockFetch).toHaveBeenCalledWith('/api/database/snapshots/cancel', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ repository: 'pg', sourceDatabaseName: 'mydb' }),
      })
    })

    it('returns false on error', async () => {
      mockFetch.mockReturnValue(Promise.resolve({ ok: false } as Response))
      expect(await cancelSnapshot('pg', 'mydb')).toBe(false)
    })
  })

  // ---- updateSnapshotMetadata ----

  describe('updateSnapshotMetadata', () => {
    it('sends PUT with label and description', async () => {
      mockFetch.mockReturnValue(jsonResponse({ success: true }))
      const result = await updateSnapshotMetadata('abc', 'new label', 'secret', 'new desc')
      expect(result.success).toBe(true)
      const body = JSON.parse(mockFetch.mock.calls[0][1].body)
      expect(body.label).toBe('new label')
      expect(body.description).toBe('new desc')
    })

    it('returns error on failure', async () => {
      mockFetch.mockReturnValue(
        Promise.resolve({
          ok: false,
          statusText: 'Forbidden',
          json: () => Promise.resolve({ error: 'Invalid password' }),
        } as Response),
      )
      const result = await updateSnapshotMetadata('abc', 'label', 'wrong')
      expect(result.success).toBe(false)
    })
  })

  // ---- updateSnapshotExpiration ----

  describe('updateSnapshotExpiration', () => {
    it('sends PUT with expiresAt', async () => {
      mockFetch.mockReturnValue(jsonResponse({ success: true }))
      const result = await updateSnapshotExpiration('abc', '2025-12-31T23:59:00', 'secret')
      expect(result.success).toBe(true)
    })

    it('sends null expiresAt to remove expiration', async () => {
      mockFetch.mockReturnValue(jsonResponse({ success: true }))
      await updateSnapshotExpiration('abc', null, 'secret')
      const body = JSON.parse(mockFetch.mock.calls[0][1].body)
      expect(body.expiresAt).toBeNull()
    })
  })

  // ---- cleanupIdleSnapshots ----

  describe('cleanupIdleSnapshots', () => {
    it('sends POST with password and minDays', async () => {
      mockFetch.mockReturnValue(jsonResponse({ success: true, deleted: 2 }))
      const result = await cleanupIdleSnapshots('secret', 30)
      expect(result.success).toBe(true)
      expect(result.deleted).toBe(2)
    })

    it('returns error on failure', async () => {
      mockFetch.mockReturnValue(
        Promise.resolve({
          ok: false,
          statusText: 'Forbidden',
          json: () => Promise.resolve({ error: 'Invalid password' }),
        } as Response),
      )
      const result = await cleanupIdleSnapshots('wrong', 30)
      expect(result.success).toBe(false)
    })
  })
})
