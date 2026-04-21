import { describe, it, expect } from 'vitest'
import type { DatabaseDump, DatabaseSnapshot } from '../types'
import { buildTargetDbName, buildSnapshotTargetDbName, compareTagsDesc } from '../utils/format'

// Container name validation regex from the modal
const CONTAINER_NAME_RE = /^[a-zA-Z0-9][a-zA-Z0-9_.-]*$/

describe('compareTagsDesc', () => {
  it('sorts numeric versions descending', () => {
    const tags = ['14', '15', '16']
    tags.sort(compareTagsDesc)
    expect(tags).toEqual(['16', '15', '14'])
  })

  it('sorts semver-like versions descending', () => {
    const tags = ['1.0.0', '2.1.0', '1.5.3']
    tags.sort(compareTagsDesc)
    expect(tags).toEqual(['2.1.0', '1.5.3', '1.0.0'])
  })

  it('sorts with mixed numeric/string parts', () => {
    const tags = ['16.4', '16.3', 'latest']
    tags.sort(compareTagsDesc)
    // numeric versions sort descending first, 'latest' compares via localeCompare
    expect(tags).toContain('latest')
    expect(tags.indexOf('16.4')).toBeLessThan(tags.indexOf('16.3'))
  })

  it('handles equal tags', () => {
    expect(compareTagsDesc('16', '16')).toBe(0)
  })

  it('handles hyphenated versions', () => {
    const tags = ['1.0-rc1', '1.0-rc2']
    tags.sort(compareTagsDesc)
    // Hyphen splits: parts are ['1','0','rc1'] vs ['1','0','rc2']
    // '1' vs '1' = 0, '0' vs '0' = 0, 'rc1' vs 'rc2' = localeCompare which is < 0
    expect(tags).toEqual(['1.0-rc1', '1.0-rc2'])
  })

  it('handles different part lengths', () => {
    const tags = ['16', '16.4']
    tags.sort(compareTagsDesc)
    // 16.4 > 16 (because 4 > NaN-as-0... but NaN check: Number('') is 0)
    // Actually Number('') is 0, so 16 vs 16 = equal, then 4 vs 0 → 4 wins
    expect(tags[0]).toBe('16.4')
  })
})

describe('migration version hint detection', () => {
  it('detects dump version older than tag', () => {
    // compareTagsDesc > 0 means a is smaller (older) than b
    expect(compareTagsDesc('20.30.2', '20.88.3')).toBeGreaterThan(0)
  })

  it('detects dump version newer than tag (no hint needed)', () => {
    expect(compareTagsDesc('20.88.3', '20.30.2')).toBeLessThan(0)
  })

  it('same version returns zero (no hint needed)', () => {
    expect(compareTagsDesc('20.30.2', '20.30.2')).toBe(0)
  })

  it('detects major version difference', () => {
    expect(compareTagsDesc('14', '16')).toBeGreaterThan(0)
  })

  it('detects minor version difference', () => {
    expect(compareTagsDesc('16.2', '16.4')).toBeGreaterThan(0)
  })
})

describe('containerNameValid', () => {
  it('accepts valid names', () => {
    expect(CONTAINER_NAME_RE.test('my-container')).toBe(true)
    expect(CONTAINER_NAME_RE.test('web_server')).toBe(true)
    expect(CONTAINER_NAME_RE.test('app.v2')).toBe(true)
    expect(CONTAINER_NAME_RE.test('Test123')).toBe(true)
  })

  it('rejects names starting with hyphen', () => {
    expect(CONTAINER_NAME_RE.test('-bad')).toBe(false)
  })

  it('rejects names starting with dot', () => {
    expect(CONTAINER_NAME_RE.test('.bad')).toBe(false)
  })

  it('rejects names with spaces', () => {
    expect(CONTAINER_NAME_RE.test('my container')).toBe(false)
  })

  it('rejects empty string', () => {
    expect(CONTAINER_NAME_RE.test('')).toBe(false)
  })

  it('accepts single character', () => {
    expect(CONTAINER_NAME_RE.test('a')).toBe(true)
  })
})

