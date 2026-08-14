/**
 * Permission names, mirroring the backend catalog
 * (br.com.fzdevx.domain.model.auth.Permission). UI gating must use these
 * exact strings; the role editor additionally renders whatever the
 * /api/roles/permissions catalog returns, so unknown future entries still
 * show up there.
 */
export const P = {
  CONTAINERS_VIEW: 'CONTAINERS_VIEW',
  CONTAINERS_OPERATE: 'CONTAINERS_OPERATE',
  CONTAINERS_RUN: 'CONTAINERS_RUN',
  IMAGES_VIEW: 'IMAGES_VIEW',
  IMAGES_MANAGE: 'IMAGES_MANAGE',
  DATABASE_VIEW: 'DATABASE_VIEW',
  DATABASE_OPERATE: 'DATABASE_OPERATE',
  DATABASE_UPLOAD: 'DATABASE_UPLOAD',
  DATABASE_DELETE: 'DATABASE_DELETE',
  DATABASE_DELETE_OWN: 'DATABASE_DELETE_OWN',
  SCHEDULES_VIEW: 'SCHEDULES_VIEW',
  SCHEDULES_MANAGE: 'SCHEDULES_MANAGE',
  TERMINAL_ACCESS: 'TERMINAL_ACCESS',
  LOGS_VIEW: 'LOGS_VIEW',
  LOGS_ANALYZE: 'LOGS_ANALYZE',
  USERS_MANAGE: 'USERS_MANAGE',
  AUDIT_VIEW: 'AUDIT_VIEW',
  AUDIT_LOG_VIEW: 'AUDIT_LOG_VIEW',
  TENANTS_VIEW_ALL: 'TENANTS_VIEW_ALL',
  SYSTEM_CONFIG: 'SYSTEM_CONFIG',
} as const

export type PermissionName = (typeof P)[keyof typeof P]
