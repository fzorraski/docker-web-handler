import { useState, useEffect, useMemo } from 'react'
import TenantSelect, { useTenantChoice } from './TenantSelect'
import {
  Dialog, DialogTitle, DialogContent, DialogActions, Button,
  TextField, Box, Typography, IconButton, Chip, Grid, Alert,
  ToggleButtonGroup, ToggleButton, MenuItem, Autocomplete, CircularProgress,
} from '@mui/material'
import { Warning } from '@mui/icons-material'
import { MobileDateTimePicker } from '@mui/x-date-pickers/MobileDateTimePicker'
import dayjs, { type Dayjs } from 'dayjs'
import { Add, Close, PlayArrow, Stop, Delete, Schedule } from '@mui/icons-material'
import { compareTagsDesc } from '../utils/format'
import { useTranslation } from 'react-i18next'
import { useNotification } from './NotificationProvider'
import PasswordConfirmDialog from './PasswordConfirmDialog'
import { RateLimitError } from '../services/fetchWithAuth'
import { createSchedule, getSchedulesByContainer } from '../services/scheduleService'
import {
  getAllowedRepositories, getRepositoryTags, getRepositoryEnvKeys,
} from '../services/containerService'
import CronExpressionBuilder from './CronExpressionBuilder'
import type { DockerContainer } from '../types'

interface Props {
  open: boolean
  onClose: () => void
  onCreated: () => void
  containers?: DockerContainer[]
  passwordRequired?: boolean
}

