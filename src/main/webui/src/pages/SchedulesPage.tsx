import { useState, useEffect, useCallback, useMemo } from 'react'
import {
  Box, Typography, Button, TextField, InputAdornment,
  Table, TableBody, TableCell, TableContainer, TableHead, TableRow, TableSortLabel,
  Paper, Chip, IconButton, Tooltip, Switch, CircularProgress, Divider, Checkbox,
  Alert, AlertTitle,
} from '@mui/material'
import {
  Search, AddCircleOutline, Delete, PlayArrow, Stop, Add,
  Schedule, EventRepeat, EventAvailable, CheckCircle, Cancel, Pending,
  FilterList, Clear, Science,
} from '@mui/icons-material'
import { useTranslation } from 'react-i18next'
import { useNotification } from '../components/NotificationProvider'
import HeroBanner from '../components/HeroBanner'
import CreateScheduleModal from '../components/CreateScheduleModal'
import PasswordConfirmDialog from '../components/PasswordConfirmDialog'
import { useTableHeaderTheme } from '../hooks/useTableHeaderTheme'
import {
  listSchedules, toggleSchedule, deleteSchedule, executeScheduleNow,
} from '../services/scheduleService'
import { getContainers } from '../services/containerService'
import { cronToHuman } from '../utils/cronFormat'
import type { ContainerSchedule, DockerContainer } from '../types'

type PendingDelete =
  | { kind: 'single'; id: string; name: string }
  | { kind: 'bulk'; ids: string[] }

