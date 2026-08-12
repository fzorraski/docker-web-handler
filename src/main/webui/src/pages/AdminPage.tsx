import { useState, useEffect, useCallback, lazy, Suspense } from 'react'
import {
  Box, Typography, Button, Paper, Chip, IconButton, Tooltip, Switch,
  Table, TableBody, TableCell, TableContainer, TableHead, TableRow,
  Tabs, Tab, CircularProgress,
} from '@mui/material'
import {
  PersonAdd, Edit, Delete, LockReset, AddCircleOutline, Visibility,
  Group, AdminPanelSettings, Tune, Shield, Workspaces, GroupAdd, History,
} from '@mui/icons-material'
import { useTranslation } from 'react-i18next'
import HeroBanner from '../components/HeroBanner'
import { useNotification } from '../components/NotificationProvider'
import { useAuth } from '../components/AuthProvider'
import { useTableHeaderTheme } from '../hooks/useTableHeaderTheme'
import { P } from '../utils/permissions'
import { formatDate } from '../utils/format'
import { listUsers, updateUser, deleteUser, type AppUser } from '../services/userService'
import { listRoles, deleteRole, getPermissionCatalog, type AppRole, type PermissionInfo } from '../services/roleService'
import { listTenantsManage, deleteTenant, type Tenant } from '../services/tenantService'
import UserFormDialog from '../components/admin/UserFormDialog'
import ResetPasswordDialog from '../components/admin/ResetPasswordDialog'
import RoleFormDialog from '../components/admin/RoleFormDialog'
import TenantFormDialog from '../components/admin/TenantFormDialog'

const SettingsTab = lazy(() => import('../components/admin/SettingsTab'))
const AuditTab = lazy(() => import('../components/admin/AuditTab'))

type AdminTab = 'users' | 'roles' | 'tenants' | 'audit' | 'settings'