export default function CreateScheduleModal({ open, onClose, onCreated, containers = [], passwordRequired = true }: Props) {
  const { t } = useTranslation()
  const { notify } = useNotification()

  const [name, setName] = useState('')
  const [tenantId, setTenantId] = useState('')
  const showTenantSelect = useTenantChoice()
  const [action, setAction] = useState<'START' | 'STOP' | 'CREATE' | 'REMOVE'>('START')
  const [scheduleType, setScheduleType] = useState<'ONE_TIME' | 'RECURRING'>('ONE_TIME')
  const [cronExpression, setCronExpression] = useState('0 8 * * 1-5')
  const [scheduledAt, setScheduledAt] = useState<Dayjs | null>(dayjs().add(1, 'hour'))

  // START/STOP fields
  const [selectedContainer, setSelectedContainer] = useState<DockerContainer | null>(null)
  const [pendingRequest, setPendingRequest] = useState<Parameters<typeof createSchedule>[0] | null>(null)
  const [containerSchedules, setContainerSchedules] = useState<import('../types').ContainerSchedule[]>([])

  // CREATE fields
  const [repositories, setRepositories] = useState<string[]>([])
  const [selectedRepo, setSelectedRepo] = useState('')
  const [allTags, setAllTags] = useState<string[]>([])
  const [selectedTag, setSelectedTag] = useState<string | null>(null)
  const [tagsLoading, setTagsLoading] = useState(false)
  const [containerName, setContainerName] = useState('')
  const [envVars, setEnvVars] = useState<{ key: string; value: string }[]>([])

  useEffect(() => {
    if (open && action === 'CREATE') {
      getAllowedRepositories().then(setRepositories).catch(() => setRepositories([]))
    }
  }, [open, action])

  useEffect(() => {
    if (!selectedContainer || action === 'CREATE') {
      setContainerSchedules([])
      return
    }
    getSchedulesByContainer(selectedContainer.containerId)
      .then(setContainerSchedules)
      .catch(() => setContainerSchedules([]))
  }, [selectedContainer])

  const conflictWarning = useMemo(() => {
    if (action === 'CREATE' || containerSchedules.length === 0) return null
    const enabled = containerSchedules.filter(s => s.enabled)
    if (enabled.some(s => s.action === 'REMOVE')) return t('schedules.conflict.removeExists')
    if (action === 'REMOVE' && enabled.length > 0) {
      const names = enabled.map(s => `${s.name} (${s.action})`).join(', ')
      return t('schedules.conflict.removeBlocked', { schedules: names })
    }
    if (enabled.some(s => s.action === action)) {
      return t('schedules.conflict.duplicateAction', { action: t(`schedules.action${action.charAt(0) + action.slice(1).toLowerCase()}` as never) })
    }
    return null
  }, [containerSchedules, action, t])

  useEffect(() => {
    if (!selectedRepo) { setAllTags([]); setSelectedTag(null); return }
    setTagsLoading(true)
    setSelectedTag(null)
    getRepositoryTags(selectedRepo)
      .then((res) => {
        if (res.state === 1 && res.tags) setAllTags([...res.tags].sort(compareTagsDesc))
        else setAllTags([])
      })
      .catch(() => setAllTags([]))
      .finally(() => setTagsLoading(false))
    getRepositoryEnvKeys(selectedRepo)
      .then((keys) => setEnvVars(keys.map((k) => ({ key: k.key, value: k.value }))))
      .catch(() => setEnvVars([]))
  }, [selectedRepo])

  function reset() {
    setName('')
    setTenantId('')
    setAction('START')
    setScheduleType('ONE_TIME')
    setCronExpression('0 8 * * 1-5')
    setScheduledAt(dayjs().add(1, 'hour'))
    setSelectedContainer(null)
    setSelectedRepo('')
    setSelectedTag(null)
    setContainerName('')
    setEnvVars([])
    setPendingRequest(null)
    setContainerSchedules([])
  }

  async function handleCreate() {
    if (!name.trim()) {
      notify(t('schedules.nameRequired'), 'warning')
      return
    }

    const request: Parameters<typeof createSchedule>[0] = {
      name: name.trim(),
      action,
      scheduleType,
      cronExpression: scheduleType === 'RECURRING' ? cronExpression : undefined,
      scheduledAt: scheduleType === 'ONE_TIME' && scheduledAt ? scheduledAt.toISOString() : undefined,
      tenantId: tenantId || undefined,
    }

    if (action === 'START' || action === 'STOP' || action === 'REMOVE') {
      if (!selectedContainer) {
        notify(t('schedules.selectContainer'), 'warning')
        return
      }
      request.containerId = selectedContainer.containerId
      request.containerName = selectedContainer.names
    } else if (action === 'CREATE') {
      if (!selectedRepo || !selectedTag) {
        notify(t('schedules.selectRepoAndTag'), 'warning')
        return
      }
      const envList = envVars.filter(e => e.key.trim()).map(e => `${e.key.trim()}=${e.value.trim()}`)
      request.createConfig = {
        repository: selectedRepo,
        tag: selectedTag,
        containerName: containerName || undefined,
        envVars: envList.length > 0 ? envList : undefined,
      }
    }

    if (!passwordRequired) {
      try {
        await createSchedule(request)
        notify(t('schedules.created'), 'success')
        reset()
        onCreated()
        onClose()
      } catch (e: unknown) {
        notify((e as Error).message || t('common.unexpectedError'), 'error')
      }
      return
    }
    setPendingRequest(request)
  }

  async function handlePasswordConfirm(password: string) {
    if (!pendingRequest) return
    try {
      await createSchedule({ ...pendingRequest, operationsPassword: password })
      notify(t('schedules.created'), 'success')
      reset()
      onCreated()
      onClose()
      setPendingRequest(null)
    } catch (e: unknown) {
      if (e instanceof RateLimitError) throw e
      notify((e as Error).message || t('common.unexpectedError'), 'error')
      setPendingRequest(null)
    }
  }

  function handleClose() {
    reset()
    onClose()
  }

  return (
    <Dialog open={open} onClose={handleClose} maxWidth="md" fullWidth>
      <DialogTitle sx={{ bgcolor: 'primary.dark', color: 'white', display: 'flex', alignItems: 'center' }}>
        <Schedule sx={{ mr: 1 }} /> {t('schedules.newSchedule')}
        <IconButton onClick={handleClose} sx={{ ml: 'auto', color: 'white' }}>
          <Close />
        </IconButton>
      </DialogTitle>
      <DialogContent dividers sx={{ pt: 3 }}>
        <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2.5 }}>
          <TextField
            fullWidth size="small"
            label={t('schedules.name')}
            placeholder={t('schedules.namePlaceholder')}
            value={name}
            onChange={(e) => setName(e.target.value)}
          />

          {showTenantSelect && <TenantSelect value={tenantId} onChange={setTenantId} />}

          <Box>
            <Typography variant="subtitle2" sx={{ mb: 1, color: 'text.secondary' }}>{t('schedules.columns.action')}</Typography>
            <ToggleButtonGroup value={action} exclusive onChange={(_e, v) => { if (v) setAction(v) }} size="small">
              <ToggleButton value="START"><PlayArrow sx={{ mr: 0.5 }} /> {t('schedules.actionStart')}</ToggleButton>
              <ToggleButton value="STOP"><Stop sx={{ mr: 0.5 }} /> {t('schedules.actionStop')}</ToggleButton>
              <ToggleButton value="CREATE"><Add sx={{ mr: 0.5 }} /> {t('schedules.actionCreate')}</ToggleButton>
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

          {scheduleType === 'RECURRING' ? (
            <CronExpressionBuilder value={cronExpression} onChange={setCronExpression} />
          ) : (
            <MobileDateTimePicker
              label={t('schedules.scheduledAt')}
              value={scheduledAt}
              onChange={setScheduledAt}
              minDateTime={dayjs()}
              slotProps={{ textField: { fullWidth: true, size: 'small' } }}
            />
          )}

          {(action === 'START' || action === 'STOP' || action === 'REMOVE') && (
            <Box>
              <Typography variant="subtitle2" sx={{ mb: 1, color: 'text.secondary' }}>{t('schedules.targetContainer')}</Typography>
              <Autocomplete
                options={containers}
                value={selectedContainer}
                onChange={(_e, v) => setSelectedContainer(v)}
                getOptionLabel={(c) => `${c.names} (${c.containerId.substring(0, 10)})`}
                renderInput={(params) => (
                  <TextField {...params} size="small" placeholder={t('schedules.selectContainer')} />
                )}
              />
              {conflictWarning && (
                <Alert severity="warning" icon={<Warning />} sx={{ mt: 1.5 }}>
                  {conflictWarning}
                </Alert>
              )}
            </Box>
          )}

          {action === 'CREATE' && (
            <Box sx={{ p: 2, border: 1, borderColor: 'divider', borderRadius: 2 }}>
              <Typography variant="subtitle2" sx={{ mb: 2, color: 'text.secondary' }}>{t('schedules.containerConfig')}</Typography>
              <Grid container spacing={2}>
                <Grid size={{ xs: 12, md: 4 }}>
                  <TextField
                    select fullWidth size="small"
                    label={t('newContainer.repository')}
                    value={selectedRepo}
                    onChange={(e) => setSelectedRepo(e.target.value)}
                  >
                    <MenuItem value="">{t('newContainer.selectRepository')}</MenuItem>
                    {repositories.map((r) => <MenuItem key={r} value={r}>{r}</MenuItem>)}
                  </TextField>
                </Grid>
                <Grid size={{ xs: 12, md: 8 }}>
                  <Autocomplete
                    options={allTags}
                    value={selectedTag}
                    onChange={(_e, v) => setSelectedTag(v)}
                    disabled={!selectedRepo}
                    loading={tagsLoading}
                    renderInput={(params) => (
                      <TextField
                        {...params}
                        label={t('newContainer.tag')}
                        placeholder={t('newContainer.tagPlaceholder')}
                        size="small"
                        slotProps={{
                          input: {
                            ...params.InputProps,
                            endAdornment: (
                              <>
                                {tagsLoading ? <CircularProgress size={20} /> : null}
                                {params.InputProps.endAdornment}
                              </>
                            ),
                          },
                        }}
                      />
                    )}
                  />
                </Grid>
                <Grid size={{ xs: 12, md: 6 }}>
                  <TextField
                    fullWidth size="small"
                    label={t('newContainer.containerName')}
                    placeholder={t('newContainer.containerNamePlaceholder')}
                    value={containerName}
                    onChange={(e) => setContainerName(e.target.value)}
                  />
                </Grid>
              </Grid>

              {envVars.length > 0 && (
                <Box sx={{ mt: 2 }}>
                  <Typography variant="caption" color="text.secondary">{t('newContainer.environmentVariables')}</Typography>
                  {envVars.map((env, i) => (
                    <Box key={i} sx={{ display: 'flex', gap: 1, mt: 0.5, alignItems: 'center' }}>
                      <TextField size="small" placeholder="KEY" value={env.key}
                        onChange={(e) => { const u = [...envVars]; u[i].key = e.target.value; setEnvVars(u) }}
                        sx={{ flex: 1 }} />
                      <Typography>=</Typography>
                      <TextField size="small" placeholder="VALUE" value={env.value}
                        onChange={(e) => { const u = [...envVars]; u[i].value = e.target.value; setEnvVars(u) }}
                        sx={{ flex: 1 }} />
                    </Box>
                  ))}
                </Box>
              )}
            </Box>
          )}
        </Box>
      </DialogContent>
      <DialogActions sx={{ px: 3, py: 2 }}>
        <Button onClick={handleClose} color="inherit">{t('common.cancel')}</Button>
        <Button variant="contained" onClick={handleCreate} startIcon={<Schedule />}>
          {t('schedules.createSchedule')}
        </Button>
      </DialogActions>

      <PasswordConfirmDialog
        open={pendingRequest !== null}
        title={t('schedules.confirmCreate')}
        message={t('schedules.confirmCreateMessage', { name: pendingRequest?.name ?? '' })}
        confirmLabel={t('schedules.createSchedule')}
        loadingLabel={t('common.saving')}
        confirmColor="primary"
        icon={<Schedule />}
        onConfirm={handlePasswordConfirm}
        onClose={() => setPendingRequest(null)}
      />
    </Dialog>
  )
}
