import { useMemo, useRef } from 'react'
import {
  Box, Typography, Button, Paper, Chip, IconButton, Tooltip,
  Table, TableBody, TableCell, TableContainer, TableHead, TableRow,
  TableSortLabel, TablePagination, CircularProgress,
} from '@mui/material'
import { GroupAdd, Edit, Delete } from '@mui/icons-material'
import { useTranslation } from 'react-i18next'
import { useTableHeaderTheme } from '../../hooks/useTableHeaderTheme'
import { useTableSort } from '../../hooks/useTableSort'
import { useTablePagination } from '../../hooks/useTablePagination'
import { useStickyHeader } from '../../hooks/useStickyHeader'
import { formatDate } from '../../utils/format'
import type { Tenant } from '../../services/tenantService'
import { filterTenant, sortTenantValue } from './adminTableUtils'
import AdminTableToolbar from './AdminTableToolbar'
import TenantChip from '../TenantChip'

interface Props {
  tenants: Tenant[]
  loading: boolean
  onCreate: () => void
  onEdit: (tenant: Tenant) => void
  onDelete: (tenant: Tenant) => void
}

export default function TenantsTab({ tenants, loading, onCreate, onEdit, onDelete }: Props) {
  const { t } = useTranslation()
  const { theadBg, theadColor, theadSortSx } = useTableHeaderTheme()

  const { filter, setFilter, sortKey, sortDir, handleSort, sorted } = useTableSort({
    data: tenants, filterFn: filterTenant, sortValueFn: sortTenantValue,
  })
  const pagination = useTablePagination(sorted, { storageKey: 'adminTenants' })
  const tableRef = useRef<HTMLDivElement>(null)
  useStickyHeader(tableRef)

  const columns = useMemo(() => [
    { key: 'name', label: t('tenants.name'), sortable: true },
    { key: 'description', label: t('tenants.description'), sortable: true },
    { key: 'members', label: t('tenants.members'), sortable: true },
    { key: 'createdAt', label: t('tenants.createdAt'), sortable: true },
    { key: 'actions', label: '', sortable: false },
  ], [t])

  return (
    <>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>{t('tenants.hint')}</Typography>
      <AdminTableToolbar
        filter={filter}
        onFilterChange={setFilter}
        placeholder={t('tenants.searchPlaceholder')}
        countLabel={t('tenants.tenantsCount', { count: sorted.length })}
        action={
          <Button variant="contained" color="success" startIcon={<GroupAdd />} size="small" onClick={onCreate}>
            {t('tenants.newTenant')}
          </Button>
        }
      />
      <Paper elevation={2} sx={{ borderRadius: 2 }}>
        <TableContainer ref={tableRef}>
          <Table stickyHeader aria-label="Tenants">
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
              {!loading && pagination.paginatedData.map((tn) => (
                <TableRow key={tn.id} hover>
                  <TableCell sx={{ fontWeight: 600 }}>
                    <TenantChip tenant={tn} fallbackLabel={tn.name} />
                  </TableCell>
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
                        <IconButton size="small" onClick={() => onEdit(tn)}>
                          <Edit fontSize="small" />
                        </IconButton>
                      </Tooltip>
                      <Tooltip title={t('common.delete')}>
                        <IconButton size="small" color="error" onClick={() => onDelete(tn)}>
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
