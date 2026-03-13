import { useState, useEffect, useRef } from 'react'
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
  Chip,
  ToggleButtonGroup,
  ToggleButton,
} from '@mui/material'
import { MobileDateTimePicker } from '@mui/x-date-pickers/MobileDateTimePicker'
import dayjs, { type Dayjs } from 'dayjs'
import { Add, Close, Delete, Memory, PlayArrow, Timer, Warning, FolderOpen } from '@mui/icons-material'
import { Alert } from '@mui/material'
import {
  getAllowedRepositories,
  getDatabaseConflicts,
  getDefaultExpirationMinutes,
  getRepositoryDatabases,
  getRepositoryEnvKeys,
  getRepositoryTags,
  isDeletionOnExpirationEnabled,
  isMemoryLimitEnabled,
  repositoryHasDatabases,
} from '../services/containerService'
import { isDumpEnabled, listDumps } from '../services/dumpService'
import type { DatabaseConflict, DatabaseDump } from '../types'
import { buildTargetDbName, formatBytes } from '../utils/format'
import { prepareRunContainer, streamRunContainer, type ContainerEvent } from '../services/sseService'
import { useNotification } from './NotificationProvider'
import OperationProgress, { RUN_WITH_RESTORE_STEPS } from './OperationProgress'
import DumpBrowserModal from './DumpBrowserModal'

interface Props {
  open: boolean
  onClose: () => void
  onCreated: () => void
}

interface EnvVar {
  key: string
  value: string
}

function compareTagsDesc(a: string, b: string): number {
  const partsA = a.split(/[.\-]/)
  const partsB = b.split(/[.\-]/)
  const len = Math.max(partsA.length, partsB.length)
  for (let i = 0; i < len; i++) {
    const na = Number(partsA[i] ?? '')
    const nb = Number(partsB[i] ?? '')
    if (!isNaN(na) && !isNaN(nb)) {
      if (nb !== na) return nb - na
    } else {
      const cmp = (partsA[i] ?? '').localeCompare(partsB[i] ?? '')
      if (cmp !== 0) return cmp
    }
  }
  return 0
}

