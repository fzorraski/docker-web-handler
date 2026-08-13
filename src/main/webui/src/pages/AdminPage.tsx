import { useState, useEffect, useCallback, useMemo, lazy, Suspense } from 'react'
import { Box, Typography, Tabs, Tab, CircularProgress } from '@mui/material'
import {
  Group, AdminPanelSettings, Tune, Workspaces, History, Insights,
} from '@mui/icons-material'
import { useTranslation } from 'react-i18next'
import HeroBanner from '../components/HeroBanner'
import { useNotification } from '../components/NotificationProvider'
import { useAuth } from '../components/AuthProvider'
import { P } from '../utils/permissions'
import { listUsers, updateUser, deleteUser, type AppUser } from '../services/userService'
import { listRoles, deleteRole, getPermissionCatalog, type AppRole, type PermissionInfo } from '../services/roleService'
import { listTenantsManage, deleteTenant, type Tenant } from '../services/tenantService'
import UserFormDialog from '../components/admin/UserFormDialog'
import ResetPasswordDialog from '../components/admin/ResetPasswordDialog'
import RoleFormDialog from '../components/admin/RoleFormDialog'
import TenantFormDialog from '../components/admin/TenantFormDialog'
import UsersTab from '../components/admin/UsersTab'
import RolesTab from '../components/admin/RolesTab'
import TenantsTab from '../components/admin/TenantsTab'
import { type AdminActor, canActOnRole } from '../components/admin/adminTableUtils'

const SettingsTab = lazy(() => import('../components/admin/SettingsTab'))
const AuditTab = lazy(() => import('../components/admin/AuditTab'))
const ActivityTab = lazy(() => import('../components/admin/ActivityTab'))

type AdminTab = 'users' | 'roles' | 'tenants' | 'audit' | 'activity' | 'settings'

export default function AdminPage() {
  const { t } = useTranslation()
  const { notify, confirm } = useNotification()
  const { currentUser, hasPermission, refreshUser } = useAuth()
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

  const actor: AdminActor = useMemo(() => ({
    canSystemConfig,
    canTenantsViewAll,
    myPermissions: currentUser?.permissions ?? [],
    myTenantIds: myTenants.map(tn => tn.id),
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }), [canSystemConfig, canTenantsViewAll, currentUser])

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
          {canSystemConfig && <Tab value="activity" icon={<Insights fontSize="small" />} iconPosition="start" label={t('admin.tabs.activity')} />}
          {canSystemConfig && <Tab value="settings" icon={<Tune fontSize="small" />} iconPosition="start" label={t('admin.tabs.settings')} />}
        </Tabs>

        {activeTab === 'users' && (
          <UsersTab
            users={users}
            roles={roles}
            loading={loading}
            onCreate={() => { setEditingUser(null); setUserDialogOpen(true) }}
            onEdit={(u) => { setEditingUser(u); setUserDialogOpen(true) }}
            onResetPassword={setResetTarget}
            onDelete={handleDeleteUser}
            onToggleEnabled={handleToggleEnabled}
          />
        )}

        {activeTab === 'roles' && (
          <RolesTab
            roles={roles}
            users={users}
            loading={loading}
            onCreate={() => { setEditingRole(null); setRoleDialogOpen(true) }}
            onEdit={(r) => { setEditingRole(r); setRoleDialogOpen(true) }}
            onDelete={handleDeleteRole}
          />
        )}

        {canTenantsViewAll && activeTab === 'tenants' && (
          <TenantsTab
            tenants={tenants}
            loading={loading}
            onCreate={() => { setEditingTenant(null); setTenantDialogOpen(true) }}
            onEdit={(tn) => { setEditingTenant(tn); setTenantDialogOpen(true) }}
            onDelete={handleDeleteTenant}
          />
        )}

        {canAuditView && activeTab === 'audit' && (
          <Suspense fallback={<CircularProgress size={28} sx={{ display: 'block', mx: 'auto', my: 4 }} />}>
            <AuditTab />
          </Suspense>
        )}

        {canSystemConfig && activeTab === 'activity' && (
          <Suspense fallback={<CircularProgress size={28} sx={{ display: 'block', mx: 'auto', my: 4 }} />}>
            <ActivityTab />
          </Suspense>
        )}

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
        myPermissions={currentUser?.permissions ?? []}
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
        readOnly={editingRole !== null && !canActOnRole(editingRole, actor)}
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
