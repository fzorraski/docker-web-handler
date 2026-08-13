import { useState, useEffect, useRef } from 'react'
import TenantAccessSelect, { useTenantChoice, useCanClearTenant } from './TenantAccessSelect'
import { splitOwner } from '../utils/tenantAccess'
import { useSseOperation } from '../hooks/useSseOperation'
import {
  Dialog,
  DialogTitle,
  DialogContent,
  DialogActions,
  Button,
  TextField,
  MenuItem,
  Grid,
  IconButton,
  CircularProgress,
  ToggleButtonGroup,
  ToggleButton,
  Typography,
  FormControlLabel,
  Switch,
  Autocomplete,
  Box,
} from '@mui/material'
import { Close, CameraAlt, Download } from '@mui/icons-material'
import { MobileDateTimePicker } from '@mui/x-date-pickers/MobileDateTimePicker'
import dayjs, { type Dayjs } from 'dayjs'
import { useTranslation } from 'react-i18next'
import { getSnapshotRepositories, cancelSnapshot } from '../services/snapshotService'
import { getRepositoryDatabases } from '../services/containerService'
import { prepareSnapshot, streamSnapshot } from '../services/sseService'
import { useNotification } from './NotificationProvider'
import { useAuth } from './AuthProvider'
import OperationProgress, { SNAPSHOT_STEPS } from './OperationProgress'

interface Props {
  open: boolean
  onClose: () => void
  onCreated: () => void
  initialRepository?: string
  initialDatabase?: string
  containerName?: string
}