export default function SchedulesPage() {
  const { t } = useTranslation()
  const { notify, confirm } = useNotification()
  const { theadBg, theadColor, theadSortSx } = useTableHeaderTheme()

  const [schedules, setSchedules] = useState<ContainerSchedule[]>([])
  const [containers, setContainers] = useState<DockerContainer[]>([])
  const [loading, setLoading] = useState(true)
  const [filter, setFilter] = useState('')
  const [modalOpen, setModalOpen] = useState(false)
  const [sortKey, setSortKey] = useState<string>('')
  const [sortDir, setSortDir] = useState<'asc' | 'desc'>('asc')
  const [actionFilter, setActionFilter] = useState<string | null>(null)
  const [typeFilter, setTypeFilter] = useState<string | null>(null)
  const [enabledFilter, setEnabledFilter] = useState<boolean | null>(null)
  const [statusFilter, setStatusFilter] = useState<string | null>(null)
  const [selected, setSelected] = useState<Set<string>>(new Set())
  const [pendingDelete, setPendingDelete] = useState<PendingDelete | null>(null)

  const loadSchedules = useCallback(() => {
    setLoading(true)
    listSchedules()
      .then(setSchedules)
      .catch(() => setSchedules([]))
      .finally(() => setLoading(false))
  }, [])

  useEffect(() => {
    loadSchedules()
    getContainers().then(setContainers).catch(() => setContainers([]))
  }, [loadSchedules])

  const activeCount = useMemo(() => schedules.filter(s => s.enabled).length, [schedules])
  const recurringCount = useMemo(() => schedules.filter(s => s.scheduleType === 'RECURRING').length, [schedules])
  const oneTimeCount = useMemo(() => schedules.filter(s => s.scheduleType === 'ONE_TIME').length, [schedules])

  const hasActiveFilters = actionFilter !== null || typeFilter !== null || enabledFilter !== null || statusFilter !== null

  function clearAllFilters() {
    setActionFilter(null)
    setTypeFilter(null)
    setEnabledFilter(null)
    setStatusFilter(null)
  }

  const filtered = useMemo(() => {
    const f = filter.toLowerCase()
    let result = schedules.filter(s => {
      if (f && !(
        s.name.toLowerCase().includes(f) ||
        s.action.toLowerCase().includes(f) ||
        (s.containerName || '').toLowerCase().includes(f)
      )) return false
      if (actionFilter && s.action !== actionFilter) return false
      if (typeFilter && s.scheduleType !== typeFilter) return false
      if (enabledFilter !== null && s.enabled !== enabledFilter) return false
      if (statusFilter && s.lastExecutionStatus !== statusFilter) return false
      return true
    })
    if (sortKey) {
      result = [...result].sort((a, b) => {
        const va = String((a as unknown as Record<string, unknown>)[sortKey] ?? '').toLowerCase()
        const vb = String((b as unknown as Record<string, unknown>)[sortKey] ?? '').toLowerCase()
        return sortDir === 'asc' ? va.localeCompare(vb) : vb.localeCompare(va)
      })
    }
    return result
  }, [schedules, filter, actionFilter, typeFilter, enabledFilter, statusFilter, sortKey, sortDir])

  function handleSort(key: string) {
    if (key === 'actions') return
    setSortDir(sortKey === key && sortDir === 'asc' ? 'desc' : 'asc')
    setSortKey(key)
  }

  async function handleToggle(id: string) {
    try {
      const updated = await toggleSchedule(id)
      setSchedules(prev => prev.map(s => s.id === id ? updated : s))
    } catch {
      notify(t('common.unexpectedError'), 'error')
    }
  }

  function handleDeleteClick(id: string, name: string) {
    setPendingDelete({ kind: 'single', id, name })
  }

  function handleBulkDeleteClick() {
    if (selected.size === 0) return
    setPendingDelete({ kind: 'bulk', ids: [...selected] })
  }

  async function handleDeleteConfirm(password: string) {
    if (!pendingDelete) return
    try {
      if (pendingDelete.kind === 'single') {
        await deleteSchedule(pendingDelete.id, password)
        notify(t('schedules.deleted'), 'success')
      } else {
        await Promise.all(pendingDelete.ids.map(id => deleteSchedule(id, password)))
        notify(t('schedules.bulkDeleted', { count: pendingDelete.ids.length }), 'success')
        setSelected(new Set())
      }
      loadSchedules()
    } catch {
      notify(t('common.unexpectedError'), 'error')
      loadSchedules()
    } finally {
      setPendingDelete(null)
    }
  }

  function getDeleteDialogTitle(): string {
    if (!pendingDelete) return ''
    return pendingDelete.kind === 'single'
      ? t('schedules.deleteSchedule')
      : t('schedules.deleteSchedules', { count: pendingDelete.ids.length })
  }

  function getDeleteDialogMessage(): string {
    if (!pendingDelete) return ''
    return pendingDelete.kind === 'single'
      ? t('schedules.confirmDeleteNamed', { name: pendingDelete.name })
      : t('schedules.confirmBulkDelete', { count: pendingDelete.ids.length })
  }

  function toggleSelect(id: string) {
    setSelected(prev => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })
  }

  function toggleSelectAll() {
    if (selected.size === filtered.length) {
      setSelected(new Set())
    } else {
      setSelected(new Set(filtered.map(s => s.id)))
    }
  }

  async function handleExecuteNow(id: string) {
    try {
      await executeScheduleNow(id)
      notify(t('schedules.executionTriggered'), 'success')
      setTimeout(loadSchedules, 2000)
    } catch {
      notify(t('common.unexpectedError'), 'error')
    }
  }

  function getTarget(s: ContainerSchedule): string {
    if (s.action === 'CREATE' && s.createConfig) {
      return `${s.createConfig.repository}:${s.createConfig.tag}`
    }
    return s.containerName || s.containerId || '-'
  }

  function getScheduleDisplay(s: ContainerSchedule): string {
    if (s.scheduleType === 'RECURRING') return cronToHuman(s.cronExpression || '')
    if (s.scheduledAt) return new Date(s.scheduledAt).toLocaleString()
    return '-'
  }

  const statusIcon = (status?: string) => {
    switch (status) {
      case 'SUCCESS': return <CheckCircle fontSize="small" color="success" />
      case 'FAILED': return <Cancel fontSize="small" color="error" />
      case 'SKIPPED': return <Pending fontSize="small" color="warning" />
      default: return null
    }
  }

  return (
    <>
      <HeroBanner linkTo="/" linkLabel={t('hero.exploreContainers')} />

      <Box sx={{ maxWidth: { xs: '95%', md: '90%', lg: '85%' }, mx: 'auto', mt: 5, mb: 4 }}>
        <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', mb: 3 }}>
          <Typography variant="h4" fontWeight="bold">{t('schedules.title')}</Typography>
          <Button
            variant="contained"
            color="success"
            startIcon={<AddCircleOutline />}
            onClick={() => setModalOpen(true)}
          >
            {t('schedules.newSchedule')}
          </Button>
        </Box>

        <Alert severity="info" icon={<Science />} variant="outlined" sx={{ mb: 3 }}>
          <AlertTitle>{t('schedules.experimentalTitle')}</AlertTitle>
          {t('schedules.experimentalMessage')}
        </Alert>

        {!loading && schedules.length > 0 && (
          <Paper elevation={2} sx={{ p: 2.5, mb: 3, borderRadius: 2 }}>
            <Box sx={{ display: 'flex', gap: 4, flexWrap: 'wrap', justifyContent: 'space-around' }}>
              <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5 }}>
                <Schedule color="primary" sx={{ fontSize: 32 }} />
                <Box>
                  <Typography variant="h5" sx={{ fontWeight: 700, lineHeight: 1.2, fontFamily: "'JetBrains Mono', monospace" }}>{schedules.length}</Typography>
                  <Typography sx={{ color: 'text.secondary', textTransform: 'uppercase', letterSpacing: '0.06em', fontSize: '0.65rem', fontWeight: 600 }}>{t('schedules.overview.total')}</Typography>
                </Box>
              </Box>
              <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5 }}>
                <CheckCircle color="success" sx={{ fontSize: 32 }} />
                <Box>
                  <Typography variant="h5" sx={{ fontWeight: 700, lineHeight: 1.2, fontFamily: "'JetBrains Mono', monospace" }}>{activeCount}</Typography>
                  <Typography sx={{ color: 'text.secondary', textTransform: 'uppercase', letterSpacing: '0.06em', fontSize: '0.65rem', fontWeight: 600 }}>{t('schedules.overview.active')}</Typography>
                </Box>
              </Box>
              <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5 }}>
                <EventRepeat color="info" sx={{ fontSize: 32 }} />
                <Box>
                  <Typography variant="h5" sx={{ fontWeight: 700, lineHeight: 1.2, fontFamily: "'JetBrains Mono', monospace" }}>{recurringCount}</Typography>
                  <Typography sx={{ color: 'text.secondary', textTransform: 'uppercase', letterSpacing: '0.06em', fontSize: '0.65rem', fontWeight: 600 }}>{t('schedules.overview.recurring')}</Typography>
                </Box>
              </Box>
              <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5 }}>
                <EventAvailable color="warning" sx={{ fontSize: 32 }} />
                <Box>
                  <Typography variant="h5" sx={{ fontWeight: 700, lineHeight: 1.2, fontFamily: "'JetBrains Mono', monospace" }}>{oneTimeCount}</Typography>
                  <Typography sx={{ color: 'text.secondary', textTransform: 'uppercase', letterSpacing: '0.06em', fontSize: '0.65rem', fontWeight: 600 }}>{t('schedules.overview.oneTime')}</Typography>
                </Box>
              </Box>
            </Box>
          </Paper>
        )}

        <Box sx={{ display: 'flex', gap: 1, mb: 3, alignItems: 'center' }}>
          <TextField
            fullWidth
            placeholder={t('schedules.searchPlaceholder')}
            value={filter}
            onChange={(e) => setFilter(e.target.value)}
            size="small"
            slotProps={{
              input: {
                startAdornment: <InputAdornment position="start"><Search color="action" /></InputAdornment>,
              },
            }}
          />
          {selected.size > 0 && (
            <Button
              variant="contained"
              color="error"
              startIcon={<Delete />}
              onClick={handleBulkDeleteClick}
              size="small"
              sx={{ whiteSpace: 'nowrap' }}
            >
              {t('common.delete')} ({selected.size})
            </Button>
          )}
        </Box>

        {schedules.length > 0 && (
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 3, flexWrap: 'wrap' }}>
            <FilterList fontSize="small" color="action" />

            <Chip
              label={t('schedules.actionStart')}
              size="small"
              icon={<PlayArrow />}
              color={actionFilter === 'START' ? 'success' : 'default'}
              variant={actionFilter === 'START' ? 'filled' : 'outlined'}
              onClick={() => setActionFilter(actionFilter === 'START' ? null : 'START')}
            />
            <Chip
              label={t('schedules.actionStop')}
              size="small"
              icon={<Stop />}
              color={actionFilter === 'STOP' ? 'warning' : 'default'}
              variant={actionFilter === 'STOP' ? 'filled' : 'outlined'}
              onClick={() => setActionFilter(actionFilter === 'STOP' ? null : 'STOP')}
            />
            <Chip
              label={t('schedules.actionRemove')}
              size="small"
              icon={<Delete />}
              color={actionFilter === 'REMOVE' ? 'error' : 'default'}
              variant={actionFilter === 'REMOVE' ? 'filled' : 'outlined'}
              onClick={() => setActionFilter(actionFilter === 'REMOVE' ? null : 'REMOVE')}
            />
            <Chip
              label={t('schedules.actionCreate')}
              size="small"
              icon={<Add />}
              color={actionFilter === 'CREATE' ? 'info' : 'default'}
              variant={actionFilter === 'CREATE' ? 'filled' : 'outlined'}
              onClick={() => setActionFilter(actionFilter === 'CREATE' ? null : 'CREATE')}
            />

            <Divider orientation="vertical" flexItem sx={{ mx: 0.5 }} />

            <Chip
              label={t('schedules.recurring')}
              size="small"
              icon={<EventRepeat />}
              color={typeFilter === 'RECURRING' ? 'info' : 'default'}
              variant={typeFilter === 'RECURRING' ? 'filled' : 'outlined'}
              onClick={() => setTypeFilter(typeFilter === 'RECURRING' ? null : 'RECURRING')}
            />
            <Chip
              label={t('schedules.oneTime')}
              size="small"
              icon={<EventAvailable />}
              color={typeFilter === 'ONE_TIME' ? 'info' : 'default'}
              variant={typeFilter === 'ONE_TIME' ? 'filled' : 'outlined'}
              onClick={() => setTypeFilter(typeFilter === 'ONE_TIME' ? null : 'ONE_TIME')}
            />

            <Divider orientation="vertical" flexItem sx={{ mx: 0.5 }} />

            <Chip
              label={t('schedules.filters.enabled')}
              size="small"
              icon={<CheckCircle />}
              color={enabledFilter === true ? 'success' : 'default'}
              variant={enabledFilter === true ? 'filled' : 'outlined'}
              onClick={() => setEnabledFilter(enabledFilter === true ? null : true)}
            />
            <Chip
              label={t('schedules.filters.disabled')}
              size="small"
              icon={<Cancel />}
              color={enabledFilter === false ? 'error' : 'default'}
              variant={enabledFilter === false ? 'filled' : 'outlined'}
              onClick={() => setEnabledFilter(enabledFilter === false ? null : false)}
            />

            <Divider orientation="vertical" flexItem sx={{ mx: 0.5 }} />

            <Chip
              label={t('schedules.filters.success')}
              size="small"
              icon={<CheckCircle />}
              color={statusFilter === 'SUCCESS' ? 'success' : 'default'}
              variant={statusFilter === 'SUCCESS' ? 'filled' : 'outlined'}
              onClick={() => setStatusFilter(statusFilter === 'SUCCESS' ? null : 'SUCCESS')}
            />
            <Chip
              label={t('schedules.filters.failed')}
              size="small"
              icon={<Cancel />}
              color={statusFilter === 'FAILED' ? 'error' : 'default'}
              variant={statusFilter === 'FAILED' ? 'filled' : 'outlined'}
              onClick={() => setStatusFilter(statusFilter === 'FAILED' ? null : 'FAILED')}
            />
            <Chip
              label={t('schedules.filters.skipped')}
              size="small"
              icon={<Pending />}
              color={statusFilter === 'SKIPPED' ? 'warning' : 'default'}
              variant={statusFilter === 'SKIPPED' ? 'filled' : 'outlined'}
              onClick={() => setStatusFilter(statusFilter === 'SKIPPED' ? null : 'SKIPPED')}
            />

            {hasActiveFilters && (
              <>
                <Divider orientation="vertical" flexItem sx={{ mx: 0.5 }} />
                <Chip
                  label={t('schedules.filters.clearAll')}
                  size="small"
                  icon={<Clear />}
                  color="default"
                  variant="outlined"
                  onClick={clearAllFilters}
                  sx={{ fontWeight: 600 }}
                />
              </>
            )}
          </Box>
        )}

        <TableContainer component={Paper} elevation={2} sx={{ borderRadius: 2, overflowX: 'auto' }}>
          <Table>
            <TableHead>
              <TableRow sx={{ bgcolor: theadBg }}>
                <TableCell padding="checkbox" sx={{ bgcolor: theadBg }}>
                  <Checkbox
                    size="small"
                    checked={filtered.length > 0 && selected.size === filtered.length}
                    indeterminate={selected.size > 0 && selected.size < filtered.length}
                    onChange={toggleSelectAll}
                    sx={{ color: theadColor }}
                  />
                </TableCell>
                {[
                  { key: 'name', label: t('schedules.columns.name') },
                  { key: 'action', label: t('schedules.columns.action') },
                  { key: 'scheduleType', label: t('schedules.columns.type') },
                  { key: 'schedule', label: t('schedules.columns.schedule') },
                  { key: 'target', label: t('schedules.columns.target') },
                  { key: 'enabled', label: t('schedules.columns.enabled') },
                  { key: 'nextExecutionAt', label: t('schedules.columns.nextRun') },
                  { key: 'lastExecutedAt', label: t('schedules.columns.lastRun') },
                  { key: 'status', label: t('schedules.columns.status') },
                  { key: 'actions', label: t('schedules.columns.actions') },
                ].map((col) => (
                  <TableCell key={col.key} sx={{ color: theadColor, fontWeight: 600 }}>
                    {col.key !== 'actions' ? (
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
                  <TableCell colSpan={11} align="center" sx={{ py: 4 }}>
                    <CircularProgress size={28} />
                  </TableCell>
                </TableRow>
              )}
              {!loading && filtered.length === 0 && (
                <TableRow>
                  <TableCell colSpan={11} align="center" sx={{ py: 4, color: 'text.secondary' }}>
                    {t('schedules.noSchedules')}
                  </TableCell>
                </TableRow>
              )}
              {filtered.map((s) => (
                <TableRow key={s.id} hover selected={selected.has(s.id)} sx={{ opacity: s.enabled ? 1 : 0.5 }}>
                  <TableCell padding="checkbox">
                    <Checkbox size="small" checked={selected.has(s.id)} onChange={() => toggleSelect(s.id)} />
                  </TableCell>
                  <TableCell sx={{ fontWeight: 600 }}>{s.name}</TableCell>
                  <TableCell>
                    <Chip
                      label={t(`schedules.action${s.action.charAt(0) + s.action.slice(1).toLowerCase()}` as never)}
                      size="small"
                      color={s.action === 'START' ? 'success' : s.action === 'STOP' ? 'warning' : s.action === 'REMOVE' ? 'error' : 'info'}
                      icon={s.action === 'START' ? <PlayArrow /> : s.action === 'STOP' ? <Stop /> : s.action === 'REMOVE' ? <Delete /> : <Add />}
                      variant="outlined"
                    />
                  </TableCell>
                  <TableCell>
                    <Chip
                      label={s.scheduleType === 'RECURRING' ? t('schedules.recurring') : t('schedules.oneTime')}
                      size="small"
                      color={s.scheduleType === 'RECURRING' ? 'info' : 'default'}
                      variant="outlined"
                    />
                  </TableCell>
                  <TableCell sx={{ fontFamily: "'JetBrains Mono', monospace", fontSize: '0.8rem' }}>
                    {getScheduleDisplay(s)}
                  </TableCell>
                  <TableCell sx={{ fontFamily: "'JetBrains Mono', monospace", fontSize: '0.85rem' }}>
                    {getTarget(s)}
                  </TableCell>
                  <TableCell>
                    <Tooltip title={
                      !s.enabled && s.scheduleType === 'ONE_TIME' && s.lastExecutedAt
                        ? t('schedules.oneTimeAlreadyExecuted')
                        : ''
                    }>
                      <span>
                        <Switch
                          checked={s.enabled}
                          size="small"
                          onChange={() => handleToggle(s.id)}
                          disabled={!s.enabled && s.scheduleType === 'ONE_TIME' && !!s.lastExecutedAt}
                        />
                      </span>
                    </Tooltip>
                  </TableCell>
                  <TableCell sx={{ fontFamily: "'JetBrains Mono', monospace", fontSize: '0.8rem' }}>
                    {s.nextExecutionAt ? new Date(s.nextExecutionAt).toLocaleString() : '-'}
                  </TableCell>
                  <TableCell sx={{ fontFamily: "'JetBrains Mono', monospace", fontSize: '0.8rem' }}>
                    {s.lastExecutedAt ? new Date(s.lastExecutedAt).toLocaleString() : '-'}
                  </TableCell>
                  <TableCell>
                    {s.lastExecutionStatus ? (
                      <Tooltip title={s.lastExecutionMessage || ''}>
                        <Chip
                          icon={statusIcon(s.lastExecutionStatus) || undefined}
                          label={s.lastExecutionStatus}
                          size="small"
                          color={s.lastExecutionStatus === 'SUCCESS' ? 'success' : s.lastExecutionStatus === 'FAILED' ? 'error' : 'default'}
                          variant="outlined"
                        />
                      </Tooltip>
                    ) : (
                      <Typography variant="body2" color="text.secondary">-</Typography>
                    )}
                  </TableCell>
                  <TableCell>
                    <Box sx={{ display: 'flex', gap: 0.25 }}>
                      {s.enabled && (
                        <Tooltip title={t('schedules.executeNow')}>
                          <IconButton size="small" color="primary" onClick={() => handleExecuteNow(s.id)}>
                            <PlayArrow />
                          </IconButton>
                        </Tooltip>
                      )}
                      <Tooltip title={t('common.delete')}>
                        <IconButton size="small" color="error" onClick={() => handleDeleteClick(s.id, s.name)}>
                          <Delete />
                        </IconButton>
                      </Tooltip>
                    </Box>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableContainer>
      </Box>

      <CreateScheduleModal
        open={modalOpen}
        onClose={() => setModalOpen(false)}
        onCreated={loadSchedules}
        containers={containers}
      />

      <PasswordConfirmDialog
        open={pendingDelete !== null}
        title={getDeleteDialogTitle()}
        message={getDeleteDialogMessage()}
        onConfirm={handleDeleteConfirm}
        onClose={() => setPendingDelete(null)}
      />
    </>
  )
}
