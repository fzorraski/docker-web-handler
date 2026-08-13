// Pure helpers behind the admin tables: permission guards (mirroring the
// backend rules) and the filter/sort functions fed to useTableSort. Kept
// framework-free so they can be unit-tested directly.
import type { AppUser } from '../../services/userService'
import type { AppRole } from '../../services/roleService'
import type { Tenant } from '../../services/tenantService'
import { P } from '../../utils/permissions'

/** What the acting admin holds; derived once from useAuth in the components. */
export interface AdminActor {
  canSystemConfig: boolean
  canTenantsViewAll: boolean
  myPermissions: string[]
  myTenantIds: string[]
}

export function rolesOf(user: AppUser, roles: AppRole[]): AppRole[] {
  return user.roleIds
    .map(id => roles.find(r => r.id === id))
    .filter((r): r is AppRole => r !== undefined)
}

export function holdsSystemConfig(user: AppUser, roles: AppRole[]): boolean {
  return rolesOf(user, roles).some(r => r.permissions.includes(P.SYSTEM_CONFIG))
}

function holdsTenantsViewAll(user: AppUser, roles: AppRole[]): boolean {
  return rolesOf(user, roles).some(r => r.permissions.includes(P.TENANTS_VIEW_ALL))
}

// mirrors the backend rule: you can only manage permissions you hold yourself,
// otherwise taking over the account would be an escalation
function holdsOnlyMyPermissions(user: AppUser, roles: AppRole[], actor: AdminActor): boolean {
  if (actor.canSystemConfig) return true
  return rolesOf(user, roles).every(r => r.permissions.every(p => actor.myPermissions.includes(p)))
}

/**
 * Acting on a user that holds SYSTEM_CONFIG requires SYSTEM_CONFIG; global
 * admins (TENANTS_VIEW_ALL) are likewise off-limits to tenant-scoped admins.
 */
export function canActOnUser(user: AppUser, roles: AppRole[], actor: AdminActor): boolean {
  return (actor.canSystemConfig || !holdsSystemConfig(user, roles))
    && (actor.canTenantsViewAll || !holdsTenantsViewAll(user, roles))
    && holdsOnlyMyPermissions(user, roles, actor)
}

/**
 * Tenant-scoped admins assign roles but never define them; the built-in
 * defaults are a system-level concern, so only a super admin retunes them.
 */
export function canActOnRole(role: AppRole, actor: AdminActor): boolean {
  return actor.canTenantsViewAll && (actor.canSystemConfig || !role.builtIn)
    && (actor.canSystemConfig || !role.permissions.includes(P.SYSTEM_CONFIG))
}

/**
 * Account-wide actions (roles, enable/disable, password, delete) on a user who
 * also belongs to a foreign tenant would leak into that tenant - membership only.
 */
export function canFullyManage(user: AppUser, actor: AdminActor): boolean {
  return actor.canTenantsViewAll || user.tenantIds.every(id => actor.myTenantIds.includes(id))
}

// ---- filter / sort ----

const time = (iso: string | null | undefined) => (iso ? new Date(iso).getTime() : 0)

export function filterUser(user: AppUser, query: string): boolean {
  const q = query.trim().toLowerCase()
  if (!q) return true
  return user.username.toLowerCase().includes(q)
    || user.roleNames.some(n => n.toLowerCase().includes(q))
    || user.tenantNames.some(n => n.toLowerCase().includes(q))
}

export function sortUserValue(user: AppUser, key: string): string | number {
  switch (key) {
    case 'username': return user.username.toLowerCase()
    case 'roles': return user.roleNames.join(', ').toLowerCase()
    case 'tenants': return user.tenantNames.join(', ').toLowerCase()
    case 'enabled': return user.enabled ? 1 : 0
    case 'createdAt': return time(user.createdAt)
    case 'lastLogin': return time(user.lastLoginAt)
    default: return ''
  }
}

export function filterRole(role: AppRole, query: string): boolean {
  const q = query.trim().toLowerCase()
  if (!q) return true
  return role.name.toLowerCase().includes(q)
    || (role.description ?? '').toLowerCase().includes(q)
}

/** usedBy comes from a precomputed map - counting users per role during render was O(roles x users). */
export function sortRoleValue(role: AppRole, key: string, usedBy: Map<string, number>): string | number {
  switch (key) {
    case 'name': return role.name.toLowerCase()
    case 'description': return (role.description ?? '').toLowerCase()
    case 'permissions': return role.permissions.length
    case 'usedBy': return usedBy.get(role.id) ?? 0
    case 'type': return role.builtIn ? 0 : 1
    default: return ''
  }
}

export function countUsedBy(users: AppUser[]): Map<string, number> {
  const counts = new Map<string, number>()
  for (const user of users) {
    for (const roleId of user.roleIds) {
      counts.set(roleId, (counts.get(roleId) ?? 0) + 1)
    }
  }
  return counts
}

export function filterTenant(tenant: Tenant, query: string): boolean {
  const q = query.trim().toLowerCase()
  if (!q) return true
  return tenant.name.toLowerCase().includes(q)
    || (tenant.description ?? '').toLowerCase().includes(q)
}

export function sortTenantValue(tenant: Tenant, key: string): string | number {
  switch (key) {
    case 'name': return tenant.name.toLowerCase()
    case 'description': return (tenant.description ?? '').toLowerCase()
    case 'members': return tenant.memberCount
    case 'createdAt': return time(tenant.createdAt)
    default: return ''
  }
}
