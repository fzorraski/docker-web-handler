import { useState, useEffect, useRef } from 'react'
import { useSseOperation } from '../hooks/useSseOperation'
import {
  Autocomplete,
  Dialog,
  DialogTitle,
  DialogContent,
  DialogActions,
  Button,
  TextField,
  MenuItem,
  Grid,
  IconButton,
  Typography,
  Box,
  CircularProgress,
  Switch,
  FormControlLabel,
  Checkbox,
  Chip,
  ToggleButtonGroup,
  ToggleButton,
} from '@mui/material'
import { MobileDateTimePicker } from '@mui/x-date-pickers/MobileDateTimePicker'
import dayjs, { type Dayjs } from 'dayjs'
import { Add, Close, Delete, Memory, PlayArrow, Restore, Timer, Warning, FolderOpen } from '@mui/icons-material'
import { Alert } from '@mui/material'
import { useTranslation } from 'react-i18next'
import {
  getAllowedRepositories,
  getDatabaseConflicts,
  getFeatures,
  getRepositoryDatabases,
  getRepositoryConfig,
  getRepositoryEnvKeys,
  getRepositoryTags,
  isMigrationApiAvailable,
  repositoryHasDatabases,
  validateOperationsPassword,
} from '../services/containerService'
import { isDumpEnabled, listDumps, getPostRestoreScripts, type PostRestoreScriptsResponse } from '../services/dumpService'
import type { DatabaseConflict, DatabaseDump, DatabaseSnapshot } from '../types'
import { buildTargetDbName, buildSnapshotTargetDbName, formatBytes, formatScriptSize, formatMigrationSummary, compareTagsDesc } from '../utils/format'
import { prepareRunContainer, streamRunContainer, cancelRunContainer } from '../services/sseService'
import { useNotification } from './NotificationProvider'
import { useMigrationPreview } from '../hooks/useMigrationPreview'
import OperationProgress, { RUN_STEPS, RUN_WITH_RESTORE_STEPS, RUN_WITH_RESTORE_AND_SCRIPTS_STEPS, RUN_WITH_RESTORE_AND_MIGRATION_STEPS, RUN_WITH_RESTORE_SCRIPTS_AND_MIGRATION_STEPS, RUN_WITH_MIGRATION_STEPS } from './OperationProgress'
import DumpBrowserModal from './DumpBrowserModal'
import MigrationConfigModal, { type MigrationConfig } from './MigrationConfigModal'
import MigrationPreviewModal from './MigrationPreviewModal'
import PasswordConfirmDialog from './PasswordConfirmDialog'

interface Props {
  open: boolean
  onClose: () => void
  onCreated: () => void
}

interface EnvVar {
  key: string
  value: string
}

const sectionSx = {
  p: 2.5,
  mb: 2,
  borderRadius: 2,
  border: 1,
  borderColor: 'divider',
  bgcolor: 'rgba(255,255,255,0.02)',
}

