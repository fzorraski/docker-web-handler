import { useCallback, useMemo, useRef } from 'react'
import {
  Box, Button, Paper, Chip, IconButton, Tooltip,
  Table, TableBody, TableCell, TableContainer, TableHead, TableRow,
  TableSortLabel, TablePagination, CircularProgress,
} from '@mui/material'
import { AddCircleOutline, Edit, Delete, Visibility, Shield } from '@mui/icons-material'
import { useTranslation } from 'react-i18next'
import { useAuth } from '../AuthProvider'
import { useTableHeaderTheme } from '../../hooks/useTableHeaderTheme'
import { useTableSort } from '../../hooks/useTableSort'
import { useTablePagination } from '../../hooks/useTablePagination'
import { useStickyHeader } from '../../hooks/useStickyHeader'
import { P } from '../../utils/permissions'
import type { AppUser } from '../../services/userService'
import type { AppRole } from '../../services/roleService'
import { type AdminActor, canActOnRole, filterRole, sortRoleValue, countUsedBy } from './adminTableUtils'
import AdminTableToolbar from './AdminTableToolbar'

interface Props {
  roles: AppRole[]
  users: AppUser[]
  loading: boolean
  onCreate: () => void
  onEdit: (role: AppRole) => void
  onDelete: (role: AppRole) => void
}

export default function RolesTab({ roles, users, loading, onCreate, onEdit, onDelete }: Props) {
  const { t } = useTranslation()
  const { currentUser, hasPermission } = useAuth()
  const { theadBg, theadColor, theadSortSx } = useTableHeaderTheme()

  const canSystemConfig = hasPermission(P.SYSTEM_CONFIG)
  const actor: AdminActor = useMemo(() => ({
    canSystemConfig,
    canTenantsViewAll: hasPermission(P.TENANTS_VIEW_ALL) || canSystemConfig,
    myPermissions: currentUser?.permissions ?? [],
    myTenantIds: (currentUser?.tenants ?? []).map(tn => tn.id),
  }), [canSystemConfig, hasPermission, currentUser])

  const usedBy = useMemo(() => countUsedBy(users), [users])
  const sortValueFn = useCallback(
    (role: AppRole, key: string) => sortRoleValue(role, key, usedBy),
    [usedBy],
  )

  const { filter, setFilter, sortKey, sortDir, handleSort, sorted } = useTableSort({
    data: roles, filterFn: filterRole, sortValueFn,
  })
  const pagination = useTablePagination(sorted, { storageKey: 'adminRoles' })
  const tableRef = useRef<HTMLDivElement>(null)
  useStickyHeader(tableRef)

  const columns = useMemo(() => [
    { key: 'name', label: t('roles.name'), sortable: true },
    { key: 'description', label: t('roles.description'), sortable: true },
    { key: 'permissions', label: t('roles.permissions'), sortable: true },
    { key: 'type', label: t('roles.type'), sortable: true },
    { key: 'actions', label: '', sortable: false },
  ], [t])

  return (
    <>
      <AdminTableToolbar
        filter={filter}
        onFilterChange={setFilter}
        placeholder={t('roles.searchPlaceholder')}
        countLabel={t('roles.rolesCount', { count: sorted.length })}
        action={
          actor.canTenantsViewAll ? (
            <Button variant="contained" color="success" startIcon={<AddCircleOutline />} size="small" onClick={onCreate}>
              {t('roles.newRole')}
            </Button>
          ) : undefined
        }
      />
      <Paper elevation={2} sx={{ borderRadius: 2 }}>
        <TableContainer ref={tableRef}>
          <Table stickyHeader aria-label="Roles">
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
                    {t('common.noResults')}
                  </TableCell>
                </TableRow>
              )}
              {!loading && pagination.paginatedData.map((r) => {
                const used = usedBy.get(r.id) ?? 0
                const editable = canActOnRole(r, actor)
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
                      {used > 0 && (
                        <Chip label={t('roles.usedBy', { count: used })} size="small" variant="outlined" color="info" sx={{ ml: 0.5 }} />
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
                              <IconButton size="small" onClick={() => onEdit(r)}>
                                <Edit fontSize="small" />
                              </IconButton>
                            </Tooltip>
                            {/* built-in roles are editable by a super admin but never deletable */}
                            <Tooltip title={r.builtIn ? t('roles.builtInUndeletable') : used > 0 ? t('roles.inUse') : t('common.delete')}>
                              <span>
                                <IconButton size="small" color="error" disabled={r.builtIn || used > 0} onClick={() => onDelete(r)}>
                                  <Delete fontSize="small" />
                                </IconButton>
                              </span>
                            </Tooltip>
                          </>
                        ) : (
                          <Tooltip title={t('roles.viewRole')}>
                            <IconButton size="small" onClick={() => onEdit(r)}>
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
    </>
  )
}
