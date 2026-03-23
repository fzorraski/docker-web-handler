import { describe, it, expect } from 'vitest'
import type { DatabaseDump, DatabaseSnapshot } from '../types'

// Utility: buildTargetDbName replicated from format.ts (tested standalone here for DB page context)
function buildTargetDbName(dump: Partial<DatabaseDump>): string {
  const parts: string[] = []
  if (dump.databaseName) parts.push(dump.databaseName)
  if (dump.version) parts.push(dump.version)
  if (dump.uploadedAt) {
    const d = new Date(dump.uploadedAt)
    if (!isNaN(d.getTime())) {
      parts.push(d.toISOString().slice(0, 10))
    }
  }
  const raw = parts.length > 0 ? parts.join('_') : ''
  return raw.replace(/[^a-zA-Z0-9_-]/g, '_')
}

// Filter function used in DatabasePage dump tab
function filterDump(dump: DatabaseDump, query: string): boolean {
  const q = query.toLowerCase()
  return dump.originalFilename.toLowerCase().includes(q)
    || (dump.databaseName ?? '').toLowerCase().includes(q)
    || (dump.version ?? '').toLowerCase().includes(q)
    || dump.format.toLowerCase().includes(q)
}

// Filter function used in DatabasePage snapshot tab
function filterSnapshot(snap: DatabaseSnapshot, query: string): boolean {
  const q = query.toLowerCase()
  return snap.repository.toLowerCase().includes(q)
    || snap.sourceDatabaseName.toLowerCase().includes(q)
    || snap.format.toLowerCase().includes(q)
    || (snap.label ?? '').toLowerCase().includes(q)
    || (snap.containerName ?? '').toLowerCase().includes(q)
}

function makeDump(overrides: Partial<DatabaseDump> = {}): DatabaseDump {
  return {
    id: '550e8400-e29b-41d4-a716-446655440000',
    originalFilename: 'backup.sql',
    storedFilename: 'abc.gz',
    uploadedAt: '2025-01-15T10:00:00Z',
    fileSize: 1024 * 1024,
    format: 'SQL',
    ...overrides,
  } as DatabaseDump
}

function makeSnapshot(overrides: Partial<DatabaseSnapshot> = {}): DatabaseSnapshot {
  return {
    id: '660e8400-e29b-41d4-a716-446655440000',
    storedFilename: 'snap.gz',
    repository: 'postgres',
    sourceDatabaseName: 'mydb',
    format: 'CUSTOM',
    createdAt: '2025-01-15T10:00:00Z',
    fileSize: 2048 * 1024,
    label: 'release-2.0',
    ...overrides,
  } as DatabaseSnapshot
}

describe('buildTargetDbName', () => {
  it('combines database, version, and date', () => {
    const result = buildTargetDbName({
      databaseName: 'mydb',
      version: '2.0',
      uploadedAt: '2025-06-15T10:00:00Z',
    })
    expect(result).toBe('mydb_2_0_2025-06-15')
  })

  it('handles missing fields', () => {
    expect(buildTargetDbName({})).toBe('')
  })

  it('only database name', () => {
    expect(buildTargetDbName({ databaseName: 'mydb' })).toBe('mydb')
  })

  it('sanitizes special chars', () => {
    const result = buildTargetDbName({ databaseName: 'my.db@test' })
    expect(result).toBe('my_db_test')
  })
})

describe('filterDump', () => {
  it('matches by filename', () => {
    expect(filterDump(makeDump({ originalFilename: 'backup.sql' }), 'backup')).toBe(true)
  })

  it('matches by databaseName', () => {
    expect(filterDump(makeDump({ databaseName: 'mydb' }), 'mydb')).toBe(true)
  })

  it('matches by version', () => {
    expect(filterDump(makeDump({ version: '2.1.0' }), '2.1')).toBe(true)
  })

  it('matches by format', () => {
    expect(filterDump(makeDump({ format: 'CUSTOM' }), 'custom')).toBe(true)
  })

  it('is case insensitive', () => {
    expect(filterDump(makeDump({ originalFilename: 'BACKUP.SQL' }), 'backup')).toBe(true)
  })

  it('returns false when no match', () => {
    expect(filterDump(makeDump(), 'zzzzz')).toBe(false)
  })

  it('handles null databaseName', () => {
    expect(filterDump(makeDump({ databaseName: undefined }), 'something')).toBe(false)
  })
})

describe('filterSnapshot', () => {
  it('matches by repository', () => {
    expect(filterSnapshot(makeSnapshot({ repository: 'postgres' }), 'post')).toBe(true)
  })

  it('matches by sourceDatabaseName', () => {
    expect(filterSnapshot(makeSnapshot({ sourceDatabaseName: 'mydb' }), 'mydb')).toBe(true)
  })

  it('matches by format', () => {
    expect(filterSnapshot(makeSnapshot({ format: 'SQL' }), 'sql')).toBe(true)
  })

  it('matches by label', () => {
    expect(filterSnapshot(makeSnapshot({ label: 'release-2.0' }), 'release')).toBe(true)
  })

  it('matches by containerName', () => {
    expect(filterSnapshot(makeSnapshot({ containerName: 'my-pg' }), 'my-pg')).toBe(true)
  })

  it('is case insensitive', () => {
    expect(filterSnapshot(makeSnapshot({ repository: 'PostgreSQL' }), 'postgresql')).toBe(true)
  })

  it('returns false when no match', () => {
    expect(filterSnapshot(makeSnapshot(), 'zzzzz')).toBe(false)
  })

  it('handles null label', () => {
    expect(filterSnapshot(makeSnapshot({ label: undefined }), 'test')).toBe(false)
  })
})

describe('storage calculations', () => {
  function calculateStoragePercent(totalBytes: number, maxBytes: number): number {
    if (maxBytes <= 0) return 0
    return Math.min(100, (totalBytes / maxBytes) * 100)
  }

  it('calculates percentage correctly', () => {
    expect(calculateStoragePercent(500, 1000)).toBe(50)
  })

  it('caps at 100%', () => {
    expect(calculateStoragePercent(1500, 1000)).toBe(100)
  })

  it('returns 0 for zero max', () => {
    expect(calculateStoragePercent(500, 0)).toBe(0)
  })

  it('returns 0 for zero total', () => {
    expect(calculateStoragePercent(0, 1000)).toBe(0)
  })
})

describe('dump selection', () => {
  it('selects all dumps', () => {
    const dumps = [makeDump({ id: 'a' }), makeDump({ id: 'b' }), makeDump({ id: 'c' })]
    const selected = new Set(dumps.map(d => d.id))
    expect(selected.size).toBe(3)
  })

  it('toggles selection', () => {
    const selected = new Set(['a', 'b'])
    // Deselect 'a'
    selected.delete('a')
    expect(selected.has('a')).toBe(false)
    expect(selected.has('b')).toBe(true)
  })
})
