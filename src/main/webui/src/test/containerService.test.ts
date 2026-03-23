import { describe, it, expect, vi, beforeEach } from 'vitest'
import {
  getContainers,
  stopContainer,
  startContainer,
  removeContainer,
  getAllowedRepositories,
  getRepositoryTags,
  getRepositoryEnvKeys,
  getDefaultExpirationMinutes,
  getLocale,
  getDatabaseConflicts,
  isDatabaseListingEnabled,
  repositoryHasDatabases,
  getRepositoryDatabases,
  isMigrationEnabled,
  isWebhookEnabled,
  getFeatures,
  isMigrationApiAvailable,
  getMigratedDatabases,
  previewMigration,
  extendExpiration,
  cancelDatabaseDeletion,
  cancelExpiration,
  runContainer,
} from '../services/containerService'

const mockFetch = vi.fn()
global.fetch = mockFetch

function jsonResponse(data: unknown, ok = true, status = 200) {
  return Promise.resolve({
    ok,
    status,
    statusText: ok ? 'OK' : 'Internal Server Error',
    json: () => Promise.resolve(data),
    text: () => Promise.resolve(String(data)),
  } as Response)
}

beforeEach(() => {
  mockFetch.mockReset()
})

describe('containerService', () => {
  // ---- GET endpoints ----

  describe('getContainers', () => {
    it('fetches and returns container list', async () => {
      const containers = [{ containerId: 'abc123def4', names: 'web', status: 'Up 2 hours' }]
      mockFetch.mockReturnValue(jsonResponse(containers))

      const result = await getContainers()

      expect(mockFetch).toHaveBeenCalledWith('/api/containers/list')
      expect(result).toEqual(containers)
    })

    it('throws on non-ok response', async () => {
      mockFetch.mockReturnValue(jsonResponse(null, false))
      await expect(getContainers()).rejects.toThrow('Internal Server Error')
    })
  })

  describe('getAllowedRepositories', () => {
    it('fetches allowed repositories', async () => {
      mockFetch.mockReturnValue(jsonResponse(['postgres', 'redis']))
      const result = await getAllowedRepositories()
      expect(result).toEqual(['postgres', 'redis'])
    })
  })

  describe('getRepositoryTags', () => {
    it('encodes repository name in URL', async () => {
      mockFetch.mockReturnValue(jsonResponse({ state: 1, tags: ['latest'] }))
      await getRepositoryTags('my/repo')
      expect(mockFetch).toHaveBeenCalledWith('/api/containers/repository-tags?repository=my%2Frepo')
    })
  })

  describe('getRepositoryEnvKeys', () => {
    it('returns env key-value pairs', async () => {
      const envKeys = [{ key: 'POSTGRES_DB', value: 'mydb' }]
      mockFetch.mockReturnValue(jsonResponse(envKeys))
      const result = await getRepositoryEnvKeys('postgres')
      expect(result).toEqual(envKeys)
    })
  })

  describe('getDefaultExpirationMinutes', () => {
    it('returns number', async () => {
      mockFetch.mockReturnValue(jsonResponse(30))
      const result = await getDefaultExpirationMinutes()
      expect(result).toBe(30)
    })
  })

  describe('getLocale', () => {
    it('returns text response', async () => {
      mockFetch.mockReturnValue(
        Promise.resolve({ ok: true, text: () => Promise.resolve('pt-BR') } as Response),
      )
      const result = await getLocale()
      expect(result).toBe('pt-BR')
    })
  })

  describe('getDatabaseConflicts', () => {
    it('encodes database name', async () => {
      mockFetch.mockReturnValue(jsonResponse({ inUseByContainers: [] }))
      await getDatabaseConflicts('my_db')
      expect(mockFetch).toHaveBeenCalledWith('/api/containers/database-conflicts?databaseName=my_db')
    })
  })

  describe('isDatabaseListingEnabled', () => {
    it('returns boolean', async () => {
      mockFetch.mockReturnValue(jsonResponse(true))
      expect(await isDatabaseListingEnabled()).toBe(true)
    })
  })

  describe('repositoryHasDatabases', () => {
    it('returns boolean', async () => {
      mockFetch.mockReturnValue(jsonResponse(false))
      expect(await repositoryHasDatabases('redis')).toBe(false)
    })
  })

  describe('getRepositoryDatabases', () => {
    it('fetches databases for repository', async () => {
      mockFetch.mockReturnValue(jsonResponse({ state: 1, databases: ['db1'] }))
      const result = await getRepositoryDatabases('postgres')
      expect(result.databases).toEqual(['db1'])
    })
  })

  describe('isMigrationEnabled', () => {
    it('returns boolean', async () => {
      mockFetch.mockReturnValue(jsonResponse(true))
      expect(await isMigrationEnabled()).toBe(true)
    })
  })

  describe('isWebhookEnabled', () => {
    it('returns boolean', async () => {
      mockFetch.mockReturnValue(jsonResponse(false))
      expect(await isWebhookEnabled()).toBe(false)
    })
  })

  describe('getFeatures', () => {
    it('returns feature flags', async () => {
      const features = {
        memoryLimit: true,
        deletionOnExpiration: true,
        databaseListing: true,
        dump: true,
        migration: false,
        webhook: false,
        terminal: true,
        defaultExpirationMinutes: 30,
        uploadPasswordRequired: true,
        operationsPasswordRequired: true,
        terminalPasswordRequired: false,
      }
      mockFetch.mockReturnValue(jsonResponse(features))
      const result = await getFeatures()
      expect(result).toEqual(features)
      expect(result.terminal).toBe(true)
      expect(result.terminalPasswordRequired).toBe(false)
    })
  })

  describe('isMigrationApiAvailable', () => {
    it('encodes repository', async () => {
      mockFetch.mockReturnValue(jsonResponse(true))
      await isMigrationApiAvailable('my/repo')
      expect(mockFetch).toHaveBeenCalledWith(
        '/api/containers/migration-api-available?repository=my%2Frepo',
      )
    })
  })

  describe('getMigratedDatabases', () => {
    it('returns migrated database list', async () => {
      const dbs = [{ databaseName: 'db1', repository: 'pg', mode: 'API', migratedAt: '2025-01-01' }]
      mockFetch.mockReturnValue(jsonResponse(dbs))
      expect(await getMigratedDatabases()).toEqual(dbs)
    })
  })

  describe('previewMigration', () => {
    it('sends query params', async () => {
      mockFetch.mockReturnValue(jsonResponse({ sql: 'ALTER TABLE...', totalStatements: 1 }))
      const result = await previewMigration('pg', '1.0', '2.0')
      expect(mockFetch).toHaveBeenCalledWith(
        expect.stringContaining('migration-preview?'),
      )
      expect(result.sql).toBe('ALTER TABLE...')
    })

    it('throws with error body on failure', async () => {
      mockFetch.mockReturnValue(
        Promise.resolve({
          ok: false,
          statusText: 'Bad Request',
          json: () => Promise.resolve({ error: 'No migrations found' }),
        } as Response),
      )
      await expect(previewMigration('pg', '1.0', '2.0')).rejects.toThrow('No migrations found')
    })

    it('falls back to statusText when body parse fails', async () => {
      mockFetch.mockReturnValue(
        Promise.resolve({
          ok: false,
          statusText: 'Bad Request',
          json: () => Promise.reject(new Error('parse error')),
        } as Response),
      )
      await expect(previewMigration('pg', '1.0', '2.0')).rejects.toThrow('Bad Request')
    })
  })

  // ---- POST endpoints ----

  describe('stopContainer', () => {
    it('sends POST with containerId', async () => {
      mockFetch.mockReturnValue(jsonResponse(true))
      const result = await stopContainer('abc123def4')
      expect(mockFetch).toHaveBeenCalledWith('/api/containers/stop', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ containerId: 'abc123def4' }),
      })
      expect(result).toBe(true)
    })

    it('returns false when container fails to stop', async () => {
      mockFetch.mockReturnValue(jsonResponse(false))
      expect(await stopContainer('abc123def4')).toBe(false)
    })
  })

  describe('startContainer', () => {
    it('sends POST with containerId', async () => {
      mockFetch.mockReturnValue(jsonResponse(true))
      const result = await startContainer('abc123def4')
      expect(result).toBe(true)
    })
  })

  describe('removeContainer', () => {
    it('sends POST with containerId', async () => {
      mockFetch.mockReturnValue(jsonResponse(true))
      const result = await removeContainer('abc123def4')
      expect(result).toBe(true)
    })
  })

  describe('extendExpiration', () => {
    it('sends minutes as query param', async () => {
      mockFetch.mockReturnValue(jsonResponse(true))
      await extendExpiration('abc123def4', 15)
      expect(mockFetch).toHaveBeenCalledWith(
        '/api/containers/extend-expiration?minutes=15',
        expect.objectContaining({ method: 'POST' }),
      )
    })

    it('defaults to 10 minutes', async () => {
      mockFetch.mockReturnValue(jsonResponse(true))
      await extendExpiration('abc123def4')
      expect(mockFetch).toHaveBeenCalledWith(
        '/api/containers/extend-expiration?minutes=10',
        expect.objectContaining({ method: 'POST' }),
      )
    })
  })

  describe('cancelDatabaseDeletion', () => {
    it('sends POST', async () => {
      mockFetch.mockReturnValue(jsonResponse(true))
      const result = await cancelDatabaseDeletion('abc123def4')
      expect(result).toBe(true)
      expect(mockFetch).toHaveBeenCalledWith(
        '/api/containers/cancel-db-deletion',
        expect.objectContaining({ method: 'POST' }),
      )
    })
  })

  describe('cancelExpiration', () => {
    it('sends POST', async () => {
      mockFetch.mockReturnValue(jsonResponse(true))
      const result = await cancelExpiration('abc123def4')
      expect(result).toBe(true)
    })
  })

  describe('runContainer', () => {
    it('sends full run request', async () => {
      mockFetch.mockReturnValue(jsonResponse({ state: 1 }))
      const result = await runContainer('postgres', '16', 'my-pg', ['POSTGRES_DB=test'])
      expect(mockFetch).toHaveBeenCalledWith('/api/containers/run', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          repository: 'postgres',
          tag: '16',
          containerName: 'my-pg',
          envVars: ['POSTGRES_DB=test'],
          expiresAt: null,
        }),
      })
      expect(result.state).toBe(1)
    })

    it('sends expiresAt when provided', async () => {
      mockFetch.mockReturnValue(jsonResponse({ state: 1 }))
      await runContainer('postgres', '16', 'my-pg', [], '2025-12-31T23:59:00Z')
      const body = JSON.parse(mockFetch.mock.calls[0][1].body)
      expect(body.expiresAt).toBe('2025-12-31T23:59:00Z')
    })
  })
})
