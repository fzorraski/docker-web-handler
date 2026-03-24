import { useState, useEffect, useMemo } from 'react'
import {
  Dialog, DialogTitle, DialogContent, DialogActions, Button,
  TextField, Box, Tab, Tabs, Typography, IconButton, Chip,
  Switch, FormControlLabel, ToggleButtonGroup, ToggleButton,
  Table, TableBody, TableCell, TableContainer, TableHead, TableRow,
  Tooltip, Alert,
} from '@mui/material'
import { MobileDateTimePicker } from '@mui/x-date-pickers/MobileDateTimePicker'
import dayjs, { type Dayjs } from 'dayjs'
import { Close, Delete, PlayArrow, Stop, Schedule, Timer, Warning, Lock } from '@mui/icons-material'
import { useTranslation } from 'react-i18next'
import { useNotification } from './NotificationProvider'
import PasswordConfirmDialog from './PasswordConfirmDialog'
import {
  getSchedulesByContainer, createSchedule, toggleSchedule, deleteSchedule, executeScheduleNow,
} from '../services/scheduleService'
import CronExpressionBuilder from './CronExpressionBuilder'
import { cronToHuman } from '../utils/cronFormat'
import type { ContainerSchedule } from '../types'

interface Props {
  open: boolean
  containerId: string
  containerName: string
  expiresAt?: string
  onClose: () => void
  passwordRequired?: boolean
}

