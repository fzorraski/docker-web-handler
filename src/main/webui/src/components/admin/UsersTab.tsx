import { useMemo, useRef } from 'react'
import {
  Box, Typography, Button, Paper, Chip, IconButton, Tooltip, Switch,
  Table, TableBody, TableCell, TableContainer, TableHead, TableRow,
  TableSortLabel, TablePagination, CircularProgress, Menu, MenuItem,
  ListItemIcon, ListItemText,
} from '@mui/material'
import { PersonAdd, Edit, Delete, LockReset, Shield, MoreVert } from '@mui/icons-material'
import { useTranslation } from 'react-i18next'
import { useAuth } from '../AuthProvider'
import { useTableHeaderTheme } from '../../hooks/useTableHeaderTheme'
import { useTableSort } from '../../hooks/useTableSort'
import { useTablePagination } from '../../hooks/useTablePagination'
import { useStickyHeader } from '../../hooks/useStickyHeader'
import { useActionMenu } from '../../hooks/useActionMenu'
import { P } from '../../utils/permissions'
import { formatDate } from '../../utils/format'
import type { AppUser } from '../../services/userService'
import type { AppRole } from '../../services/roleService'
import {
  type AdminActor, rolesOf, holdsSystemConfig, canActOnUser, canFullyManage,
  filterUser, sortUserValue,
} from './adminTableUtils'
import AdminTableToolbar from './AdminTableToolbar'
import TenantChip from '../TenantChip'
import { useTenants } from '../../hooks/useTenants'

interface Props {
  users: AppUser[]
  roles: AppRole[]
  loading: boolean
  onCreate: () => void
  onEdit: (user: AppUser) => void
  onResetPassword: (user: AppUser) => void
  onDelete: (user: AppUser) => void
  onToggleEnabled: (user: AppUser) => void
}