describe('buildTargetDbName (for restore)', () => {
  it('builds name from dump with all fields', () => {
    const result = buildTargetDbName({
      databaseName: 'mydb',
      version: '2.0',
      uploadedAt: '2025-06-15T10:00:00Z',
    } as DatabaseDump)
    expect(result).toContain('mydb')
    expect(result).toContain('2_0')
    expect(result).toContain('2025-06-15')
  })

  it('handles dump with only filename', () => {
    const result = buildTargetDbName({} as DatabaseDump)
    expect(result).toBe('')
  })
})

describe('env var building', () => {
  // Simulating the env var building logic from NewContainerModal
  function buildEnvVars(
    vars: { key: string; value: string }[],
    dbEnvVar: string | null,
    selectedDb: string | null,
  ): string[] {
    const result = vars
      .filter((v) => v.key.trim())
      .map((v) => `${v.key.trim()}=${v.value}`)

    if (dbEnvVar && selectedDb) {
      const existing = result.findIndex((e) => e.startsWith(dbEnvVar + '='))
      if (existing >= 0) {
        result[existing] = `${dbEnvVar}=${selectedDb}`
      } else {
        result.push(`${dbEnvVar}=${selectedDb}`)
      }
    }

    return result
  }

  it('builds basic env vars', () => {
    const vars = [{ key: 'MY_VAR', value: 'hello' }]
    expect(buildEnvVars(vars, null, null)).toEqual(['MY_VAR=hello'])
  })

  it('appends database env var', () => {
    const vars = [{ key: 'OTHER', value: '1' }]
    expect(buildEnvVars(vars, 'POSTGRES_DB', 'mydb')).toEqual([
      'OTHER=1',
      'POSTGRES_DB=mydb',
    ])
  })

  it('overrides existing database env var', () => {
    const vars = [{ key: 'POSTGRES_DB', value: 'old' }]
    expect(buildEnvVars(vars, 'POSTGRES_DB', 'new')).toEqual(['POSTGRES_DB=new'])
  })

  it('filters empty keys', () => {
    const vars = [{ key: '', value: 'x' }, { key: 'VALID', value: 'y' }]
    expect(buildEnvVars(vars, null, null)).toEqual(['VALID=y'])
  })

  it('trims keys', () => {
    const vars = [{ key: '  MY_KEY  ', value: 'val' }]
    expect(buildEnvVars(vars, null, null)).toEqual(['MY_KEY=val'])
  })

  it('allows empty values', () => {
    const vars = [{ key: 'EMPTY', value: '' }]
    expect(buildEnvVars(vars, null, null)).toEqual(['EMPTY='])
  })
})

describe('expiration calculation', () => {
  it('computes future expiration from minutes', () => {
    const now = Date.now()
    const minutes = 480
    const expiresAt = new Date(now + minutes * 60 * 1000)
    expect(expiresAt.getTime()).toBeGreaterThan(now)
  })

  it('null when expiration disabled', () => {
    const expirationEnabled = false
    const expiresAt = expirationEnabled ? new Date() : null
    expect(expiresAt).toBeNull()
  })
})

describe('memory validation', () => {
  function isMemoryValid(memoryMb: string): boolean {
    if (!memoryMb) return true
    const n = Number(memoryMb)
    return !isNaN(n) && n >= 4 && n <= 65536
  }

  it('empty is valid (optional)', () => {
    expect(isMemoryValid('')).toBe(true)
  })

  it('valid range', () => {
    expect(isMemoryValid('512')).toBe(true)
    expect(isMemoryValid('4')).toBe(true)
    expect(isMemoryValid('65536')).toBe(true)
  })

  it('too low', () => {
    expect(isMemoryValid('2')).toBe(false)
  })

  it('too high', () => {
    expect(isMemoryValid('100000')).toBe(false)
  })

  it('non-numeric', () => {
    expect(isMemoryValid('abc')).toBe(false)
  })
})