export default function QuickScheduleDialog({ open, containerId, containerName, expiresAt, onClose, passwordRequired = true }: Props) {
  const { t } = useTranslation()
  const { notify } = useNotification()
  const [tab, setTab] = useState(0)
  const [schedules, setSchedules] = useState<ContainerSchedule[]>([])
  const [pendingDeleteId, setPendingDeleteId] = useState<string | null>(null)
  const pendingSchedule = pendingDeleteId ? schedules.find(s => s.id === pendingDeleteId) : null

  // Create form state
  const [name, setName] = useState('')
  const [action, setAction] = useState<'START' | 'STOP' | 'REMOVE'>('START')
  const [scheduleType, setScheduleType] = useState<'ONE_TIME' | 'RECURRING'>('ONE_TIME')
  const [cronExpression, setCronExpression] = useState('0 8 * * 1-5')
  const [scheduledAt, setScheduledAt] = useState<Dayjs | null>(dayjs().add(1, 'hour'))
  const [pendingCreate, setPendingCreate] = useState(false)

  useEffect(() => {
    if (open) {
      loadSchedules()
      setName('')
      setAction('START')
      setScheduleType('ONE_TIME')
      setCronExpression('0 8 * * 1-5')
      setScheduledAt(dayjs().add(1, 'hour'))
      setPendingCreate(false)
    }
  }, [open, containerId])

  function loadSchedules() {
    getSchedulesByContainer(containerId).then(setSchedules).catch(() => setSchedules([]))
  }

  const conflictWarning = useMemo(() => {
    const enabled = schedules.filter(s => s.enabled)
    const hasRemove = enabled.some(s => s.action === 'REMOVE')
    if (hasRemove) return t('schedules.conflict.removeExists')
    if (action === 'REMOVE' && enabled.length > 0) {
      const names = enabled.map(s => `${s.name} (${s.action})`).join(', ')
      return t('schedules.conflict.removeBlocked', { schedules: names })
    }
    if (enabled.some(s => s.action === action)) {
      return t('schedules.conflict.duplicateAction', { action: t(`schedules.action${action.charAt(0) + action.slice(1).toLowerCase()}` as never) })
    }
    return null
  }, [schedules, action, t])

  function handleCreate() {
    if (!name.trim()) {
      notify(t('schedules.nameRequired'), 'warning')
      return
    }
    if (!passwordRequired) { handleCreateConfirm(''); return }
    setPendingCreate(true)
  }

  async function handleCreateConfirm(password: string) {
    try {
      await createSchedule({
        name: name.trim(),
        action,
        scheduleType,
        cronExpression: scheduleType === 'RECURRING' ? cronExpression : undefined,
        scheduledAt: scheduleType === 'ONE_TIME' && scheduledAt ? scheduledAt.toISOString() : undefined,
        containerId,
        containerName,
        operationsPassword: password || undefined,
      })
      notify(t('schedules.created'), 'success')
      loadSchedules()
      setTab(1)
    } catch (e: unknown) {
      notify((e as Error).message || t('common.unexpectedError'), 'error')
    } finally {
      setPendingCreate(false)
    }
  }

  const [pendingToggleId, setPendingToggleId] = useState<string | null>(null)
  const pendingToggleSchedule = pendingToggleId ? schedules.find(s => s.id === pendingToggleId) : null

  async function handleToggleConfirm(password: string) {
    if (!pendingToggleId) return
    try {
      const updated = await toggleSchedule(pendingToggleId, password)
      setSchedules(prev => prev.map(s => s.id === pendingToggleId ? updated : s))
    } catch {
      notify(t('common.unexpectedError'), 'error')
    } finally {
      setPendingToggleId(null)
    }
  }

  async function requestToggle(id: string) {
    if (!passwordRequired) {
      try {
        const updated = await toggleSchedule(id, '')
        setSchedules(prev => prev.map(s => s.id === id ? updated : s))
      } catch { notify(t('common.unexpectedError'), 'error') }
    } else { setPendingToggleId(id) }
  }

  async function handleDeleteConfirm(password: string) {
    if (!pendingDeleteId) return
    try {
      await deleteSchedule(pendingDeleteId, password)
      notify(t('schedules.deleted'), 'success')
      loadSchedules()
    } catch {
      notify(t('common.unexpectedError'), 'error')
    } finally {
      setPendingDeleteId(null)
    }
  }

  async function requestDelete(id: string) {
    if (!passwordRequired) {
      try { await deleteSchedule(id, ''); notify(t('schedules.deleted'), 'success'); loadSchedules() }
      catch { notify(t('common.unexpectedError'), 'error') }
    } else { setPendingDeleteId(id) }
  }

  const [pendingExecId, setPendingExecId] = useState<string | null>(null)
  const pendingExecSchedule = pendingExecId ? schedules.find(s => s.id === pendingExecId) : null

  async function handleExecuteNowConfirm(password: string) {
    if (!pendingExecId) return
    try {
      await executeScheduleNow(pendingExecId, password)
      notify(t('schedules.executionTriggered'), 'success')
    } catch {
      notify(t('common.unexpectedError'), 'error')
    } finally {
      setPendingExecId(null)
    }
  }

  async function requestExecNow(id: string) {
    if (!passwordRequired) {
      try { await executeScheduleNow(id, ''); notify(t('schedules.executionTriggered'), 'success') }
      catch { notify(t('common.unexpectedError'), 'error') }
    } else { setPendingExecId(id) }
  }

  return (
    <Dialog open={open} onClose={onClose} maxWidth="md" fullWidth>
      <DialogTitle sx={{ display: 'flex', alignItems: 'center' }}>
        <Schedule sx={{ mr: 1 }} /> {t('schedules.quickTitle', { name: containerName })}
        <IconButton onClick={onClose} sx={{ ml: 'auto' }}>
          <Close />
        </IconButton>
      </DialogTitle>
      <DialogContent dividers>
        {expiresAt && (
          <Alert severity="warning" icon={<Timer />} sx={{ mb: 2 }}>
            {t('schedules.containerExpirationWarning', { date: dayjs(expiresAt).format('DD/MM/YYYY HH:mm') })}
          </Alert>
        )}
        <Tabs value={tab} onChange={(_e, v) => setTab(v)} sx={{ mb: 2 }}>
          <Tab label={t('schedules.createTab')} />
          <Tab label={t('schedules.activeTab', { count: schedules.length })} />
        </Tabs>

        {tab === 0 && (
          <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2.5 }}>
            <TextField
              fullWidth size="small"
              label={t('schedules.name')}
              value={name}
              onChange={(e) => setName(e.target.value)}
            />

            <Box sx={{ display: 'flex', gap: 3, flexWrap: 'wrap', alignItems: 'flex-start' }}>
              <Box>
                <Typography variant="subtitle2" sx={{ mb: 1, color: 'text.secondary' }}>{t('schedules.columns.action')}</Typography>
                <ToggleButtonGroup value={action} exclusive onChange={(_e, v) => { if (v) setAction(v) }} size="small">
                  <ToggleButton value="START"><PlayArrow sx={{ mr: 0.5 }} /> {t('schedules.actionStart')}</ToggleButton>
                  <ToggleButton value="STOP"><Stop sx={{ mr: 0.5 }} /> {t('schedules.actionStop')}</ToggleButton>
                  <ToggleButton value="REMOVE"><Delete sx={{ mr: 0.5 }} /> {t('schedules.actionRemove')}</ToggleButton>
                </ToggleButtonGroup>
              </Box>

              <Box>
                <Typography variant="subtitle2" sx={{ mb: 1, color: 'text.secondary' }}>{t('schedules.columns.type')}</Typography>
                <ToggleButtonGroup value={scheduleType} exclusive onChange={(_e, v) => { if (v) setScheduleType(v) }} size="small">
                  <ToggleButton value="ONE_TIME">{t('schedules.oneTime')}</ToggleButton>
                  <ToggleButton value="RECURRING">{t('schedules.recurring')}</ToggleButton>
                </ToggleButtonGroup>
              </Box>
            </Box>

            {conflictWarning && (
              <Alert severity="warning" icon={<Warning />}>
                {conflictWarning}
              </Alert>
            )}

            {scheduleType === 'RECURRING' ? (
              <Box>
                <CronExpressionBuilder value={cronExpression} onChange={setCronExpression} />
                {expiresAt && (
                  <Alert severity="warning" variant="outlined" sx={{ mt: 1 }}>
                    {t('schedules.expiresAtWarning', { date: dayjs(expiresAt).format('DD/MM/YYYY HH:mm') })}
                  </Alert>
                )}
              </Box>
            ) : (
              <MobileDateTimePicker
                label={t('schedules.scheduledAt')}
                value={scheduledAt}
                onChange={setScheduledAt}
                minDateTime={dayjs()}
                maxDateTime={expiresAt ? dayjs(expiresAt) : undefined}
                slotProps={{
                  textField: {
                    fullWidth: true,
                    size: 'small',
                    helperText: expiresAt
                      ? t('schedules.expiresAtWarning', { date: dayjs(expiresAt).format('DD/MM/YYYY HH:mm') })
                      : undefined,
                  },
                }}
              />
            )}
          </Box>
        )}

        {tab === 1 && (
          <TableContainer>
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell>{t('schedules.columns.name')}</TableCell>
                  <TableCell>{t('schedules.columns.action')}</TableCell>
                  <TableCell>{t('schedules.columns.schedule')}</TableCell>
                  <TableCell>{t('schedules.columns.enabled')}</TableCell>
                  <TableCell>{t('schedules.columns.status')}</TableCell>
                  <TableCell>{t('schedules.columns.actions')}</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {schedules.length === 0 && (
                  <TableRow>
                    <TableCell colSpan={6} align="center" sx={{ color: 'text.secondary', py: 3 }}>
                      {t('schedules.noSchedules')}
                    </TableCell>
                  </TableRow>
                )}
                {schedules.map((s) => (
                  <TableRow key={s.id}>
                    <TableCell>{s.name}</TableCell>
                    <TableCell>
                      <Chip label={s.action} size="small" color={s.action === 'START' ? 'success' : s.action === 'STOP' ? 'warning' : s.action === 'REMOVE' ? 'error' : 'info'} variant="outlined" />
                    </TableCell>
                    <TableCell>
                      <Typography variant="body2" sx={{ fontFamily: "'JetBrains Mono', monospace", fontSize: '0.8rem' }}>
                        {s.scheduleType === 'RECURRING' ? cronToHuman(s.cronExpression || '') : (s.scheduledAt ? new Date(s.scheduledAt).toLocaleString() : '-')}
                      </Typography>
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
                            onChange={() => requestToggle(s.id)}
                            disabled={!s.enabled && s.scheduleType === 'ONE_TIME' && !!s.lastExecutedAt}
                          />
                        </span>
                      </Tooltip>
                    </TableCell>
                    <TableCell>
                      {s.lastExecutionStatus && (
                        <Chip
                          label={s.lastExecutionStatus}
                          size="small"
                          color={s.lastExecutionStatus === 'SUCCESS' ? 'success' : s.lastExecutionStatus === 'FAILED' ? 'error' : 'default'}
                          variant="outlined"
                        />
                      )}
                    </TableCell>
                    <TableCell>
                      <Box sx={{ display: 'flex', gap: 0.5 }}>
                        {s.enabled && (
                          <Tooltip title={t('schedules.executeNow')}>
                            <IconButton size="small" onClick={() => requestExecNow(s.id)}>
                              <PlayArrow fontSize="small" />
                            </IconButton>
                          </Tooltip>
                        )}
                        <Tooltip title={t('common.delete')}>
                          <IconButton size="small" color="error" onClick={() => requestDelete(s.id)}>
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
        )}
      </DialogContent>
      <DialogActions sx={{ px: 3, py: 2 }}>
        <Button onClick={onClose} color="inherit">{t('common.close')}</Button>
        {tab === 0 && (
          <Button variant="contained" onClick={handleCreate}>
            {t('schedules.createSchedule')}
          </Button>
        )}
      </DialogActions>

      <PasswordConfirmDialog
        open={pendingDeleteId !== null}
        title={t('schedules.deleteSchedule')}
        message={t('schedules.confirmDeleteNamed', { name: pendingSchedule?.name ?? '' })}
        onConfirm={handleDeleteConfirm}
        onClose={() => setPendingDeleteId(null)}
      />

      <PasswordConfirmDialog
        open={pendingCreate}
        title={t('schedules.confirmCreate')}
        message={t('schedules.confirmCreateMessage', { name: name.trim() })}
        confirmLabel={t('schedules.createSchedule')}
        loadingLabel={t('common.saving')}
        confirmColor="primary"
        icon={<Schedule />}
        onConfirm={handleCreateConfirm}
        onClose={() => setPendingCreate(false)}
      />

      <PasswordConfirmDialog
        open={pendingToggleId !== null}
        title={t('schedules.confirmToggle')}
        message={t('schedules.confirmToggleMessage', { name: pendingToggleSchedule?.name ?? '' })}
        confirmLabel={t('common.confirm')}
        loadingLabel={t('common.preparing')}
        confirmColor="primary"
        icon={<Lock />}
        onConfirm={handleToggleConfirm}
        onClose={() => setPendingToggleId(null)}
      />

      <PasswordConfirmDialog
        open={pendingExecId !== null}
        title={t('schedules.confirmExecuteNow')}
        message={t('schedules.confirmExecuteNowMessage', { name: pendingExecSchedule?.name ?? '' })}
        confirmLabel={t('common.confirm')}
        loadingLabel={t('common.preparing')}
        confirmColor="primary"
        icon={<Lock />}
        onConfirm={handleExecuteNowConfirm}
        onClose={() => setPendingExecId(null)}
      />
    </Dialog>
  )
}
