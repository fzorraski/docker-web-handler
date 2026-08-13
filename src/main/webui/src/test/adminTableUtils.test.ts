import { describe, it, expect } from 'vitest'
import {
  type AdminActor, canActOnUser, canActOnRole, canFullyManage,
  filterUser, sortUserValue, filterRole, sortRoleValue, countUsedBy,
  filterTenant, sortTenantValue,
} from '../components/admin/adminTableUtils'
import type { AppUser } from '../services/userService'
import type { AppRole } from '../services/roleService'
import type { Tenant } from '../services/tenantService'
import { P } from '../utils/permissions'

function user(overrides: Partial<AppUser> = {}): AppUser {
  return {
    id: 'u1', username: 'alice', roleIds: [], roleNames: [], tenantIds: [], tenantNames: [],
    enabled: true, createdAt: null, lastLoginAt: null, ...overrides,
  }
}

function role(overrides: Partial<AppRole> = {}): AppRole {
  return { id: 'r1', name: 'Viewer', description: null, permissions: [], builtIn: false, ...overrides }
}

function tenant(overrides: Partial<Tenant> = {}): Tenant {
  return { id: 't1', name: 'Dev-Team', description: null, color: '#fff', createdAt: null, memberCount: 0, ...overrides }
}

const superAdmin: AdminActor = { canSystemConfig: true, canTenantsViewAll: true, myPermissions: [], myTenantIds: [] }
const scopedAdmin: AdminActor = {
  canSystemConfig: false, canTenantsViewAll: false,
  myPermissions: [P.CONTAINERS_VIEW], myTenantIds: ['t1'],
}

describe('admin permission guards', () => {
  it('scoped admin cannot act on a super admin user', () => {
    const admin = role({ id: 'ra', permissions: [P.SYSTEM_CONFIG] })
    const target = user({ roleIds: ['ra'] })
    expect(canActOnUser(target, [admin], scopedAdmin)).toBe(false)
    expect(canActOnUser(target, [admin], superAdmin)).toBe(true)
  })

  it('scoped admin cannot grant permissions they do not hold', () => {
    const wider = role({ id: 'rw', permissions: [P.CONTAINERS_VIEW, P.DATABASE_UPLOAD] })
    expect(canActOnUser(user({ roleIds: ['rw'] }), [wider], scopedAdmin)).toBe(false)
  })

  it('membership in a foreign tenant blocks full management only', () => {
    const target = user({ tenantIds: ['t1', 't-foreign'] })
    expect(canFullyManage(target, scopedAdmin)).toBe(false)
    expect(canFullyManage(target, superAdmin)).toBe(true)
  })

  it('built-in roles are only editable by a super admin', () => {
    const builtIn = role({ builtIn: true })
    const globalAdmin: AdminActor = { ...scopedAdmin, canTenantsViewAll: true }
    expect(canActOnRole(builtIn, globalAdmin)).toBe(false)
    expect(canActOnRole(builtIn, superAdmin)).toBe(true)
    expect(canActOnRole(role(), scopedAdmin)).toBe(false)
  })
})

describe('users filter and sort', () => {
  it('matches username, role name and tenant name', () => {
    const u = user({ username: 'alice', roleNames: ['Operator'], tenantNames: ['Dev-Team'] })
    expect(filterUser(u, 'ali')).toBe(true)
    expect(filterUser(u, 'opera')).toBe(true)
    expect(filterUser(u, 'dev-t')).toBe(true)
    expect(filterUser(u, 'nope')).toBe(false)
    expect(filterUser(u, '  ')).toBe(true)
  })

  it('sorts dates numerically and missing dates first', () => {
    expect(sortUserValue(user({ lastLoginAt: '2026-01-02T00:00:00Z' }), 'lastLogin'))
      .toBeGreaterThan(sortUserValue(user({ lastLoginAt: null }), 'lastLogin') as number)
  })

  it('sorts enabled as a number', () => {
    expect(sortUserValue(user({ enabled: true }), 'enabled')).toBe(1)
    expect(sortUserValue(user({ enabled: false }), 'enabled')).toBe(0)
  })
})

describe('roles filter and sort', () => {
  it('counts role usage once across all users', () => {
    const counts = countUsedBy([
      user({ id: 'u1', roleIds: ['r1', 'r2'] }),
      user({ id: 'u2', roleIds: ['r1'] }),
    ])
    expect(counts.get('r1')).toBe(2)
    expect(counts.get('r2')).toBe(1)
    expect(counts.get('r3')).toBeUndefined()
  })

  it('sorts by usage through the precomputed map', () => {
    const counts = new Map([['r1', 3]])
    expect(sortRoleValue(role({ id: 'r1' }), 'usedBy', counts)).toBe(3)
    expect(sortRoleValue(role({ id: 'r9' }), 'usedBy', counts)).toBe(0)
  })

  it('filters on name and description', () => {
    expect(filterRole(role({ name: 'Operator' }), 'oper')).toBe(true)
    expect(filterRole(role({ description: 'read only' }), 'read')).toBe(true)
    expect(filterRole(role(), 'nope')).toBe(false)
  })
})

describe('tenants filter and sort', () => {
  it('filters on name and description', () => {
    expect(filterTenant(tenant({ name: 'Dev-Team' }), 'dev')).toBe(true)
    expect(filterTenant(tenant({ description: 'squad' }), 'squa')).toBe(true)
    expect(filterTenant(tenant(), 'nope')).toBe(false)
  })

  it('sorts members numerically', () => {
    expect(sortTenantValue(tenant({ memberCount: 7 }), 'members')).toBe(7)
  })
})