export default function UsersTab({ users, roles, loading, onCreate, onEdit, onResetPassword, onDelete, onToggleEnabled }: Props) {
  const { t } = useTranslation()
  const { currentUser, hasPermission } = useAuth()
  const { theadBg, theadColor, theadSortSx } = useTableHeaderTheme()

  const canSystemConfig = hasPermission(P.SYSTEM_CONFIG)
  const canTenantsViewAll = hasPermission(P.TENANTS_VIEW_ALL) || canSystemConfig
  const myTenants = currentUser?.tenants ?? []
  const actor: AdminActor = useMemo(() => ({
    canSystemConfig,
    canTenantsViewAll,
    myPermissions: currentUser?.permissions ?? [],
    myTenantIds: myTenants.map(tn => tn.id),
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }), [canSystemConfig, canTenantsViewAll, currentUser])

  const isSelf = (user: AppUser) => currentUser?.username === user.username

  const { filter, setFilter, sortKey, sortDir, handleSort, sorted } = useTableSort({
    data: users, filterFn: filterUser, sortValueFn: sortUserValue,
  })
  const pagination = useTablePagination(sorted, { storageKey: 'adminUsers' })
  const tableRef = useRef<HTMLDivElement>(null)
  useStickyHeader(tableRef)
  const actionMenu = useActionMenu<AppUser>()
  const tenants = useTenants()

  const columns = useMemo(() => [
    { key: 'username', label: t('users.username'), sortable: true },
    { key: 'roles', label: t('users.roles'), sortable: true },
    { key: 'tenants', label: t('users.tenants'), sortable: true },
    { key: 'enabled', label: t('users.enabled'), sortable: true },
    { key: 'createdAt', label: t('users.createdAt'), sortable: true },
    { key: 'lastLogin', label: t('users.lastLogin'), sortable: true },
    { key: 'actions', label: '', sortable: false },
  ], [t])

  const menuTarget = actionMenu.target
  const menuFullyManageable = menuTarget !== null
    && canActOnUser(menuTarget, roles, actor) && canFullyManage(menuTarget, actor)

  return (
    <>
      <AdminTableToolbar
        filter={filter}
        onFilterChange={setFilter}
        placeholder={t('users.searchPlaceholder')}
        countLabel={t('users.usersCount', { count: sorted.length })}
        action={
          // a scoped admin without tenants cannot create anyone - hide the dead-end button
          (canTenantsViewAll || myTenants.length > 0) ? (
            <Button variant="contained" color="success" startIcon={<PersonAdd />} size="small" onClick={onCreate}>
              {t('users.newUser')}
            </Button>
          ) : undefined
        }
      />
      <Paper elevation={2} sx={{ borderRadius: 2 }}>
        <TableContainer ref={tableRef}>
          <Table stickyHeader aria-label="Users">
            <TableHead>
              <TableRow>
                {columns.map((col) => (
                  <TableCell key={col.key} sx={{ bgcolor: theadBg, color: theadColor, fontWeight: 600 }}>
                    {col.sortable ? (
                      <TableSortLabel
                        active={sortKey === col.key}
                        direction={sortKey === col.key ? sortDir : 'asc'}
                        onClick={() => handleSort(col.key)}
                        sx={theadSortSx}
                      >
                        {col.label}
                      </TableSortLabel>
                    ) : col.label}
                  </TableCell>
                ))}
              </TableRow>
            </TableHead>
            <TableBody>
              {loading && (
                <TableRow>
                  <TableCell colSpan={columns.length} align="center" sx={{ py: 4 }}><CircularProgress size={28} /></TableCell>
                </TableRow>
              )}
              {!loading && sorted.length === 0 && (
                <TableRow>
                  <TableCell colSpan={columns.length} align="center" sx={{ py: 4, color: 'text.secondary' }}>
                    {!canTenantsViewAll && users.length === 0
                      ? (myTenants.length === 0 ? t('users.noTenantAdminHint') : t('users.noUsersInTenant'))
                      : t('common.noResults')}
                  </TableCell>
                </TableRow>
              )}
              {!loading && pagination.paginatedData.map((u) => {
                const actionable = canActOnUser(u, roles, actor)
                const fullyManageable = actionable && canFullyManage(u, actor)
                const superAdmin = holdsSystemConfig(u, roles)
                return (
                  <TableRow
                    key={u.id}
                    hover
                    onContextMenu={(e) => {
                      if (!actionable) return
                      e.preventDefault()
                      actionMenu.openByPosition({ top: e.clientY, left: e.clientX }, u)
                    }}
                  >
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
                        {rolesOf(u, roles).map((r) => (
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
                        {u.tenantIds.length === 0
                          ? <Typography variant="caption" color="text.secondary">{t('users.noTenant')}</Typography>
                          : u.tenantIds.map((id, i) => (
                              <TenantChip key={id} tenant={tenants.get(id)} fallbackLabel={u.tenantNames[i] ?? id} />
                            ))}
                      </Box>
                    </TableCell>
                    <TableCell>
                      <Switch
                        checked={u.enabled}
                        size="small"
                        disabled={!fullyManageable || isSelf(u)}
                        onChange={() => onToggleEnabled(u)}
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
                        <IconButton size="small" onClick={(e) => actionMenu.openByAnchor(e.currentTarget, u)}>
                          <MoreVert fontSize="small" />
                        </IconButton>
                      )}
                    </TableCell>
                  </TableRow>
                )
              })}
            </TableBody>
          </Table>
        </TableContainer>
      </Paper>
      <TablePagination
        component="div"
        count={pagination.totalCount}
        page={pagination.page}
        onPageChange={pagination.handleChangePage}
        rowsPerPage={pagination.rowsPerPage}
        onRowsPerPageChange={pagination.handleChangeRowsPerPage}
        rowsPerPageOptions={pagination.rowsPerPageOptions}
        labelRowsPerPage={t('common.rowsPerPage')}
      />

      <Menu
        open={actionMenu.menuOpen}
        onClose={actionMenu.close}
        anchorEl={actionMenu.anchorEl}
        anchorReference={actionMenu.contextMenuPos ? 'anchorPosition' : 'anchorEl'}
        anchorPosition={actionMenu.contextMenuPos ?? undefined}
        slotProps={actionMenu.menuSlotProps}
      >
        <MenuItem onClick={() => { if (menuTarget) onEdit(menuTarget); actionMenu.close() }}>
          <ListItemIcon><Edit fontSize="small" /></ListItemIcon>
          <ListItemText
            primary={menuFullyManageable ? t('users.editUser') : t('users.membershipOnlyHint')}
            slotProps={{ primary: { noWrap: false, sx: { maxWidth: 280, whiteSpace: 'normal' } } }}
          />
        </MenuItem>
        {menuFullyManageable && (
          <MenuItem onClick={() => { if (menuTarget) onResetPassword(menuTarget); actionMenu.close() }}>
            <ListItemIcon><LockReset fontSize="small" color="warning" /></ListItemIcon>
            <ListItemText primary={t('users.resetPassword')} />
          </MenuItem>
        )}
        {menuFullyManageable && menuTarget && !isSelf(menuTarget) && (
          <MenuItem onClick={() => { onDelete(menuTarget); actionMenu.close() }} sx={{ color: 'error.main' }}>
            <ListItemIcon><Delete fontSize="small" color="error" /></ListItemIcon>
            <ListItemText primary={t('common.delete')} />
          </MenuItem>
        )}
      </Menu>
    </>
  )
}