export default function NewContainerModal({ open, onClose, onCreated }: Props) {
  const { notify, confirm } = useNotification()
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
  const [dbMode, setDbMode] = useState<'existing' | 'restore'>('existing')
  const [dumpBrowserOpen, setDumpBrowserOpen] = useState(false)
  const [restoreTargetDb, setRestoreTargetDb] = useState('')
  const [createDatabase, setCreateDatabase] = useState(false)
  const [confirmDialogOpen, setConfirmDialogOpen] = useState(false)
  const [confirmNameInput, setConfirmNameInput] = useState('')
  const activeDbName = dbMode === 'restore' ? restoreTargetDb.trim() || null : selectedDb
  const hasDbUsageConflict = (dbConflict?.inUseByContainers?.length ?? 0) > 0
  const expirationLockedByDb = !!(dbConflict?.scheduledForDeletionBy && dbConflict?.expiresAt)
  const [defaultExpMinutes, setDefaultExpMinutes] = useState(480)
  const [expirationEnabled, setExpirationEnabled] = useState(true)
  const [expiresAt, setExpiresAt] = useState<Dayjs | null>(dayjs().add(480, 'minute'))
  const [running, setRunning] = useState(false)
  const [sseEvents, setSseEvents] = useState<ContainerEvent[]>([])
  const [sseError, setSseError] = useState(false)
  const cleanupSse = useRef<(() => void) | null>(null)

  useEffect(() => {
    getAllowedRepositories()
      .then((repos) => {
        setRepositories(repos)
        if (repos.length > 0) setSelectedRepo(repos[0])
      })
      .catch(() => {})
    getDefaultExpirationMinutes()
      .then((m) => {
        setDefaultExpMinutes(m)
        setExpiresAt(dayjs().add(m, 'minute'))
      })
      .catch(() => {})
    isMemoryLimitEnabled()
      .then(setMemoryEnabled)
      .catch(() => {})
    isDeletionOnExpirationEnabled()
      .then(setDbDeletionEnabled)
      .catch(() => {})
    isDumpEnabled()
      .then((enabled) => {
        setDumpFeatureEnabled(enabled)
        if (enabled) {
          listDumps().then(setAllDumps).catch(() => setAllDumps([]))
        }
      })
      .catch(() => setDumpFeatureEnabled(false))
  }, [])

  useEffect(() => {
    if (!selectedRepo) {
      setAllTags([])
      setSelectedTag(null)
      setEnvVars([])
      setDbEnabled(false)
      setDatabases([])
      setSelectedDb(null)
      setDbEnvVar(null)
      return
    }
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
    if (mode === 'existing') {
      setSelectedDump(null)
      setRestoreTargetDb('')
      setCreateDatabase(false)
    } else {
      setSelectedDb(null)
    }
  }

  function handleDumpSelected(dump: DatabaseDump) {
    setSelectedDump(dump)
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

  function resetForm() {
    setSelectedRepo('')
    setSelectedTag(null)
    setAllTags([])
    setContainerName('')
    setEnvVars([])
    setMemoryMb('')
    setExpirationEnabled(true)
    setExpiresAt(dayjs().add(defaultExpMinutes, 'minute'))
    setSseEvents([])
    setSseError(false)
    setDbEnabled(false)
    setDatabases([])
    setSelectedDb(null)
    setDbEnvVar(null)
    setDeleteDbOnExpiration(false)
    setDbConflict(null)
    setSelectedDump(null)
    setDbMode('existing')
    setDumpBrowserOpen(false)
    setRestoreTargetDb('')
    setCreateDatabase(false)
    setConfirmDialogOpen(false)
    setConfirmNameInput('')
  }

  async function handleRun() {
    if (!selectedRepo) return notify('Please select a repository.', 'warning')
    if (!selectedTag) return notify('Please select a tag.', 'warning')

    if (dbMode === 'restore') {
      if (!selectedDump) return notify('Please select a dump to restore.', 'warning')
      if (!restoreTargetDb.trim()) return notify('Please enter a target database name.', 'warning')
    }

    if (deleteDbOnExpiration && activeDbName && expirationEnabled) {
      setConfirmNameInput('')
      setConfirmDialogOpen(true)
      return
    }

    const name = containerName ? ` as "${containerName}"` : ''
    const accepted = await confirm(`Run container from ${selectedRepo}:${selectedTag}${name}?`)
    if (!accepted) return

    executeRun()
  }

  async function executeRun() {
    setRunning(true)
    setSseEvents([])
    setSseError(false)

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
        dumpId: dbMode === 'restore' ? (selectedDump?.id || null) : null,
        createDatabase: dbMode === 'restore' ? createDatabase : false,
      })

      cleanupSse.current = streamRunContainer(
        ticket,
        (event) => setSseEvents((prev) => [...prev, event]),
        () => {
          setTimeout(() => {
            setRunning(false)
            resetForm()
            onClose()
            onCreated()
            notify('Container started successfully.', 'success')
          }, 1500)
        },
        () => {
          setRunning(false)
          setSseError(true)
        },
      )
    } catch {
      setRunning(false)
      notify('An unexpected error occurred.', 'error')
    }
  }

  function handleConfirmRun() {
    setConfirmDialogOpen(false)
    executeRun()
  }

  function handleClose() {
    if (cleanupSse.current) {
      cleanupSse.current()
      cleanupSse.current = null
    }
    setRunning(false)
    resetForm()
    onClose()
  }

  return (
    <>
    <Dialog
      open={open}
      onClose={(_event, reason) => {
        if (running && (reason === 'escapeKeyDown' || reason === 'backdropClick')) return
        handleClose()
      }}
      maxWidth="md"
      fullWidth
    >
      <DialogTitle sx={{ bgcolor: 'primary.main', color: 'white', display: 'flex', alignItems: 'center' }}>
        <Add sx={{ mr: 1 }} /> New Container
        <IconButton onClick={handleClose} sx={{ ml: 'auto', color: 'white' }}>
          <Close />
        </IconButton>
      </DialogTitle>
      <DialogContent dividers sx={{ pt: 3 }}>
        {running || sseEvents.length > 0 ? (
          <OperationProgress events={sseEvents} steps={dbMode === 'restore' && selectedDump ? RUN_WITH_RESTORE_STEPS : undefined} />
        ) : (
          <>
            {/* Repository + Tag */}
            <Grid container spacing={2} sx={{ mb: 3 }}>
              <Grid size={{ xs: 12, md: 4 }}>
                <TextField
                  select
                  fullWidth
                  label="Repository"
                  value={selectedRepo}
                  onChange={(e) => setSelectedRepo(e.target.value)}
                  size="small"
                >
                  <MenuItem value="">Select a repository...</MenuItem>
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
                  noOptionsText={!selectedRepo ? 'Select a repository first...' : 'No tags found'}
                  slotProps={{ listbox: { style: { maxHeight: 7 * 36 } } }}
                  renderInput={(params) => (
                    <TextField
                      {...params}
                      label="Tag"
                      placeholder="Type to filter tags..."
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
            </Grid>

            {/* Container name + Memory */}
            <Grid container spacing={2} sx={{ mb: 3 }}>
              <Grid size={{ xs: 12, md: 6 }}>
                <TextField
                  fullWidth
                  label="Container Name"
                  placeholder="e.g. my-app-container (optional)"
                  value={containerName}
                  onChange={(e) => setContainerName(e.target.value)}
                  size="small"
                  error={!containerNameValid}
                  helperText={!containerNameValid ? 'Only letters, digits, underscores, periods, and hyphens; must start with a letter or digit' : ''}
                />
              </Grid>
              {memoryEnabled && (
                <Grid size={{ xs: 12, md: 6 }}>
                  <TextField
                    fullWidth
                    label="Memory Limit (MB)"
                    placeholder="e.g. 512 (optional)"
                    value={memoryMb}
                    onChange={(e) => setMemoryMb(e.target.value.replace(/\D/g, ''))}
                    size="small"
                    type="text"
                    helperText="Leave empty for no limit"
                    slotProps={{
                      input: {
                        startAdornment: <Memory fontSize="small" sx={{ mr: 1, color: 'text.secondary' }} />,
                      },
                    }}
                  />
                </Grid>
              )}
            </Grid>

            {/* Database */}
            {dbEnabled && (
              <Grid container spacing={2} sx={{ mb: 3 }}>
                {dumpFeatureEnabled && (
                  <Grid size={{ xs: 12 }}>
                    <ToggleButtonGroup
                      value={dbMode}
                      exclusive
                      onChange={(_e, value) => { if (value) handleDbModeChange(value) }}
                      size="small"
                    >
                      <ToggleButton value="existing">Existing Database</ToggleButton>
                      <ToggleButton value="restore">Restore from Dump</ToggleButton>
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
                        noOptionsText={dbLoading ? 'Loading databases...' : 'No databases found'}
                        slotProps={{ listbox: { style: { maxHeight: 7 * 36 } } }}
                        renderInput={(params) => (
                          <TextField
                            {...params}
                            label="Database"
                            placeholder="Select a database..."
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
                          Database <strong>{selectedDb}</strong> is scheduled for deletion by
                          container <strong>{dbConflict.scheduledForDeletionBy}</strong>.
                          It will be dropped when that container expires.
                        </Alert>
                        {expirationLockedByDb && (
                          <Alert severity="info" variant="outlined" sx={{ mt: 1 }}>
                            Expiration time is synced with container <strong>{dbConflict.scheduledForDeletionBy}</strong>.
                            This container will be removed together when the database is dropped.
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
                              disabled={hasDbUsageConflict}
                              color="warning"
                            />
                          }
                          label={
                            <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5 }}>
                              <Warning fontSize="small" color="warning" /> Delete database on expiration
                            </Box>
                          }
                        />
                      </Grid>
                    )}
                    {hasDbUsageConflict && dbDeletionEnabled && selectedDb && expirationEnabled && (
                      <Grid size={{ xs: 12 }}>
                        <Alert severity="error" variant="outlined">
                          Cannot enable database deletion. Database <strong>{selectedDb}</strong> is
                          in use by: <strong>{dbConflict!.inUseByContainers.join(', ')}</strong>.
                        </Alert>
                      </Grid>
                    )}
                    {deleteDbOnExpiration && selectedDb && expirationEnabled && !hasDbUsageConflict && (
                      <Grid size={{ xs: 12 }}>
                        <Alert severity="warning" variant="outlined">
                          The database <strong>{selectedDb}</strong> will be permanently deleted when this container expires.
                          This action cannot be undone.
                        </Alert>
                      </Grid>
                    )}
                  </>
                )}

                {dbMode === 'restore' && (
                  <>
                    <Grid size={{ xs: 12 }}>
                      {selectedDump ? (
                        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5, p: 1.5, border: 1, borderColor: 'divider', borderRadius: 1 }}>
                          <Box sx={{ flex: 1 }}>
                            <Typography variant="body2" fontWeight={600}>{selectedDump.originalFilename}</Typography>
                            <Box sx={{ display: 'flex', gap: 1, mt: 0.5 }}>
                              <Chip
                                label={selectedDump.format}
                                size="small"
                                color={selectedDump.format === 'SQL' ? 'primary' : selectedDump.format === 'CUSTOM' ? 'secondary' : 'default'}
                                variant="outlined"
                              />
                              <Typography variant="caption" color="text.secondary" sx={{ alignSelf: 'center' }}>
                                {formatBytes(selectedDump.fileSize)}
                              </Typography>
                            </Box>
                          </Box>
                          <Button size="small" variant="outlined" onClick={() => setDumpBrowserOpen(true)}>
                            Change
                          </Button>
                        </Box>
                      ) : (
                        <Button
                          variant="outlined"
                          startIcon={<FolderOpen />}
                          onClick={() => setDumpBrowserOpen(true)}
                        >
                          Browse Dumps
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
                        disabled={!selectedDump}
                        renderInput={(params) => (
                          <TextField
                            {...params}
                            label="Target Database"
                            placeholder="Select or type a new database name"
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
                        label="Create database if it doesn't exist"
                      />
                    </Grid>
                    {restoreTargetDb.trim() && dbConflict?.scheduledForDeletionBy && (
                      <Grid size={{ xs: 12 }}>
                        <Alert severity="error" variant="outlined">
                          Database <strong>{restoreTargetDb.trim()}</strong> is scheduled for deletion by
                          container <strong>{dbConflict.scheduledForDeletionBy}</strong>.
                          It will be dropped when that container expires.
                        </Alert>
                        {expirationLockedByDb && (
                          <Alert severity="info" variant="outlined" sx={{ mt: 1 }}>
                            Expiration time is synced with container <strong>{dbConflict.scheduledForDeletionBy}</strong>.
                            This container will be removed together when the database is dropped.
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
                              disabled={hasDbUsageConflict}
                              color="warning"
                            />
                          }
                          label={
                            <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5 }}>
                              <Warning fontSize="small" color="warning" /> Delete database on expiration
                            </Box>
                          }
                        />
                      </Grid>
                    )}
                    {hasDbUsageConflict && dbDeletionEnabled && restoreTargetDb.trim() && expirationEnabled && (
                      <Grid size={{ xs: 12 }}>
                        <Alert severity="error" variant="outlined">
                          Cannot enable database deletion. Database <strong>{restoreTargetDb.trim()}</strong> is
                          in use by: <strong>{dbConflict!.inUseByContainers.join(', ')}</strong>.
                        </Alert>
                      </Grid>
                    )}
                    {deleteDbOnExpiration && restoreTargetDb.trim() && expirationEnabled && !hasDbUsageConflict && (
                      <Grid size={{ xs: 12 }}>
                        <Alert severity="warning" variant="outlined">
                          The database <strong>{restoreTargetDb.trim()}</strong> will be permanently deleted when this container expires.
                          This action cannot be undone.
                        </Alert>
                      </Grid>
                    )}
                  </>
                )}
              </Grid>
            )}

            {/* Expiration */}
            <Grid container spacing={2} sx={{ mb: 3, alignItems: 'center' }}>
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
                      <Timer fontSize="small" /> Auto-expire
                    </Box>
                  }
                />
              </Grid>
              {expirationEnabled && (
                <>
                  <Grid size={{ xs: 12, md: 4 }}>
                    <Box sx={{ display: 'flex', gap: 1, flexWrap: 'wrap' }}>
                      {[
                        { label: '2 Hours', amount: 2, unit: 'hour' as const },
                        { label: '1 Day', amount: 1, unit: 'day' as const },
                        { label: '3 Days', amount: 3, unit: 'day' as const },
                        { label: '1 Week', amount: 7, unit: 'day' as const },
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
                      label="Expires at"
                      value={expiresAt}
                      onChange={(v) => setExpiresAt(v)}
                      minDateTime={dayjs()}
                      maxDateTime={expirationLockedByDb ? dayjs(dbConflict!.expiresAt) : undefined}
                      slotProps={{
                        textField: {
                          fullWidth: true,
                          size: 'small',
                          helperText: expirationLockedByDb
                            ? `Cannot exceed expiration of container "${dbConflict!.scheduledForDeletionBy}"`
                            : 'Container will be stopped and removed at this time',
                        },
                      }}
                    />
                  </Grid>
                </>
              )}
            </Grid>

            {/* Environment Variables */}
            <Typography variant="subtitle2" sx={{ mb: 1, color: 'text.secondary' }}>
              Environment Variables
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
              Add Variable
            </Button>
          </>
        )}
      </DialogContent>
      <DialogActions sx={{ px: 3, py: 2 }}>
        {sseError ? (
          <>
            <Button onClick={handleClose} color="inherit">Close</Button>
            <Button
              variant="contained"
              color="primary"
              onClick={() => {
                setSseEvents([])
                setSseError(false)
              }}
            >
              Back to Form
            </Button>
          </>
        ) : running ? (
          <Button onClick={handleClose} color="inherit">Cancel</Button>
        ) : (
          <>
            <Button onClick={handleClose} color="inherit">Cancel</Button>
            <Button
              variant="contained"
              color="success"
              onClick={handleRun}
              disabled={running || !containerNameValid}
              startIcon={<PlayArrow />}
            >
              Run Container
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
      />

      <Dialog open={confirmDialogOpen} onClose={() => setConfirmDialogOpen(false)} maxWidth="sm" fullWidth>
        <DialogTitle sx={{ bgcolor: 'warning.main', color: 'white', display: 'flex', alignItems: 'center' }}>
          <Warning sx={{ mr: 1 }} /> Confirm Database Deletion
        </DialogTitle>
        <DialogContent dividers sx={{ pt: 3 }}>
          <Alert severity="warning" sx={{ mb: 3 }}>
            The database <strong>{activeDbName}</strong> will be permanently deleted when this container expires.
            This action cannot be undone.
          </Alert>
          <Typography variant="body2" sx={{ mb: 2 }}>
            To confirm, type the database name <strong>{activeDbName}</strong> below:
          </Typography>
          <TextField
            fullWidth
            size="small"
            placeholder={activeDbName ?? ''}
            value={confirmNameInput}
            onChange={(e) => setConfirmNameInput(e.target.value)}
            onPaste={(e) => e.preventDefault()}
            autoFocus
          />
        </DialogContent>
        <DialogActions sx={{ px: 3, py: 2 }}>
          <Button onClick={() => setConfirmDialogOpen(false)} color="inherit">Cancel</Button>
          <Button
            variant="contained"
            color="warning"
            disabled={confirmNameInput !== activeDbName}
            onClick={handleConfirmRun}
            startIcon={<PlayArrow />}
          >
            Confirm & Run
          </Button>
        </DialogActions>
      </Dialog>
    </>
  )
}