export default function NewContainerModal({ open, onClose, onCreated }: Props) {
  const { notify, confirm } = useNotification()
  const { t } = useTranslation()
  const [repositories, setRepositories] = useState<string[]>([])
  const [selectedRepo, setSelectedRepo] = useState('')
  const [allTags, setAllTags] = useState<string[]>([])
  const [selectedTag, setSelectedTag] = useState<string | null>(null)
  const [tagsLoading, setTagsLoading] = useState(false)
  const [containerName, setContainerName] = useState('')
  const containerNameValid = containerName === '' || /^[a-zA-Z0-9][a-zA-Z0-9_.-]*$/.test(containerName)
  const [envVars, setEnvVars] = useState<EnvVar[]>([])
  const [memoryMb, setMemoryMb] = useState<string>('')
  const [memoryEnabled, setMemoryEnabled] = useState(false)
  const [memoryMaxMb, setMemoryMaxMb] = useState<number>(65536)
  const [dbEnabled, setDbEnabled] = useState(false)
  const [databases, setDatabases] = useState<string[]>([])
  const [selectedDb, setSelectedDb] = useState<string | null>(null)
  const [dbEnvVar, setDbEnvVar] = useState<string | null>(null)
  const [dbLoading, setDbLoading] = useState(false)
  const [dbDeletionEnabled, setDbDeletionEnabled] = useState(false)
  const [deleteDbOnExpiration, setDeleteDbOnExpiration] = useState(false)
  const [dbConflict, setDbConflict] = useState<DatabaseConflict | null>(null)
  const [dumpFeatureEnabled, setDumpFeatureEnabled] = useState(false)
  const [allDumps, setAllDumps] = useState<DatabaseDump[]>([])
  const [selectedDump, setSelectedDump] = useState<DatabaseDump | null>(null)
  const [selectedSnapshot, setSelectedSnapshot] = useState<DatabaseSnapshot | null>(null)
  const [dbMode, setDbMode] = useState<'existing' | 'restore'>('existing')
  const [dumpBrowserOpen, setDumpBrowserOpen] = useState(false)
  const [restoreTargetDb, setRestoreTargetDb] = useState('')
  const [createDatabase, setCreateDatabase] = useState(false)
  const [scriptsResponse, setScriptsResponse] = useState<PostRestoreScriptsResponse | null>(null)
  const [selectedOptionalScripts, setSelectedOptionalScripts] = useState<string[]>([])
  const [confirmDialogOpen, setConfirmDialogOpen] = useState(false)
  const [confirmNameInput, setConfirmNameInput] = useState('')
  const [confirmOverrideOpen, setConfirmOverrideOpen] = useState(false)
  const [migrationFeatureEnabled, setMigrationFeatureEnabled] = useState(false)
  const [migrationApiAvail, setMigrationApiAvail] = useState(false)
  const [migrationEnabled, setMigrationEnabled] = useState(false)
  const [migrationConfig, setMigrationConfig] = useState<MigrationConfig | null>(null)
  const [migrationModalOpen, setMigrationModalOpen] = useState(false)
  const [opsPwRequired, setOpsPwRequired] = useState(true)
  const [webhookFeatureEnabled, setWebhookFeatureEnabled] = useState(false)
  const [webhookNotify, setWebhookNotify] = useState(false)
  const [operationsPassword, setOperationsPassword] = useState('')
  const operationsPasswordRef = useRef('')
  const [operationsPasswordOpen, setOperationsPasswordOpen] = useState(false)
  const migrationPreview = useMigrationPreview()
  const [runTicket, setRunTicket] = useState<string | null>(null)
  const [cancelling, setCancelling] = useState(false)
  const activeDbName = dbMode === 'restore' ? restoreTargetDb.trim() || null : selectedDb
  const hasDbUsageConflict = (dbConflict?.inUseByContainers?.length ?? 0) > 0
  const restoreDbExists = !!(dbMode === 'restore' && restoreTargetDb.trim() && databases.includes(restoreTargetDb.trim()))
  const expirationLockedByDb = !!(dbConflict?.scheduledForDeletionBy && dbConflict?.expiresAt)
  const [defaultExpMinutes, setDefaultExpMinutes] = useState(480)
  const [expirationEnabled, setExpirationEnabled] = useState(true)
  const [expiresAt, setExpiresAt] = useState<Dayjs | null>(dayjs().add(480, 'minute'))
  const sse = useSseOperation()

  // Keep migration target version in sync with selected tag
  useEffect(() => {
    if (migrationConfig && selectedTag) {
      setMigrationConfig((prev) => prev ? { ...prev, targetVersion: selectedTag } : prev)
    }
  }, [selectedTag])

  useEffect(() => {
    if (!open) return
    getAllowedRepositories()
      .then((repos) => {
        setRepositories(repos)
        if (repos.length > 0) setSelectedRepo(repos[0])
      })
      .catch(() => {})
    getFeatures()
      .then((f) => {
        setDefaultExpMinutes(f.defaultExpirationMinutes)
        setExpiresAt(dayjs().add(f.defaultExpirationMinutes, 'minute'))
        setMemoryEnabled(f.memoryLimit)
        setMemoryMaxMb(f.memoryLimitMaxMb)
        setDbDeletionEnabled(f.deletionOnExpiration)
        setDumpFeatureEnabled(f.dump)
        setMigrationFeatureEnabled(f.migration)
        setOpsPwRequired(f.operationsPasswordRequired)
        setWebhookFeatureEnabled(f.webhook)
        if (f.dump) {
          listDumps().then(setAllDumps).catch(() => setAllDumps([]))
        }
      })
      .catch(() => {})
  }, [open])

  useEffect(() => {
    if (!selectedRepo) {
      setAllTags([])
      setSelectedTag(null)
      setEnvVars([])
      setDbEnabled(false)
      setDatabases([])
      setSelectedDb(null)
      setDbEnvVar(null)
      setScriptsResponse(null)
      setSelectedOptionalScripts([])
      setMigrationApiAvail(false)
      return
    }
    if (migrationFeatureEnabled) {
      isMigrationApiAvailable(selectedRepo).then(setMigrationApiAvail).catch(() => setMigrationApiAvail(false))
    }
    getPostRestoreScripts(selectedRepo)
      .then((res) => {
        setScriptsResponse(res)
        if (res.enabled) {
          setSelectedOptionalScripts([])
        }
      })
      .catch(() => setScriptsResponse(null))
    setTagsLoading(true)
    setSelectedTag(null)
    getRepositoryTags(selectedRepo)
      .then((res) => {
        if (res.state === 1 && res.tags) setAllTags([...res.tags].sort(compareTagsDesc))
        else {
          setAllTags([])
          if (res.message) notify(res.message, 'error')
        }
      })
      .catch(() => setAllTags([]))
      .finally(() => setTagsLoading(false))
    getRepositoryEnvKeys(selectedRepo)
      .then((keys) => setEnvVars(keys.map((k) => ({ key: k.key, value: k.value }))))
      .catch(() => setEnvVars([]))
    getRepositoryConfig(selectedRepo)
      .then((cfg) => {
        setMemoryEnabled(cfg.memoryLimitEnabled)
        setMemoryMaxMb(cfg.memoryLimitMaxMb)
        setDefaultExpMinutes(cfg.defaultExpirationMinutes)
        setExpiresAt(dayjs().add(cfg.defaultExpirationMinutes, 'minute'))
      })
      .catch(() => {})
    repositoryHasDatabases(selectedRepo)
      .then((has) => {
        setDbEnabled(has)
        setDatabases([])
        setSelectedDb(null)
        setDbEnvVar(null)
        if (has) {
          setDbLoading(true)
          getRepositoryDatabases(selectedRepo)
            .then((res) => {
              if (res.state === 1 && res.databases) {
                setDatabases(res.databases)
                setDbEnvVar(res.dbEnvVar ?? null)
              } else if (res.message) {
                notify(res.message, 'error')
              }
            })
            .catch(() => setDatabases([]))
            .finally(() => setDbLoading(false))
        }
      })
      .catch(() => setDbEnabled(false))
  }, [selectedRepo])

  useEffect(() => {
    if (dbMode !== 'restore') return
    setDeleteDbOnExpiration(false)
    setDbConflict(null)
    const name = restoreTargetDb.trim()
    if (name && databases.includes(name)) {
      getDatabaseConflicts(name)
        .then((conflict) => {
          setDbConflict(conflict)
          if (conflict.scheduledForDeletionBy && conflict.expiresAt) {
            setExpirationEnabled(true)
            setExpiresAt(dayjs(conflict.expiresAt))
          }
        })
        .catch(() => setDbConflict(null))
    }
  }, [restoreTargetDb, dbMode])

  function addEnvVar() {
    setEnvVars([...envVars, { key: '', value: '' }])
  }

  function updateEnvVar(index: number, field: 'key' | 'value', val: string) {
    const updated = [...envVars]
    updated[index][field] = val
    setEnvVars(updated)
  }

  function removeEnvVar(index: number) {
    setEnvVars(envVars.filter((_, i) => i !== index))
  }

  function handleDatabaseSelect(dbName: string | null) {
    setSelectedDb(dbName)
    setDeleteDbOnExpiration(false)
    setDbConflict(null)
    if (dbEnvVar && dbName) {
      const idx = envVars.findIndex((e) => e.key === dbEnvVar)
      if (idx >= 0) {
        updateEnvVar(idx, 'value', dbName)
      } else {
        setEnvVars((prev) => [...prev, { key: dbEnvVar, value: dbName }])
      }
    }
    if (dbName) {
      getDatabaseConflicts(dbName)
        .then((conflict) => {
          setDbConflict(conflict)
          if (conflict.scheduledForDeletionBy && conflict.expiresAt) {
            setExpirationEnabled(true)
            setExpiresAt(dayjs(conflict.expiresAt))
          }
        })
        .catch(() => setDbConflict(null))
    }
  }

  function handleDbModeChange(mode: 'existing' | 'restore') {
    setDbMode(mode)
    setDeleteDbOnExpiration(false)
    setDbConflict(null)
    setMigrationEnabled(false)
    setMigrationConfig(null)
    if (mode === 'existing') {
      setSelectedDump(null)
      setSelectedSnapshot(null)
      setRestoreTargetDb('')
      setCreateDatabase(false)
    } else {
      setSelectedDb(null)
    }
  }

  function handleDumpSelected(dump: DatabaseDump) {
    setSelectedDump(dump)
    setSelectedSnapshot(null)
    const targetName = buildTargetDbName(dump)
    setRestoreTargetDb(targetName)
    setCreateDatabase(!databases.includes(targetName))
    if (dbEnvVar && targetName) {
      const idx = envVars.findIndex((e) => e.key === dbEnvVar)
      if (idx >= 0) {
        updateEnvVar(idx, 'value', targetName)
      } else {
        setEnvVars((prev) => [...prev, { key: dbEnvVar, value: targetName }])
      }
    }
  }

  function handleSnapshotSelected(snap: DatabaseSnapshot) {
    setSelectedSnapshot(snap)
    setSelectedDump(null)
    const targetName = buildSnapshotTargetDbName(snap)
    setRestoreTargetDb(targetName)
    setCreateDatabase(!databases.includes(targetName))
    if (dbEnvVar && targetName) {
      const idx = envVars.findIndex((e) => e.key === dbEnvVar)
      if (idx >= 0) {
        updateEnvVar(idx, 'value', targetName)
      } else {
        setEnvVars((prev) => [...prev, { key: dbEnvVar, value: targetName }])
      }
    }
  }

  function resetForm() {
    setSelectedRepo('')
    setSelectedTag(null)
    setAllTags([])
    setContainerName('')
    setEnvVars([])
    setMemoryMb('')
    setExpirationEnabled(true)
    setExpiresAt(dayjs().add(defaultExpMinutes, 'minute'))
    sse.reset()
    setDbEnabled(false)
    setDatabases([])
    setSelectedDb(null)
    setDbEnvVar(null)
    setDeleteDbOnExpiration(false)
    setDbConflict(null)
    setSelectedDump(null)
    setSelectedSnapshot(null)
    setDbMode('existing')
    setDumpBrowserOpen(false)
    setRestoreTargetDb('')
    setCreateDatabase(false)
    setScriptsResponse(null)
    setSelectedOptionalScripts([])
    setConfirmDialogOpen(false)
    setConfirmNameInput('')
    setConfirmOverrideOpen(false)
    setMigrationEnabled(false)
    setMigrationConfig(null)
    setMigrationModalOpen(false)
    setWebhookNotify(false)
    setOperationsPassword('')
    operationsPasswordRef.current = ''
    setOperationsPasswordOpen(false)
    migrationPreview.reset()
    setRunTicket(null)
    setCancelling(false)
  }

  async function handleRun() {
    if (!selectedRepo) return notify(t('newContainer.selectRepoWarning'), 'warning')
    if (!selectedTag) return notify(t('newContainer.selectTagWarning'), 'warning')

    if (dbMode === 'restore') {
      if (!selectedDump && !selectedSnapshot) return notify(t('newContainer.selectDumpWarning'), 'warning')
      if (!restoreTargetDb.trim()) return notify(t('newContainer.enterTargetDbWarning'), 'warning')
    }

    const needsPassword = opsPwRequired && (
      (dbMode === 'existing' && migrationEnabled && migrationConfig) || deleteDbOnExpiration
    )
    if (needsPassword && !operationsPasswordRef.current) {
      setOperationsPasswordOpen(true)
      return
    }

    await continueAfterPassword()
  }

  async function continueAfterPassword() {
    if (restoreDbExists) {
      setConfirmOverrideOpen(true)
      return
    }

    await proceedAfterOverrideCheck()
  }

  async function proceedAfterOverrideCheck() {
    setConfirmOverrideOpen(false)

    if (deleteDbOnExpiration && activeDbName && expirationEnabled) {
      setConfirmNameInput('')
      setConfirmDialogOpen(true)
      return
    }

    const name = containerName ? t('newContainer.confirmRunAs', { containerName }) : ''
    const accepted = await confirm(t('newContainer.confirmRun', { repo: selectedRepo, tag: selectedTag, name }))
    if (!accepted) return

    await proceedToMigrationPreviewOrRun()
  }

  async function proceedToMigrationPreviewOrRun() {
    if (migrationEnabled && migrationConfig) {
      const shown = await migrationPreview.showPreview(migrationConfig, selectedRepo)
      if (shown) return
    }

    executeRun()
  }

  async function executeRun() {
    try {
      const envList = envVars
        .filter((e) => e.key.trim())
        .map((e) => `${e.key.trim()}=${e.value.trim()}`)

      const parsedMemory = memoryMb ? parseInt(memoryMb, 10) : null
      const shouldDeleteDb = deleteDbOnExpiration && expirationEnabled && !!activeDbName

      const ticket = await prepareRunContainer({
        repository: selectedRepo,
        tag: selectedTag!,
        containerName,
        envVars: envList,
        expiresAt: expirationEnabled && expiresAt ? expiresAt.format('YYYY-MM-DDTHH:mm:ss') : null,
        memoryMb: parsedMemory && !isNaN(parsedMemory) ? parsedMemory : null,
        databaseName: activeDbName || null,
        deleteDatabaseOnExpiration: shouldDeleteDb,
        dumpId: dbMode === 'restore' && selectedDump ? selectedDump.id : null,
        snapshotId: dbMode === 'restore' && selectedSnapshot ? selectedSnapshot.id : null,
        createDatabase: dbMode === 'restore' ? createDatabase : false,
        selectedOptionalScripts: dbMode === 'restore' && scriptsResponse?.enabled ? selectedOptionalScripts : undefined,
        operationsPassword: operationsPasswordRef.current || null,
        migrationMode: migrationEnabled && migrationConfig ? migrationConfig.mode : null,
        migrationSql: migrationEnabled && migrationConfig?.mode === 'MANUAL' ? migrationConfig.sql : null,
        migrationSourceVersion: migrationEnabled && migrationConfig ? migrationConfig.sourceVersion : null,
        migrationTargetVersion: migrationEnabled && migrationConfig ? migrationConfig.targetVersion : null,
        webhookNotify: webhookFeatureEnabled ? webhookNotify : undefined,
      })

      setRunTicket(ticket)

      sse.start(
        (onEvent, onDone, onError) => streamRunContainer(ticket, onEvent, onDone, onError),
        () => {
          setTimeout(() => {
            resetForm()
            onClose()
            onCreated()
            notify(t('newContainer.containerStarted'), 'success')
          }, 1500)
        },
      )
    } catch (e) {
      operationsPasswordRef.current = ''
      notify(e instanceof Error && e.message ? e.message : t('common.unexpectedError'), 'error')
    }
  }

  function handleConfirmRun() {
    setConfirmDialogOpen(false)
    proceedToMigrationPreviewOrRun()
  }

  function handleClose() {
    sse.cleanup()
    resetForm()
    onClose()
  }

  const usedBySuffix = hasDbUsageConflict
    ? t('newContainer.dbUsedBySuffix', { containers: dbConflict!.inUseByContainers.join(', ') })
    : ''

  return (
    <>
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
        <Add sx={{ mr: 1 }} /> {t('newContainer.title')}
        <IconButton onClick={handleClose} sx={{ ml: 'auto', color: 'white' }}>
          <Close />
        </IconButton>
      </DialogTitle>
      <DialogContent dividers sx={{ pt: 3 }}>
        {sse.isRunning || sse.events.length > 0 ? (
          <OperationProgress events={sse.events} steps={
            dbMode === 'restore' && (selectedDump || selectedSnapshot)
              ? (scriptsResponse?.enabled
                  ? (migrationEnabled && migrationConfig ? RUN_WITH_RESTORE_SCRIPTS_AND_MIGRATION_STEPS : RUN_WITH_RESTORE_AND_SCRIPTS_STEPS)
                  : (migrationEnabled && migrationConfig ? RUN_WITH_RESTORE_AND_MIGRATION_STEPS : RUN_WITH_RESTORE_STEPS))
              : (migrationEnabled && migrationConfig ? RUN_WITH_MIGRATION_STEPS : undefined)
          } />
        ) : (
          <>
            <Box sx={sectionSx}>
            <Grid container spacing={2}>
              <Grid size={{ xs: 12, md: 4 }}>
                <TextField
                  select
                  fullWidth
                  label={t('newContainer.repository')}
                  value={selectedRepo}
                  onChange={(e) => setSelectedRepo(e.target.value)}
                  size="small"
                >
                  <MenuItem value="">{t('newContainer.selectRepository')}</MenuItem>
                  {repositories.map((r) => (
                    <MenuItem key={r} value={r}>{r}</MenuItem>
                  ))}
                </TextField>
              </Grid>
              <Grid size={{ xs: 12, md: 8 }}>
                <Autocomplete
                  options={allTags}
                  value={selectedTag}
                  onChange={(_e, value) => setSelectedTag(value)}
                  disabled={!selectedRepo}
                  loading={tagsLoading}
                  noOptionsText={!selectedRepo ? t('newContainer.selectRepoFirst') : t('newContainer.noTagsFound')}
                  slotProps={{ listbox: { style: { maxHeight: 7 * 36 } } }}
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
                  fullWidth
                  label={t('newContainer.containerName')}
                  placeholder={t('newContainer.containerNamePlaceholder')}
                  value={containerName}
                  onChange={(e) => setContainerName(e.target.value)}
                  size="small"
                  error={!containerNameValid}
                  helperText={!containerNameValid ? t('newContainer.containerNameError') : ''}
                />
              </Grid>
              {memoryEnabled && (
                <Grid size={{ xs: 12, md: 6 }}>
                  <TextField
                    fullWidth
                    label={t('newContainer.memoryLimit')}
                    placeholder={t('newContainer.memoryPlaceholder')}
                    value={memoryMb}
                    onChange={(e) => setMemoryMb(e.target.value.replace(/\D/g, ''))}
                    size="small"
                    type="text"
                    error={!!memoryMb && parseInt(memoryMb, 10) > memoryMaxMb}
                    helperText={memoryMb && parseInt(memoryMb, 10) > memoryMaxMb
                      ? t('newContainer.memoryExceeded', { max: memoryMaxMb })
                      : memoryMaxMb < 65536
                        ? t('newContainer.memoryHelperTextMax', { max: memoryMaxMb })
                        : t('newContainer.memoryHelperText')}
                    slotProps={{
                      input: {
                        startAdornment: <Memory fontSize="small" sx={{ mr: 1, color: 'text.secondary' }} />,
                      },
                    }}
                  />
                </Grid>
              )}
            </Grid>
            </Box>

            {/* Database */}
            {dbEnabled && (
              <Box sx={sectionSx}>
              <Grid container spacing={2}>
                {dumpFeatureEnabled && (
                  <Grid size={{ xs: 12 }}>
                    <ToggleButtonGroup
                      value={dbMode}
                      exclusive
                      onChange={(_e, value) => { if (value) handleDbModeChange(value) }}
                      size="small"
                    >
                      <ToggleButton value="existing">{t('newContainer.existingDatabase')}</ToggleButton>
                      <ToggleButton value="restore">{t('newContainer.restoreFromDump')}</ToggleButton>
                    </ToggleButtonGroup>
                  </Grid>
                )}

                {dbMode === 'existing' && (
                  <>
                    <Grid size={{ xs: 12, md: 6 }}>
                      <Autocomplete
                        options={databases}
                        value={selectedDb}
                        onChange={(_e, value) => handleDatabaseSelect(value)}
                        loading={dbLoading}
                        noOptionsText={dbLoading ? t('newContainer.loadingDatabases') : t('newContainer.noDatabasesFound')}
                        slotProps={{ listbox: { style: { maxHeight: 7 * 36 } } }}
                        renderInput={(params) => (
                          <TextField
                            {...params}
                            label={t('newContainer.database')}
                            placeholder={t('newContainer.selectDatabase')}
                            size="small"
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
                    </Grid>
                    {selectedDb && dbConflict?.scheduledForDeletionBy && (
                      <Grid size={{ xs: 12 }}>
                        <Alert severity="error" variant="outlined">
                          <span dangerouslySetInnerHTML={{ __html: t('newContainer.dbScheduledForDeletion', { database: selectedDb, container: dbConflict.scheduledForDeletionBy }) }} />
                        </Alert>
                        {expirationLockedByDb && (
                          <Alert severity="info" variant="outlined" sx={{ mt: 1 }}>
                            <span dangerouslySetInnerHTML={{ __html: t('newContainer.dbExpirationSynced', { container: dbConflict.scheduledForDeletionBy }) }} />
                          </Alert>
                        )}
                      </Grid>
                    )}
                    {dbDeletionEnabled && selectedDb && expirationEnabled && (
                      <Grid size={{ xs: 12, md: 6 }}>
                        <FormControlLabel
                          control={
                            <Switch
                              checked={deleteDbOnExpiration}
                              onChange={(e) => setDeleteDbOnExpiration(e.target.checked)}
                              disabled={hasDbUsageConflict || dbConflict?.protectedFlag}
                              color="warning"
                            />
                          }
                          label={
                            <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5 }}>
                              <Warning fontSize="small" color="warning" /> {t('newContainer.deleteDbOnExpiration')}
                            </Box>
                          }
                        />
                      </Grid>
                    )}
                    {dbConflict?.protectedFlag && dbDeletionEnabled && selectedDb && expirationEnabled && (
                      <Grid size={{ xs: 12 }}>
                        <Alert severity="info" variant="outlined">
                          <span dangerouslySetInnerHTML={{ __html: t('newContainer.dbProtectedNoDeletion', { database: selectedDb }) }} />
                        </Alert>
                      </Grid>
                    )}
                    {hasDbUsageConflict && !dbConflict?.protectedFlag && dbDeletionEnabled && selectedDb && expirationEnabled && (
                      <Grid size={{ xs: 12 }}>
                        <Alert severity="error" variant="outlined">
                          <span dangerouslySetInnerHTML={{ __html: t('newContainer.cannotEnableDbDeletion', { database: selectedDb, containers: dbConflict!.inUseByContainers.join(', ') }) }} />
                        </Alert>
                      </Grid>
                    )}
                    {deleteDbOnExpiration && selectedDb && expirationEnabled && !hasDbUsageConflict && (
                      <Grid size={{ xs: 12 }}>
                        <Alert severity="warning" variant="outlined">
                          <span dangerouslySetInnerHTML={{ __html: t('newContainer.dbWillBeDeleted', { database: selectedDb }) }} />
                        </Alert>
                      </Grid>
                    )}
                    {migrationFeatureEnabled && selectedDb && (
                      <Grid size={{ xs: 12 }}>
                        <FormControlLabel
                          control={
                            <Switch
                              checked={migrationEnabled}
                              disabled={hasDbUsageConflict}
                              onChange={(e) => {
                                setMigrationEnabled(e.target.checked)
                                if (e.target.checked && !migrationConfig) {
                                  setMigrationModalOpen(true)
                                } else if (!e.target.checked) {
                                  setMigrationConfig(null)
                                }
                              }}
                            />
                          }
                          label={t('newContainer.migration')}
                        />
                        {hasDbUsageConflict && (
                          <Typography variant="caption" color="text.secondary" sx={{ display: 'block', ml: 4 }}>
                            {t('newContainer.migrationDisabledInUse', { containers: dbConflict!.inUseByContainers.join(', ') })}
                          </Typography>
                        )}
                        {migrationEnabled && migrationConfig && (
                          <Chip
                            label={formatMigrationSummary(migrationConfig, t, 'newContainer')}
                            size="small"
                            variant="outlined"
                            color="info"
                            onClick={() => setMigrationModalOpen(true)}
                            sx={{ ml: 4, mt: 0.5 }}
                          />
                        )}
                      </Grid>
                    )}
                  </>
                )}

                {dbMode === 'restore' && (
                  <>
                    <Grid size={{ xs: 12 }}>
                      {(selectedDump || selectedSnapshot) ? (
                        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5, p: 1.5, border: 1, borderColor: 'divider', borderRadius: 1 }}>
                          <Box sx={{ flex: 1 }}>
                            <Typography variant="body2" fontWeight={600}>
                              {selectedDump ? selectedDump.originalFilename : (selectedSnapshot!.label || selectedSnapshot!.sourceDatabaseName)}
                            </Typography>
                            <Box sx={{ display: 'flex', gap: 1, mt: 0.5 }}>
                              <Chip
                                label={selectedDump ? t('newContainer.dump') : t('containers.snapshot')}
                                size="small"
                                color={selectedDump ? 'default' : 'info'}
                                variant="outlined"
                              />
                              <Chip
                                label={(selectedDump ?? selectedSnapshot!).format}
                                size="small"
                                color={(selectedDump ?? selectedSnapshot!).format === 'SQL' ? 'primary' : 'secondary'}
                                variant="outlined"
                              />
                              <Typography variant="caption" color="text.secondary" sx={{ alignSelf: 'center' }}>
                                {formatBytes((selectedDump ?? selectedSnapshot!).fileSize)}
                              </Typography>
                            </Box>
                          </Box>
                          <Button size="small" variant="outlined" onClick={() => setDumpBrowserOpen(true)}>
                            {t('common.change')}
                          </Button>
                        </Box>
                      ) : (
                        <Button
                          variant="outlined"
                          startIcon={<FolderOpen />}
                          onClick={() => setDumpBrowserOpen(true)}
                        >
                          {t('newContainer.browseFiles')}
                        </Button>
                      )}
                    </Grid>
                    <Grid size={{ xs: 12, md: 6 }}>
                      <Autocomplete
                        freeSolo
                        options={databases}
                        value={restoreTargetDb}
                        onInputChange={(_e, value) => {
                          setRestoreTargetDb(value)
                          setCreateDatabase(!databases.includes(value))
                          if (dbEnvVar) {
                            const idx = envVars.findIndex((e) => e.key === dbEnvVar)
                            if (idx >= 0) {
                              updateEnvVar(idx, 'value', value)
                            }
                          }
                        }}
                        loading={dbLoading}
                        disabled={!selectedDump && !selectedSnapshot}
                        renderInput={(params) => (
                          <TextField
                            {...params}
                            label={t('newContainer.targetDatabase')}
                            placeholder={t('newContainer.targetDbPlaceholder')}
                            size="small"
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
                    </Grid>
                    <Grid size={{ xs: 12, md: 6 }}>
                      <FormControlLabel
                        control={
                          <Switch
                            checked={createDatabase}
                            onChange={(e) => setCreateDatabase(e.target.checked)}
                          />
                        }
                        label={t('newContainer.createDatabase')}
                      />
                    </Grid>
                    {restoreDbExists && (
                      <Grid size={{ xs: 12 }}>
                        <Alert severity="warning" variant="outlined" icon={<Warning />}>
                          <span dangerouslySetInnerHTML={{ __html: t('newContainer.dbAlreadyExists', { database: restoreTargetDb.trim(), usedBy: usedBySuffix }) }} />
                        </Alert>
                      </Grid>
                    )}
                    {restoreTargetDb.trim() && dbConflict?.scheduledForDeletionBy && (
                      <Grid size={{ xs: 12 }}>
                        <Alert severity="error" variant="outlined">
                          <span dangerouslySetInnerHTML={{ __html: t('newContainer.dbScheduledForDeletion', { database: restoreTargetDb.trim(), container: dbConflict.scheduledForDeletionBy }) }} />
                        </Alert>
                        {expirationLockedByDb && (
                          <Alert severity="info" variant="outlined" sx={{ mt: 1 }}>
                            <span dangerouslySetInnerHTML={{ __html: t('newContainer.dbExpirationSynced', { container: dbConflict.scheduledForDeletionBy }) }} />
                          </Alert>
                        )}
                      </Grid>
                    )}
                    {dbDeletionEnabled && restoreTargetDb.trim() && expirationEnabled && (
                      <Grid size={{ xs: 12, md: 6 }}>
                        <FormControlLabel
                          control={
                            <Switch
                              checked={deleteDbOnExpiration}
                              onChange={(e) => setDeleteDbOnExpiration(e.target.checked)}
                              disabled={hasDbUsageConflict || dbConflict?.protectedFlag}
                              color="warning"
                            />
                          }
                          label={
                            <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5 }}>
                              <Warning fontSize="small" color="warning" /> {t('newContainer.deleteDbOnExpiration')}
                            </Box>
                          }
                        />
                      </Grid>
                    )}
                    {dbConflict?.protectedFlag && dbDeletionEnabled && restoreTargetDb.trim() && expirationEnabled && (
                      <Grid size={{ xs: 12 }}>
                        <Alert severity="info" variant="outlined">
                          <span dangerouslySetInnerHTML={{ __html: t('newContainer.dbProtectedNoDeletion', { database: restoreTargetDb.trim() }) }} />
                        </Alert>
                      </Grid>
                    )}
                    {hasDbUsageConflict && !dbConflict?.protectedFlag && dbDeletionEnabled && restoreTargetDb.trim() && expirationEnabled && (
                      <Grid size={{ xs: 12 }}>
                        <Alert severity="error" variant="outlined">
                          <span dangerouslySetInnerHTML={{ __html: t('newContainer.cannotEnableDbDeletion', { database: restoreTargetDb.trim(), containers: dbConflict!.inUseByContainers.join(', ') }) }} />
                        </Alert>
                      </Grid>
                    )}
                    {deleteDbOnExpiration && restoreTargetDb.trim() && expirationEnabled && !hasDbUsageConflict && (
                      <Grid size={{ xs: 12 }}>
                        <Alert severity="warning" variant="outlined">
                          <span dangerouslySetInnerHTML={{ __html: t('newContainer.dbWillBeDeleted', { database: restoreTargetDb.trim() }) }} />
                        </Alert>
                      </Grid>
                    )}

                    {scriptsResponse?.enabled && (selectedDump || selectedSnapshot) && (scriptsResponse.mandatory.length > 0 || scriptsResponse.optional.length > 0) && (
                      <Grid size={{ xs: 12 }}>
                        <Typography variant="subtitle2" sx={{ mb: 1, color: 'text.secondary' }}>
                          {t('newContainer.postRestoreScripts')}
                        </Typography>
                        {scriptsResponse.mandatory.length > 0 && (
                          <Box sx={{ mb: 1 }}>
                            <Typography variant="caption" color="text.secondary">{t('newContainer.mandatoryScripts')}</Typography>
                            {scriptsResponse.mandatory.map((s) => (
                              <FormControlLabel
                                key={s.filename}
                                control={<Checkbox checked disabled size="small" />}
                                label={
                                  <Typography variant="body2">
                                    {s.filename}
                                    <Chip label={formatScriptSize(s.fileSize)} size="small" variant="outlined" sx={{ ml: 1 }} />
                                  </Typography>
                                }
                                sx={{ display: 'flex', ml: 0 }}
                              />
                            ))}
                          </Box>
                        )}
                        {scriptsResponse.optional.length > 0 && (
                          <Box sx={{ mb: 1 }}>
                            <Typography variant="caption" color="text.secondary">{t('newContainer.optionalScripts')}</Typography>
                            {scriptsResponse.optional.map((s) => (
                              <FormControlLabel
                                key={s.filename}
                                control={
                                  <Checkbox
                                    checked={selectedOptionalScripts.includes(s.filename)}
                                    onChange={(e) => {
                                      if (e.target.checked) {
                                        setSelectedOptionalScripts((prev) => [...prev, s.filename])
                                      } else {
                                        setSelectedOptionalScripts((prev) => prev.filter((f) => f !== s.filename))
                                      }
                                    }}
                                    size="small"
                                  />
                                }
                                label={
                                  <Typography variant="body2">
                                    {s.filename}
                                    <Chip label={formatScriptSize(s.fileSize)} size="small" variant="outlined" sx={{ ml: 1 }} />
                                  </Typography>
                                }
                                sx={{ display: 'flex', ml: 0 }}
                              />
                            ))}
                          </Box>
                        )}
                        <Typography variant="caption" color="text.secondary">
                          {scriptsResponse.onFailure === 'stop' ? t('newContainer.onFailureStop') : t('newContainer.onFailureContinue')}
                        </Typography>
                      </Grid>
                    )}

                    {migrationFeatureEnabled && (selectedDump || selectedSnapshot) && (
                      <Grid size={{ xs: 12 }}>
                        <FormControlLabel
                          control={
                            <Switch
                              checked={migrationEnabled}
                              onChange={(e) => {
                                setMigrationEnabled(e.target.checked)
                                if (e.target.checked && !migrationConfig) {
                                  setMigrationModalOpen(true)
                                } else if (!e.target.checked) {
                                  setMigrationConfig(null)
                                }
                              }}
                            />
                          }
                          label={t('newContainer.migration')}
                        />
                        {migrationEnabled && migrationConfig && (
                          <Chip
                            label={formatMigrationSummary(migrationConfig, t, 'newContainer')}
                            size="small"
                            variant="outlined"
                            color="info"
                            onClick={() => setMigrationModalOpen(true)}
                            sx={{ ml: 1 }}
                          />
                        )}
                        {!migrationEnabled && selectedDump?.version && selectedTag && compareTagsDesc(selectedDump.version, selectedTag) > 0 && (
                          <Alert severity="info" variant="outlined" sx={{ mt: 1 }}>
                            <span dangerouslySetInnerHTML={{ __html: t('newContainer.migrationVersionHint', { dumpVersion: selectedDump.version, tagVersion: selectedTag }) }} />
                          </Alert>
                        )}
                      </Grid>
                    )}
                  </>
                )}
              </Grid>
              </Box>
            )}

            {/* Expiration */}
            <Box sx={sectionSx}>
            <Grid container spacing={2} sx={{ alignItems: 'center' }}>
              <Grid size={{ xs: 12, md: 3 }}>
                <FormControlLabel
                  control={
                    <Switch
                      checked={expirationEnabled}
                      onChange={(e) => setExpirationEnabled(e.target.checked)}
                      disabled={expirationLockedByDb}
                    />
                  }
                  label={
                    <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5 }}>
                      <Timer fontSize="small" /> {t('newContainer.autoExpire')}
                    </Box>
                  }
                />
              </Grid>
              {expirationEnabled && (
                <>
                  <Grid size={{ xs: 12, md: 4 }}>
                    <Box sx={{ display: 'flex', gap: 1, flexWrap: 'wrap' }}>
                      {[
                        { label: t('newContainer.presets.2hours'), amount: 2, unit: 'hour' as const },
                        { label: t('newContainer.presets.1day'), amount: 1, unit: 'day' as const },
                        { label: t('newContainer.presets.3days'), amount: 3, unit: 'day' as const },
                        { label: t('newContainer.presets.1week'), amount: 7, unit: 'day' as const },
                      ].map((opt) => {
                        const target = dayjs().add(opt.amount, opt.unit)
                        const exceedsMax = expirationLockedByDb && target.isAfter(dayjs(dbConflict!.expiresAt))
                        return (
                          <Chip
                            key={opt.label}
                            label={opt.label}
                            onClick={exceedsMax ? undefined : () => setExpiresAt(target)}
                            color={expiresAt && expiresAt.isSame(target, 'minute') ? 'primary' : 'default'}
                            variant={expiresAt && expiresAt.isSame(target, 'minute') ? 'filled' : 'outlined'}
                            clickable={!exceedsMax}
                            disabled={exceedsMax}
                          />
                        )
                      })}
                    </Box>
                  </Grid>
                  <Grid size={{ xs: 12, md: 5 }}>
                    <MobileDateTimePicker
                      label={t('newContainer.expiresAt')}
                      value={expiresAt}
                      onChange={(v) => setExpiresAt(v)}
                      minDateTime={dayjs()}
                      maxDateTime={expirationLockedByDb ? dayjs(dbConflict!.expiresAt) : undefined}
                      slotProps={{
                        textField: {
                          fullWidth: true,
                          size: 'small',
                          helperText: expirationLockedByDb
                            ? t('newContainer.expiresLockedHelperText', { container: dbConflict!.scheduledForDeletionBy })
                            : t('newContainer.expiresHelperText'),
                        },
                      }}
                    />
                  </Grid>
                </>
              )}
            </Grid>
            </Box>

            {/* Environment Variables */}
            <Box sx={sectionSx}>
            <Typography variant="subtitle2" sx={{ mb: 1, color: 'text.secondary' }}>
              {t('newContainer.environmentVariables')}
            </Typography>
            {envVars.map((env, i) => (
              <Box key={i} sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 1 }}>
                <TextField
                  size="small"
                  placeholder="KEY"
                  value={env.key}
                  onChange={(e) => updateEnvVar(i, 'key', e.target.value)}
                  sx={{ flex: 1 }}
                />
                <Typography variant="body1">=</Typography>
                <TextField
                  size="small"
                  placeholder="VALUE"
                  value={env.value}
                  onChange={(e) => updateEnvVar(i, 'value', e.target.value)}
                  sx={{ flex: 1 }}
                />
                <IconButton size="small" color="error" onClick={() => removeEnvVar(i)}>
                  <Delete fontSize="small" />
                </IconButton>
              </Box>
            ))}
            <Button size="small" startIcon={<Add />} onClick={addEnvVar}>
              {t('newContainer.addVariable')}
            </Button>
            </Box>
          </>
        )}

        {webhookFeatureEnabled && !sse.isRunning && !sse.isDone && !sse.hasError && (
          <FormControlLabel
            control={
              <Switch
                checked={webhookNotify}
                onChange={(e) => setWebhookNotify(e.target.checked)}
              />
            }
            label={t('webhook.notifyOnCompletion')}
            sx={{ mt: 2 }}
          />
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
            onClick={async () => {
              if (runTicket) {
                setCancelling(true)
                await cancelRunContainer(runTicket)
              }
            }}
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
              color="success"
              onClick={handleRun}
              disabled={sse.isRunning || !containerNameValid}
              startIcon={<PlayArrow />}
            >
              {t('newContainer.runContainer')}
            </Button>
          </>
        )}
      </DialogActions>
    </Dialog>

      <DumpBrowserModal
        open={dumpBrowserOpen}
        dumps={allDumps}
        onClose={() => setDumpBrowserOpen(false)}
        onSelect={handleDumpSelected}
        onSelectSnapshot={handleSnapshotSelected}
      />

      <Dialog open={confirmOverrideOpen} onClose={() => setConfirmOverrideOpen(false)} maxWidth="sm" fullWidth>
        <DialogTitle sx={{ bgcolor: 'warning.main', color: 'white', display: 'flex', alignItems: 'center' }}>
          <Warning sx={{ mr: 1 }} /> {t('newContainer.confirmDbOverride')}
        </DialogTitle>
        <DialogContent dividers sx={{ pt: 3 }}>
          <Alert severity="warning" sx={{ mb: 2 }}>
            <span dangerouslySetInnerHTML={{ __html: t('newContainer.dbOverrideWarning', { database: restoreTargetDb.trim(), usedBy: usedBySuffix }) }} />
          </Alert>
          <Typography>
            <span dangerouslySetInnerHTML={{ __html: t('newContainer.dbOverrideText') }} />
          </Typography>
          <Typography variant="body2" color="text.secondary" sx={{ mt: 2 }}>
            {t('newContainer.dbOverrideHint')}
          </Typography>
        </DialogContent>
        <DialogActions sx={{ px: 3, py: 2 }}>
          <Button onClick={() => setConfirmOverrideOpen(false)} color="inherit">
            {t('common.goBack')}
          </Button>
          <Button
            variant="contained"
            color="warning"
            onClick={proceedAfterOverrideCheck}
            startIcon={<Restore />}
          >
            {t('newContainer.overrideAndContinue')}
          </Button>
        </DialogActions>
      </Dialog>

      <Dialog open={confirmDialogOpen} onClose={() => setConfirmDialogOpen(false)} maxWidth="sm" fullWidth>
        <DialogTitle sx={{ bgcolor: 'warning.main', color: 'white', display: 'flex', alignItems: 'center' }}>
          <Warning sx={{ mr: 1 }} /> {t('newContainer.confirmDbDeletion')}
        </DialogTitle>
        <DialogContent dividers sx={{ pt: 3 }}>
          <Alert severity="warning" sx={{ mb: 3 }}>
            <Box component="span" sx={{ '& strong': { userSelect: 'all', cursor: 'pointer' } }} dangerouslySetInnerHTML={{ __html: t('newContainer.dbDeletionWarning', { database: activeDbName }) }} />
          </Alert>
          <Typography variant="body2" sx={{ mb: 2 }}>
            <Box component="span" sx={{ '& strong': { userSelect: 'all', cursor: 'pointer' } }} dangerouslySetInnerHTML={{ __html: t('newContainer.dbDeletionConfirmText', { database: activeDbName }) }} />
          </Typography>
          <TextField
            fullWidth
            size="small"
            placeholder={activeDbName ?? ''}
            value={confirmNameInput}
            onChange={(e) => setConfirmNameInput(e.target.value)}
            autoFocus
          />
        </DialogContent>
        <DialogActions sx={{ px: 3, py: 2 }}>
          <Button onClick={() => setConfirmDialogOpen(false)} color="inherit">{t('common.cancel')}</Button>
          <Button
            variant="contained"
            color="warning"
            disabled={confirmNameInput !== activeDbName}
            onClick={handleConfirmRun}
            startIcon={<PlayArrow />}
          >
            {t('newContainer.confirmAndRun')}
          </Button>
        </DialogActions>
      </Dialog>

      <MigrationConfigModal
        open={migrationModalOpen}
        config={migrationConfig}
        apiModeAvailable={migrationApiAvail}
        suggestedSourceVersion={selectedDump?.version || undefined}
        suggestedTargetVersion={selectedTag || undefined}

        onSave={(cfg) => {
          setMigrationConfig(cfg)
          setMigrationEnabled(true)
          setMigrationModalOpen(false)
        }}
        onClose={() => {
          setMigrationModalOpen(false)
          if (!migrationConfig) setMigrationEnabled(false)
        }}
      />

      <MigrationPreviewModal
        open={migrationPreview.previewOpen}
        preview={migrationPreview.previewData}
        loading={migrationPreview.previewLoading}
        error={migrationPreview.previewError}
        onApprove={() => {
          migrationPreview.closePreview()
          executeRun()
        }}
        onDecline={migrationPreview.closePreview}
      />

      <PasswordConfirmDialog
        open={operationsPasswordOpen}
        title={t('newContainer.confirmRunTitle')}
        message={t('newContainer.confirmRunPasswordMessage')}
        confirmLabel={t('newContainer.runContainer')}
        loadingLabel={t('common.preparing')}
        confirmColor="primary"
        icon={<PlayArrow />}
        onConfirm={async (password) => {
          const valid = await validateOperationsPassword(password)
          if (!valid) {
            throw new Error(t('common.invalidOperationsPassword'))
          }
          operationsPasswordRef.current = password
          setOperationsPassword(password)
          setOperationsPasswordOpen(false)
          await continueAfterPassword()
        }}
        onClose={() => setOperationsPasswordOpen(false)}
      />
    </>
  )
}