export default function AdminPage() {
  const { t } = useTranslation()
  const { notify, confirm } = useNotification()
  const { currentUser, hasPermission, refreshUser } = useAuth()
  const { theadBg, theadColor } = useTableHeaderTheme()
  const canSystemConfig = hasPermission(P.SYSTEM_CONFIG)
  const canAuditView = hasPermission(P.AUDIT_LOG_VIEW)
  // an admin without cross-tenant reach only manages members of their own tenants
  const canTenantsViewAll = hasPermission(P.TENANTS_VIEW_ALL) || canSystemConfig
  const myTenants = currentUser?.tenants ?? []

  const [activeTab, setActiveTab] = useState<AdminTab>('users')
  const [users, setUsers] = useState<AppUser[]>([])
  const [roles, setRoles] = useState<AppRole[]>([])
  const [tenants, setTenants] = useState<Tenant[]>([])
  const [catalog, setCatalog] = useState<PermissionInfo[]>([])
  const [loading, setLoading] = useState(true)

  const [userDialogOpen, setUserDialogOpen] = useState(false)
  const [editingUser, setEditingUser] = useState<AppUser | null>(null)
  const [resetTarget, setResetTarget] = useState<AppUser | null>(null)
  const [roleDialogOpen, setRoleDialogOpen] = useState(false)
  const [editingRole, setEditingRole] = useState<AppRole | null>(null)
  const [tenantDialogOpen, setTenantDialogOpen] = useState(false)
  const [editingTenant, setEditingTenant] = useState<Tenant | null>(null)

  const load = useCallback(async () => {
    try {
      // tenant management data is global-admin only; scoped admins use their own memberships
      const [userList, roleList, tenantList] = await Promise.all([
        listUsers(), listRoles(), canTenantsViewAll ? listTenantsManage() : Promise.resolve([]),
      ])
      setUsers(userList)
      setRoles(roleList)
      setTenants(tenantList)
    } catch (e) {
      notify(e instanceof Error ? e.message : t('common.unexpectedError'), 'error')
    } finally {
      setLoading(false)
    }
  }, [notify, t, canTenantsViewAll])

  useEffect(() => { load() }, [load])

  // the permission catalog is a static enum list - fetch it once, not per mutation
  useEffect(() => {
    getPermissionCatalog().then(setCatalog).catch(() => setCatalog([]))
  }, [])

  function rolesOf(user: AppUser): AppRole[] {
    return user.roleIds
      .map(id => roles.find(r => r.id === id))
      .filter((r): r is AppRole => r !== undefined)
  }

  function holdsSystemConfig(user: AppUser): boolean {
    return rolesOf(user).some(r => r.permissions.includes(P.SYSTEM_CONFIG))
  }

  function holdsTenantsViewAll(user: AppUser): boolean {
    return rolesOf(user).some(r => r.permissions.includes(P.TENANTS_VIEW_ALL))
  }

  // acting on a user (or role) that holds SYSTEM_CONFIG requires SYSTEM_CONFIG;
  // global admins (TENANTS_VIEW_ALL) are likewise off-limits to tenant-scoped admins
  function canActOnUser(user: AppUser): boolean {
    return (canSystemConfig || !holdsSystemConfig(user))
      && (canTenantsViewAll || !holdsTenantsViewAll(user))
  }

  // tenant-scoped admins assign roles but never define them; the built-in
  // defaults are a system-level concern, so only a super admin retunes them
  function canActOnRole(role: AppRole): boolean {
    return canTenantsViewAll && (canSystemConfig || !role.builtIn)
      && (canSystemConfig || !role.permissions.includes(P.SYSTEM_CONFIG))
  }

  // account-wide actions (roles, enable/disable, password, delete) on a user who
  // also belongs to a foreign tenant would leak into that tenant - membership only
  function canFullyManage(user: AppUser): boolean {
    return canTenantsViewAll || user.tenantIds.every(id => myTenants.some(tn => tn.id === id))
  }

  const isSelf = (user: AppUser) => currentUser?.username === user.username

  async function handleToggleEnabled(user: AppUser) {
    try {
      await updateUser(user.id, { enabled: !user.enabled })
      notify(t('users.updated'), 'success')
      await load()
    } catch (e) {
      notify(e instanceof Error ? e.message : t('common.unexpectedError'), 'error')
    }
  }

  async function handleDeleteUser(user: AppUser) {
    if (!(await confirm(t('users.confirmDelete', { name: user.username })))) return
    try {
      await deleteUser(user.id)
      notify(t('users.deleted'), 'success')
      await load()
    } catch (e) {
      notify(e instanceof Error ? e.message : t('common.unexpectedError'), 'error')
    }
  }

  async function handleDeleteRole(role: AppRole) {
    if (!(await confirm(t('roles.confirmDelete', { name: role.name })))) return
    try {
      await deleteRole(role.id)
      notify(t('roles.deleted'), 'success')
      await load()
    } catch (e) {
      notify(e instanceof Error ? e.message : t('common.unexpectedError'), 'error')
    }
  }

  async function handleDeleteTenant(tenant: Tenant) {
    if (!(await confirm(t('tenants.confirmDelete', { name: tenant.name })))) return
    try {
      await deleteTenant(tenant.id)
      notify(t('tenants.deleted'), 'success')
      await load()
    } catch (e) {
      // e.g. blocked while users still reference the tenant
      notify(e instanceof Error ? e.message : t('common.unexpectedError'), 'error')
    }
  }

  async function afterTenantSaved() {
    notify(editingTenant ? t('tenants.updated') : t('tenants.created'), 'success')
    await load()
    await refreshUser()
  }

  async function afterUserSaved() {
    notify(editingUser ? t('users.updated') : t('users.created'), 'success')
    await load()
    // role/enabled changes may affect the acting user's own permissions
    await refreshUser()
  }

  async function afterRoleSaved() {
    notify(editingRole ? t('roles.updated') : t('roles.created'), 'success')
    await load()
    await refreshUser()
  }

  return (
    <>
      <HeroBanner linkTo="/" linkLabel={t('hero.exploreContainers')} />

      <Box sx={{ maxWidth: { xs: '95%', md: '90%', lg: '85%' }, mx: 'auto', mt: 5, mb: 4 }}>
        <Typography variant="h4" fontWeight="bold" sx={{ mb: 3 }}>{t('admin.title')}</Typography>

        {/* string values: two tabs are conditional, numeric indices would shift */}
        <Tabs value={activeTab} onChange={(_, v) => setActiveTab(v)} sx={{ mb: 3 }}>
          <Tab value="users" icon={<Group fontSize="small" />} iconPosition="start" label={t('admin.tabs.users')} />
          <Tab value="roles" icon={<AdminPanelSettings fontSize="small" />} iconPosition="start" label={t('admin.tabs.roles')} />
          {canTenantsViewAll && <Tab value="tenants" icon={<Workspaces fontSize="small" />} iconPosition="start" label={t('admin.tabs.tenants')} />}
          {canAuditView && <Tab value="audit" icon={<History fontSize="small" />} iconPosition="start" label={t('admin.tabs.audit')} />}
          {canSystemConfig && <Tab value="settings" icon={<Tune fontSize="small" />} iconPosition="start" label={t('admin.tabs.settings')} />}
        </Tabs>

        {/* ==================== USERS TAB ==================== */}
        {activeTab === 'users' && (
          <>
            {/* a scoped admin without tenants cannot create anyone - hide the dead-end button */}
            {(canTenantsViewAll || myTenants.length > 0) && (
              <Box sx={{ display: 'flex', justifyContent: 'flex-end', mb: 2 }}>
                <Button
                  variant="contained"
                  color="success"
                  startIcon={<PersonAdd />}
                  size="small"
                  onClick={() => { setEditingUser(null); setUserDialogOpen(true) }}
                >
                  {t('users.newUser')}
                </Button>
              </Box>
            )}
            <Paper elevation={2} sx={{ borderRadius: 2 }}>
              <TableContainer>
                <Table aria-label="Users">
                  <TableHead>
                    <TableRow>
                      {[t('users.username'), t('users.roles'), t('users.tenants'), t('users.enabled'), t('users.createdAt'), t('users.lastLogin'), ''].map((label, i) => (
                        <TableCell key={i} sx={{ bgcolor: theadBg, color: theadColor, fontWeight: 600 }}>{label}</TableCell>
                      ))}
                    </TableRow>
                  </TableHead>
                  <TableBody>
                    {loading && (
                      <TableRow>
                        <TableCell colSpan={7} align="center" sx={{ py: 4 }}><CircularProgress size={28} /></TableCell>
                      </TableRow>
                    )}
                    {!loading && users.length === 0 && !canTenantsViewAll && (
                      <TableRow>
                        <TableCell colSpan={7} align="center" sx={{ py: 4, color: 'text.secondary' }}>
                          {myTenants.length === 0 ? t('users.noTenantAdminHint') : t('users.noUsersInTenant')}
                        </TableCell>
                      </TableRow>
                    )}
                    {!loading && users.map((u) => {
                      const actionable = canActOnUser(u)
                      const fullyManageable = actionable && canFullyManage(u)
                      const superAdmin = holdsSystemConfig(u)
                      return (
                        <TableRow key={u.id} hover>
                          <TableCell sx={{ fontWeight: 600, fontFamily: "'JetBrains Mono', monospace", fontSize: '0.85rem' }}>
                            <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
                              {u.username}
                              {isSelf(u) && <Chip label={t('users.you')} size="small" color="primary" variant="outlined" sx={{ height: 18, fontSize: '0.65rem' }} />}
                              {superAdmin && (
                                <Tooltip title={t('users.superAdmin')}>
                                  <Shield sx={{ fontSize: 16, color: 'warning.main' }} />
                                </Tooltip>
                              )}
                            </Box>
                          </TableCell>
                          <TableCell>
                            <Box sx={{ display: 'flex', gap: 0.5, flexWrap: 'wrap' }}>
                              {rolesOf(u).map((r) => (
                                <Chip
                                  key={r.id}
                                  label={r.name}
                                  size="small"
                                  variant="outlined"
                                  color={r.permissions.includes(P.SYSTEM_CONFIG) ? 'warning' : 'default'}
                                />
                              ))}
                            </Box>
                          </TableCell>
                          <TableCell>
                            <Box sx={{ display: 'flex', gap: 0.5, flexWrap: 'wrap' }}>
                              {u.tenantNames.length === 0
                                ? <Typography variant="caption" color="text.secondary">{t('users.noTenant')}</Typography>
                                : u.tenantNames.map((name) => (
                                    <Chip key={name} label={name} size="small" variant="outlined" color="secondary" />
                                  ))}
                            </Box>
                          </TableCell>
                          <TableCell>
                            <Switch
                              checked={u.enabled}
                              size="small"
                              disabled={!fullyManageable || isSelf(u)}
                              onChange={() => handleToggleEnabled(u)}
                            />
                          </TableCell>
                          <TableCell sx={{ fontSize: '0.85rem', color: 'text.secondary', whiteSpace: 'nowrap' }}>
                            {u.createdAt ? formatDate(u.createdAt) : '-'}
                          </TableCell>
                          <TableCell sx={{ fontSize: '0.85rem', color: 'text.secondary', whiteSpace: 'nowrap' }}>
                            {u.lastLoginAt ? formatDate(u.lastLoginAt) : t('users.never')}
                          </TableCell>
                          <TableCell align="right">
                            {actionable && (
                              <Box sx={{ display: 'flex', gap: 0.25, justifyContent: 'flex-end' }}>
                                <Tooltip title={fullyManageable ? t('users.editUser') : t('users.membershipOnlyHint')}>
                                  <IconButton size="small" onClick={() => { setEditingUser(u); setUserDialogOpen(true) }}>
                                    <Edit fontSize="small" />
                                  </IconButton>
                                </Tooltip>
                                {fullyManageable && (
                                  <Tooltip title={t('users.resetPassword')}>
                                    <IconButton size="small" color="warning" onClick={() => setResetTarget(u)}>
                                      <LockReset fontSize="small" />
                                    </IconButton>
                                  </Tooltip>
                                )}
                                {fullyManageable && !isSelf(u) && (
                                  <Tooltip title={t('common.delete')}>
                                    <IconButton size="small" color="error" onClick={() => handleDeleteUser(u)}>
                                      <Delete fontSize="small" />
                                    </IconButton>
                                  </Tooltip>
                                )}
                              </Box>
                            )}
                          </TableCell>
                        </TableRow>
                      )
                    })}
                  </TableBody>
                </Table>
              </TableContainer>
            </Paper>
          </>
        )}

        {/* ==================== ROLES TAB ==================== */}
        {activeTab === 'roles' && (
          <>
            {canTenantsViewAll && (
              <Box sx={{ display: 'flex', justifyContent: 'flex-end', mb: 2 }}>
                <Button
                  variant="contained"
                  color="success"
                  startIcon={<AddCircleOutline />}
                  size="small"
                  onClick={() => { setEditingRole(null); setRoleDialogOpen(true) }}
                >
                  {t('roles.newRole')}
                </Button>
              </Box>
            )}
            <Paper elevation={2} sx={{ borderRadius: 2 }}>
              <TableContainer>
                <Table aria-label="Roles">
                  <TableHead>
                    <TableRow>
                      {[t('roles.name'), t('roles.description'), t('roles.permissions'), t('roles.type'), ''].map((label, i) => (
                        <TableCell key={i} sx={{ bgcolor: theadBg, color: theadColor, fontWeight: 600 }}>{label}</TableCell>
                      ))}
                    </TableRow>
                  </TableHead>
                  <TableBody>
                    {loading && (
                      <TableRow>
                        <TableCell colSpan={5} align="center" sx={{ py: 4 }}><CircularProgress size={28} /></TableCell>
                      </TableRow>
                    )}
                    {!loading && roles.map((r) => {
                      const usedBy = users.filter(u => u.roleIds.includes(r.id)).length
                      const editable = canActOnRole(r)
                      return (
                        <TableRow key={r.id} hover>
                          <TableCell sx={{ fontWeight: 600 }}>
                            <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
                              {r.name}
                              {r.permissions.includes(P.SYSTEM_CONFIG) && (
                                <Tooltip title={t('users.superAdmin')}>
                                  <Shield sx={{ fontSize: 16, color: 'warning.main' }} />
                                </Tooltip>
                              )}
                            </Box>
                          </TableCell>
                          <TableCell sx={{ fontSize: '0.85rem', color: 'text.secondary' }}>{r.description || '-'}</TableCell>
                          <TableCell>
                            <Chip label={t('roles.permissionsCount', { count: r.permissions.length })} size="small" variant="outlined" />
                            {usedBy > 0 && (
                              <Chip label={t('roles.usedBy', { count: usedBy })} size="small" variant="outlined" color="info" sx={{ ml: 0.5 }} />
                            )}
                          </TableCell>
                          <TableCell>
                            <Chip
                              label={r.builtIn ? t('roles.builtIn') : t('roles.custom')}
                              size="small"
                              color={r.builtIn ? 'info' : 'default'}
                              variant="outlined"
                            />
                            {r.builtIn && r.customized && (
                              <Chip label={t('roles.customized')} size="small" variant="outlined" color="warning" sx={{ ml: 0.5 }} />
                            )}
                          </TableCell>
                          <TableCell align="right">
                            <Box sx={{ display: 'flex', gap: 0.25, justifyContent: 'flex-end' }}>
                              {editable ? (
                                <>
                                  <Tooltip title={t('roles.editRole')}>
                                    <IconButton size="small" onClick={() => { setEditingRole(r); setRoleDialogOpen(true) }}>
                                      <Edit fontSize="small" />
                                    </IconButton>
                                  </Tooltip>
                                  {/* built-in roles are editable by a super admin but never deletable */}
                                  <Tooltip title={r.builtIn ? t('roles.builtInUndeletable') : usedBy > 0 ? t('roles.inUse') : t('common.delete')}>
                                    <span>
                                      <IconButton size="small" color="error" disabled={r.builtIn || usedBy > 0} onClick={() => handleDeleteRole(r)}>
                                        <Delete fontSize="small" />
                                      </IconButton>
                                    </span>
                                  </Tooltip>
                                </>
                              ) : (
                                <Tooltip title={t('roles.viewRole')}>
                                  <IconButton size="small" onClick={() => { setEditingRole(r); setRoleDialogOpen(true) }}>
                                    <Visibility fontSize="small" />
                                  </IconButton>
                                </Tooltip>
                              )}
                            </Box>
                          </TableCell>
                        </TableRow>
                      )
                    })}
                  </TableBody>
                </Table>
              </TableContainer>
            </Paper>
          </>
        )}

        {/* ==================== TENANTS TAB ==================== */}
        {canTenantsViewAll && activeTab === 'tenants' && (
          <>
            <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 2, mb: 2 }}>
              <Typography variant="body2" color="text.secondary">{t('tenants.hint')}</Typography>
              <Button
                variant="contained"
                color="success"
                startIcon={<GroupAdd />}
                size="small"
                sx={{ flexShrink: 0 }}
                onClick={() => { setEditingTenant(null); setTenantDialogOpen(true) }}
              >
                {t('tenants.newTenant')}
              </Button>
            </Box>
            <Paper elevation={2} sx={{ borderRadius: 2 }}>
              <TableContainer>
                <Table aria-label="Tenants">
                  <TableHead>
                    <TableRow>
                      {[t('tenants.name'), t('tenants.description'), t('tenants.members'), t('tenants.createdAt'), ''].map((label, i) => (
                        <TableCell key={i} sx={{ bgcolor: theadBg, color: theadColor, fontWeight: 600 }}>{label}</TableCell>
                      ))}
                    </TableRow>
                  </TableHead>
                  <TableBody>
                    {loading && (
                      <TableRow>
                        <TableCell colSpan={5} align="center" sx={{ py: 4 }}><CircularProgress size={28} /></TableCell>
                      </TableRow>
                    )}
                    {!loading && tenants.map((tn) => (
                      <TableRow key={tn.id} hover>
                        <TableCell sx={{ fontWeight: 600 }}>{tn.name}</TableCell>
                        <TableCell sx={{ fontSize: '0.85rem', color: 'text.secondary' }}>{tn.description || '-'}</TableCell>
                        <TableCell>
                          <Chip label={t('tenants.membersCount', { count: tn.memberCount })} size="small" variant="outlined"
                                color={tn.memberCount > 0 ? 'info' : 'default'} />
                        </TableCell>
                        <TableCell sx={{ fontSize: '0.85rem', color: 'text.secondary', whiteSpace: 'nowrap' }}>
                          {tn.createdAt ? formatDate(tn.createdAt) : '-'}
                        </TableCell>
                        <TableCell align="right">
                          <Box sx={{ display: 'flex', gap: 0.25, justifyContent: 'flex-end' }}>
                            <Tooltip title={t('tenants.editTenant')}>
                              <IconButton size="small" onClick={() => { setEditingTenant(tn); setTenantDialogOpen(true) }}>
                                <Edit fontSize="small" />
                              </IconButton>
                            </Tooltip>
                            <Tooltip title={t('common.delete')}>
                              <IconButton size="small" color="error" onClick={() => handleDeleteTenant(tn)}>
                                <Delete fontSize="small" />
                              </IconButton>
                            </Tooltip>
                          </Box>
                        </TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </TableContainer>
            </Paper>
          </>
        )}

        {/* ==================== AUDIT TAB ==================== */}
        {canAuditView && activeTab === 'audit' && (
          <Suspense fallback={<CircularProgress size={28} sx={{ display: 'block', mx: 'auto', my: 4 }} />}>
            <AuditTab />
          </Suspense>
        )}

        {/* ==================== SETTINGS TAB ==================== */}
        {canSystemConfig && activeTab === 'settings' && (
          <Suspense fallback={<CircularProgress size={28} sx={{ display: 'block', mx: 'auto', my: 4 }} />}>
            <SettingsTab />
          </Suspense>
        )}
      </Box>

      <UserFormDialog
        open={userDialogOpen}
        onClose={() => setUserDialogOpen(false)}
        onSaved={afterUserSaved}
        roles={roles}
        tenants={canTenantsViewAll ? tenants : myTenants}
        user={editingUser}
        canSystemConfig={canSystemConfig}
        canTenantsViewAll={canTenantsViewAll}
      />

      <ResetPasswordDialog
        open={resetTarget !== null}
        onClose={() => setResetTarget(null)}
        onDone={() => notify(t('users.passwordReset'), 'success')}
        user={resetTarget}
      />

      <RoleFormDialog
        open={roleDialogOpen}
        onClose={() => setRoleDialogOpen(false)}
        onSaved={afterRoleSaved}
        role={editingRole}
        catalog={catalog}
        canSystemConfig={canSystemConfig}
        readOnly={editingRole !== null && !canActOnRole(editingRole)}
      />

      <TenantFormDialog
        open={tenantDialogOpen}
        onClose={() => setTenantDialogOpen(false)}
        onSaved={afterTenantSaved}
        tenant={editingTenant}
      />
    </>
  )
}
