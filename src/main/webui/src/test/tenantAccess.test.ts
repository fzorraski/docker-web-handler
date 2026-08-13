import { describe, it, expect } from 'vitest'
import {
  ALL_OPTION,
  NONE_OPTION,
  defaultTenantSelection,
  isAllSelected,
  reduceTenantSelection,
  splitOwner,
  type TenantAccessRules,
} from '../utils/tenantAccess'

const admin: TenantAccessRules = {
  optionIds: ['a', 'b', 'c'],
  canViewAll: true,
  ownTenantIds: ['a'],
}

const member: TenantAccessRules = {
  optionIds: ['a', 'b'],
  canViewAll: false,
  ownTenantIds: ['a', 'b'],
}

/** MUI appends a clicked value and splices a re-clicked one out. */
function click(current: string[], id: string): string[] {
  const i = current.indexOf(id)
  if (i === -1) return [...current, id]
  const next = [...current]
  next.splice(i, 1)
  return next
}

describe('reduceTenantSelection - ownership', () => {
  it('makes the first tenant clicked the owner and the second shared', () => {
    const one = reduceTenantSelection([], click([], 'b'), admin)
    expect(one).toEqual(['b'])
    expect(reduceTenantSelection(one, click(one, 'a'), admin)).toEqual(['b', 'a'])
  })

  it('never displaces the owner when more tenants are added', () => {
    const result = reduceTenantSelection(['b', 'a'], click(['b', 'a'], 'c'), admin)
    expect(result[0]).toBe('b')
    expect(result).toEqual(['b', 'a', 'c'])
  })

  it('promotes the next tenant when the owner is unticked', () => {
    expect(reduceTenantSelection(['b', 'a', 'c'], click(['b', 'a', 'c'], 'b'), admin))
      .toEqual(['a', 'c'])
  })

  it('leaves the owner in place when a shared tenant is unticked', () => {
    expect(reduceTenantSelection(['b', 'a', 'c'], click(['b', 'a', 'c'], 'a'), admin))
      .toEqual(['b', 'c'])
  })

  it('collapses duplicates', () => {
    expect(reduceTenantSelection(['a', 'a'], ['a', 'a', 'b'], admin)).toEqual(['a', 'b'])
  })
})

describe('reduceTenantSelection - the "none" row', () => {
  it('clears everything for an admin', () => {
    expect(reduceTenantSelection(['b', 'a'], click(['b', 'a'], NONE_OPTION), admin)).toEqual([])
  })

  it('is ignored for a member, who must own what they create', () => {
    expect(reduceTenantSelection(['a'], click(['a'], NONE_OPTION), member)).toEqual(['a'])
  })
})

describe('reduceTenantSelection - the "all tenants" row', () => {
  it('selects every option while preserving the current owner', () => {
    // the regression: naively ticking everything would hand ownership - and
    // with it the right to edit sharing later - to optionIds[0]
    const result = reduceTenantSelection(['b'], click(['b'], ALL_OPTION), admin)
    expect(result[0]).toBe('b')
    expect([...result].sort()).toEqual(['a', 'b', 'c'])
  })

  it('picks an owner from the options when nothing is selected yet', () => {
    expect(reduceTenantSelection([], click([], ALL_OPTION), admin)).toEqual(['a', 'b', 'c'])
  })

  it('toggles back off to nothing for an admin', () => {
    // the sentinel is never part of the value, so a second click arrives as an
    // addition even though its checkbox renders as ticked
    expect(reduceTenantSelection(['b', 'a', 'c'], click(['b', 'a', 'c'], ALL_OPTION), admin))
      .toEqual([])
  })

  it('toggles back off to the first own tenant for a member', () => {
    expect(reduceTenantSelection(['b', 'a'], click(['b', 'a'], ALL_OPTION), member)).toEqual(['a'])
  })
})

describe('reduceTenantSelection - invariants', () => {
  it('never lets a member end up with no tenant', () => {
    expect(reduceTenantSelection(['a'], click(['a'], 'a'), member)).toEqual(['a'])
  })

  it('never lets a sentinel reach the value', () => {
    for (const sentinel of [NONE_OPTION, ALL_OPTION]) {
      const result = reduceTenantSelection(['a'], ['a', 'b', sentinel], admin)
      expect(result).not.toContain(sentinel)
    }
  })
})

describe('splitOwner', () => {
  it('asks for no tenant explicitly when the user was offered that choice', () => {
    // the regression: an omitted tenantId alone reads as "unspecified" to the
    // backend, which then stamps the actor's own first membership - so an
    // admin who is also a tenant member would get their tenant on a dump the
    // UI promised was visible to everyone
    expect(splitOwner([], true)).toEqual({
      tenantId: undefined, sharedWithTenants: [], noTenant: true,
    })
  })

  it('never asks for no tenant when the selector was hidden', () => {
    // a single-tenant member never sees the field; the backend rejects the
    // flag from anyone without TENANTS_VIEW_ALL
    expect(splitOwner([])).toEqual({
      tenantId: undefined, sharedWithTenants: [], noTenant: undefined,
    })
  })

  it('does not set the flag when a tenant is selected', () => {
    expect(splitOwner(['b'], true).noTenant).toBeUndefined()
  })

  it('keeps the owner out of the shared list', () => {
    expect(splitOwner(['b', 'a', 'c'])).toEqual({ tenantId: 'b', sharedWithTenants: ['a', 'c'] })
  })

  it('sends no shared list for a single tenant', () => {
    expect(splitOwner(['b'])).toEqual({ tenantId: 'b', sharedWithTenants: [] })
  })
})

describe('defaultTenantSelection / isAllSelected', () => {
  it('starts an admin with nothing and a member with their first tenant', () => {
    expect(defaultTenantSelection(admin)).toEqual([])
    expect(defaultTenantSelection(member)).toEqual(['a'])
  })

  it('ignores order when checking whether everything is selected', () => {
    expect(isAllSelected(['c', 'a', 'b'], admin)).toBe(true)
    expect(isAllSelected(['a', 'b'], admin)).toBe(false)
    expect(isAllSelected([], admin)).toBe(false)
  })
})
