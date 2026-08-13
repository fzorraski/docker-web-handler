import { describe, it, expect } from 'vitest'
import { resolveTenantLabel, isSystemEntry } from '../utils/auditTenant'

const names = new Map([['t1', { name: 'Process-Team' }], ['t2', { name: 'Support' }]])

describe('audit tenant labelling', () => {
  it('resolves a known tenant id to its name', () => {
    expect(resolveTenantLabel('t1', names, 'System')).toBe('Process-Team')
  })

  it('falls back to the raw id for a tenant that no longer exists', () => {
    expect(resolveTenantLabel('deleted-tenant', names, 'System')).toBe('deleted-tenant')
  })

  it('treats an ABSENT tenantId as a system entry', () => {
    // the regression: the API serialises with JSON-B, which omits null
    // properties, so untenanted entries arrive with no tenantId key at all.
    // A === null check missed this and rendered an empty chip.
    expect(resolveTenantLabel(undefined, names, 'System')).toBe('System')
    expect(isSystemEntry(undefined)).toBe(true)
  })

  it('treats an explicit null tenantId as a system entry', () => {
    expect(resolveTenantLabel(null, names, 'System')).toBe('System')
    expect(isSystemEntry(null)).toBe(true)
  })

  it('treats a blank tenantId as a system entry', () => {
    expect(resolveTenantLabel('', names, 'System')).toBe('System')
    expect(isSystemEntry('')).toBe(true)
  })

  it('does not call a real tenant a system entry', () => {
    expect(isSystemEntry('t1')).toBe(false)
  })
})