export default function CreateSnapshotModal({ open, onClose, onCreated, initialRepository, initialDatabase, containerName }: Props) {
  const { notify } = useNotification()
  const { rbacEnabled } = useAuth()
  const canClearTenant = useCanClearTenant()
  const { t } = useTranslation()
  const locked = !!(initialRepository && initialDatabase)
  const [repositories, setRepositories] = useState<string[]>([])
  const [selectedRepo, setSelectedRepo] = useState('')
  const [databases, setDatabases] = useState<string[]>([])
  const [dbLoading, setDbLoading] = useState(false)
  const [selectedDb, setSelectedDb] = useState('')
  const [format, setFormat] = useState<'CUSTOM' | 'SQL'>('CUSTOM')
  const [label, setLabel] = useState('')
  const [description, setDescription] = useState('')
  // ordered: the first tenant owns the snapshot, the rest are shared with
  const [tenantAccess, setTenantAccess] = useState<string[]>([])
  const showTenantSelect = useTenantChoice()
  const [expirationEnabled, setExpirationEnabled] = useState(false)
  const [expiresAt, setExpiresAt] = useState<Dayjs | null>(dayjs().add(7, 'day'))
  const [password, setPassword] = useState('')
  const [cancelling, setCancelling] = useState(false)
  const sse = useSseOperation()
  const pendingInitialDb = useRef<string | undefined>(undefined)

  useEffect(() => {
    if (open) {
      if (locked) {
        setSelectedRepo(initialRepository)
        setSelectedDb(initialDatabase)
        setDatabases([initialDatabase])
      } else {
        getSnapshotRepositories().then(setRepositories).catch(() => setRepositories([]))
        if (initialRepository) {
          setSelectedRepo(initialRepository)
          pendingInitialDb.current = initialDatabase
        }
      }
    }
  }, [open, initialRepository, initialDatabase, locked])

  useEffect(() => {
    if (!selectedRepo || locked) return
    setDbLoading(true)
    setSelectedDb('')
    getRepositoryDatabases(selectedRepo)
      .then((res) => {
        if (res.state === 1 && res.databases) {
          setDatabases(res.databases)
          if (pendingInitialDb.current && res.databases.includes(pendingInitialDb.current)) {
            setSelectedDb(pendingInitialDb.current)
            pendingInitialDb.current = undefined
          }
        } else {
          setDatabases([])
        }
      })
      .catch(() => setDatabases([]))
      .finally(() => setDbLoading(false))
  }, [selectedRepo, locked])

  function resetForm() {
    setSelectedRepo('')
    setDatabases([])
    setSelectedDb('')
    setFormat('CUSTOM')
    setLabel('')
    setDescription('')
    setTenantAccess([])
    setExpirationEnabled(false)
    setExpiresAt(dayjs().add(7, 'day'))
    setPassword('')
    sse.reset()
    setCancelling(false)
  }

  async function handleClose() {
    if (sse.isRunning && selectedRepo && selectedDb) {
      await cancelSnapshot(selectedRepo, selectedDb)
    }
    sse.cleanup()
    onClose()
    setTimeout(resetForm, 300)
  }

  async function handleCancel() {
    if (!selectedRepo || !selectedDb) return
    setCancelling(true)
    await cancelSnapshot(selectedRepo, selectedDb)
  }

  async function handleSaveToServer() {
    if (!validate()) return
    await startSnapshotSse(false)
  }

  async function handleDownload() {
    if (!validate()) return
    await startSnapshotSse(true)
  }

  async function startSnapshotSse(download: boolean) {
    try {
      const ticket = await prepareSnapshot({
        repository: selectedRepo,
        sourceDatabaseName: selectedDb,
        format,
        label: label.trim() || undefined,
        description: download ? undefined : description.trim() || undefined,
        expiresAt: download ? undefined : (expirationEnabled && expiresAt ? expiresAt.format('YYYY-MM-DDTHH:mm:ss') : undefined),
        password,
        containerName,
        temporary: download,
        ...splitOwner(tenantAccess, canClearTenant),
      })

      sse.start(
        (onEvent, onDone, onError) => streamSnapshot(ticket, onEvent, onDone, onError),
        (event) => {
          if (download && event.detail) {
            const a = document.createElement('a')
            a.href = `/api/database/snapshots/download/${encodeURIComponent(event.detail)}`
            a.download = ''
            document.body.appendChild(a)
            a.click()
            document.body.removeChild(a)
          }
          setTimeout(() => {
            onClose()
            onCreated()
            notify(download ? t('createSnapshot.downloadStarted') : t('createSnapshot.snapshotCreated'), 'success')
            setTimeout(resetForm, 300)
          }, 1500)
        },
      )
    } catch (e) {
      notify(e instanceof Error ? e.message : t('common.unexpectedError'), 'error')
    }
  }

  function validate(): boolean {
    if (!selectedRepo) { notify(t('createSnapshot.selectRepoWarning'), 'warning'); return false }
    if (!selectedDb) { notify(t('createSnapshot.selectDbWarning'), 'warning'); return false }
    if (!rbacEnabled && !password) { notify(t('createSnapshot.enterPasswordWarning'), 'warning'); return false }
    return true
  }

  const formReady = selectedRepo && selectedDb && (rbacEnabled || password)

  return (
    <Dialog
      open={open}
      onClose={(_event, reason) => {
        if (sse.isRunning && (reason === 'escapeKeyDown' || reason === 'backdropClick')) return
        handleClose()
      }}
      maxWidth="md"
      fullWidth
    >
      <DialogTitle sx={{ bgcolor: 'primary.dark', color: 'white', display: 'flex', alignItems: 'center' }}>
        <CameraAlt sx={{ mr: 1 }} /> {t('createSnapshot.title')}
        <IconButton onClick={handleClose} sx={{ ml: 'auto', color: 'white' }}>
          <Close />
        </IconButton>
      </DialogTitle>
      <DialogContent dividers sx={{ pt: 3 }}>
        {sse.isRunning || sse.events.length > 0 ? (
          <OperationProgress events={sse.events} steps={SNAPSHOT_STEPS} />
        ) : (
          <>
            <Grid container spacing={2} sx={{ mb: 3 }}>
              <Grid size={{ xs: 12, md: 6 }}>
                {locked ? (
                  <TextField
                    fullWidth
                    label={t('createSnapshot.repository')}
                    value={selectedRepo}
                    size="small"
                    disabled
                  />
                ) : (
                  <TextField
                    select
                    fullWidth
                    label={t('createSnapshot.repository')}
                    value={selectedRepo}
                    onChange={(e) => setSelectedRepo(e.target.value)}
                    size="small"
                  >
                    <MenuItem value="">{t('createSnapshot.selectRepository')}</MenuItem>
                    {repositories.map((r) => (
                      <MenuItem key={r} value={r}>{r}</MenuItem>
                    ))}
                  </TextField>
                )}
              </Grid>
              <Grid size={{ xs: 12, md: 6 }}>
                {locked ? (
                  <TextField
                    fullWidth
                    label={t('createSnapshot.sourceDatabase')}
                    value={selectedDb}
                    size="small"
                    disabled
                  />
                ) : (
                  <Autocomplete
                    freeSolo
                    options={databases}
                    value={selectedDb || null}
                    onChange={(_e, v) => setSelectedDb(typeof v === 'string' ? v : '')}
                    onInputChange={(_e, v) => setSelectedDb(v)}
                    disabled={!selectedRepo || dbLoading}
                    size="small"
                    renderInput={(params) => (
                      <TextField
                        {...params}
                        fullWidth
                        label={t('createSnapshot.sourceDatabase')}
                        slotProps={{
                          input: {
                            ...params.InputProps,
                            endAdornment: (
                              <>
                                {dbLoading ? <CircularProgress size={20} /> : null}
                                {params.InputProps.endAdornment}
                              </>
                            ),
                          },
                        }}
                      />
                    )}
                  />
                )}
              </Grid>
            </Grid>

            <Grid container spacing={2} sx={{ mb: 3 }}>
              <Grid size={{ xs: 12, md: 6 }}>
                <Typography variant="body2" color="text.secondary" sx={{ mb: 1 }}>
                  {t('createSnapshot.outputFormat')}
                </Typography>
                <ToggleButtonGroup
                  value={format}
                  exclusive
                  onChange={(_e, val) => { if (val) setFormat(val) }}
                  size="small"
                  fullWidth
                >
                  <ToggleButton value="CUSTOM">{t('createSnapshot.customFormat')}</ToggleButton>
                  <ToggleButton value="SQL">{t('createSnapshot.sqlFormat')}</ToggleButton>
                </ToggleButtonGroup>
              </Grid>
              <Grid size={{ xs: 12, md: 6 }}>
                <TextField
                  fullWidth
                  label={t('createSnapshot.labelField')}
                  value={label}
                  onChange={(e) => setLabel(e.target.value)}
                  size="small"
                  placeholder={t('createSnapshot.labelPlaceholder')}
                  slotProps={{ htmlInput: { maxLength: 100 } }}
                  sx={{ mt: 3 }}
                />
              </Grid>
            </Grid>

            <TextField
              fullWidth
              label={t('createSnapshot.descriptionLabel')}
              placeholder={t('createSnapshot.descriptionPlaceholder')}
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              size="small"
              multiline
              minRows={2}
              maxRows={4}
              slotProps={{ htmlInput: { maxLength: 500 } }}
              sx={{ mb: 3 }}
            />

            {showTenantSelect && (
              <Box sx={{ mb: 3 }}>
                <TenantAccessSelect value={tenantAccess} onChange={setTenantAccess} />
              </Box>
            )}

            <Grid container spacing={2} sx={{ mb: 2 }} alignItems="center">
              <Grid size={{ xs: 12, md: 4 }}>
                <FormControlLabel
                  control={
                    <Switch
                      checked={expirationEnabled}
                      onChange={(e) => setExpirationEnabled(e.target.checked)}
                    />
                  }
                  label={t('createSnapshot.autoDeleteSnapshot')}
                />
              </Grid>
              {expirationEnabled && (
                <Grid size={{ xs: 12, md: 8 }}>
                  <MobileDateTimePicker
                    label={t('createSnapshot.expiresAt')}
                    value={expiresAt}
                    onChange={(v) => setExpiresAt(v)}
                    minDateTime={dayjs()}
                    slotProps={{
                      textField: {
                        fullWidth: true,
                        size: 'small',
                        helperText: t('createSnapshot.expiresHelperText'),
                      },
                    }}
                  />
                </Grid>
              )}
            </Grid>

            {!rbacEnabled && (
              <TextField
                fullWidth
                type="password"
                label={t('common.operationsPassword')}
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                size="small"
                autoComplete="off"
              />
            )}
          </>
        )}
      </DialogContent>
      <DialogActions sx={{ px: 3, py: 2 }}>
        {sse.hasError ? (
          <>
            <Button onClick={handleClose} color="inherit">{t('common.close')}</Button>
            <Button
              variant="contained"
              color="primary"
              onClick={() => { setCancelling(false); sse.reset() }}
            >
              {t('common.backToForm')}
            </Button>
          </>
        ) : sse.isRunning ? (
          <Button
            onClick={handleCancel}
            color="error"
            variant="contained"
            disabled={cancelling}
            startIcon={cancelling ? <CircularProgress size={20} /> : undefined}
          >
            {cancelling ? t('common.cancelling') : t('common.cancel')}
          </Button>
        ) : (
          <>
            <Button onClick={handleClose} color="inherit">{t('common.cancel')}</Button>
            <Button
              variant="contained"
              color="info"
              onClick={handleDownload}
              disabled={!formReady}
              startIcon={<Download />}
            >
              {t('common.download')}
            </Button>
            <Button
              variant="contained"
              color="success"
              onClick={handleSaveToServer}
              disabled={!formReady}
              startIcon={<CameraAlt />}
            >
              {t('createSnapshot.saveToServer')}
            </Button>
          </>
        )}
      </DialogActions>
    </Dialog>
  )
}
