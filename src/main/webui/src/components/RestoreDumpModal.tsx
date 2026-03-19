import { useState, useEffect } from 'react'
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
  FormControlLabel,
  Switch,
  Checkbox,
  CircularProgress,
  Typography,
  Box,
  Chip,
  Alert,
} from '@mui/material'
import { Close, Restore, Warning } from '@mui/icons-material'
import { useTranslation } from 'react-i18next'
import type { DatabaseDump, DatabaseSnapshot } from '../types'
import { buildTargetDbName, buildSnapshotTargetDbName, formatScriptSize, formatMigrationSummary } from '../utils/format'
import { getDumpRepositories, cancelRestore, getPostRestoreScripts, type PostRestoreScriptsResponse } from '../services/dumpService'
import { getDatabaseConflicts, getRepositoryDatabases, isMigrationEnabled, isMigrationApiAvailable } from '../services/containerService'
import { prepareRestoreDump, streamRestoreDump } from '../services/sseService'
import { useNotification } from './NotificationProvider'
import { useMigrationPreview } from '../hooks/useMigrationPreview'
import OperationProgress, { RESTORE_STEPS, RESTORE_WITH_SCRIPTS_STEPS, RESTORE_WITH_MIGRATION_STEPS, RESTORE_WITH_SCRIPTS_AND_MIGRATION_STEPS } from './OperationProgress'
import MigrationConfigModal, { type MigrationConfig } from './MigrationConfigModal'
import MigrationPreviewModal from './MigrationPreviewModal'

interface Props {
  open: boolean
  dump: DatabaseDump | null
  snapshot?: DatabaseSnapshot | null
  onClose: () => void
  onRestored: () => void
}

export default function RestoreDumpModal({ open, dump, snapshot, onClose, onRestored }: Props) {
  const source = snapshot ?? dump
  const isSnapshot = !!snapshot
  const { notify } = useNotification()
  const { t } = useTranslation()
  const [repositories, setRepositories] = useState<string[]>([])
  const [selectedRepo, setSelectedRepo] = useState('')
  const [databases, setDatabases] = useState<string[]>([])
  const [dbLoading, setDbLoading] = useState(false)
  const [targetDb, setTargetDb] = useState<string>('')
  const [createDb, setCreateDb] = useState(false)
  const [password, setPassword] = useState('')
  const [cancelling, setCancelling] = useState(false)
  const sse = useSseOperation()
  const [scriptsResponse, setScriptsResponse] = useState<PostRestoreScriptsResponse | null>(null)
  const [selectedOptionalScripts, setSelectedOptionalScripts] = useState<string[]>([])
  const [confirmOverrideOpen, setConfirmOverrideOpen] = useState(false)
  const [inUseBy, setInUseBy] = useState<string[]>([])
  const [migrationFeatureEnabled, setMigrationFeatureEnabled] = useState(false)
  const [migrationApiAvail, setMigrationApiAvail] = useState(false)
  const [migrationEnabled, setMigrationEnabled] = useState(false)
  const [migrationConfig, setMigrationConfig] = useState<MigrationConfig | null>(null)
  const [migrationModalOpen, setMigrationModalOpen] = useState(false)
  const migrationPreview = useMigrationPreview()

  const dbExists = !!(targetDb.trim() && databases.includes(targetDb.trim()))

  useEffect(() => {
    if (open) {
      getDumpRepositories().then(setRepositories).catch(() => setRepositories([]))
      isMigrationEnabled().then(setMigrationFeatureEnabled).catch(() => setMigrationFeatureEnabled(false))
    }
  }, [open])

  useEffect(() => {
    if (!selectedRepo) {
      setDatabases([])
      setScriptsResponse(null)
      setSelectedOptionalScripts([])
      setMigrationApiAvail(false)
      return
    }
    if (migrationFeatureEnabled) {
      isMigrationApiAvailable(selectedRepo).then(setMigrationApiAvail).catch(() => setMigrationApiAvail(false))
    }
    setDbLoading(true)
    getRepositoryDatabases(selectedRepo)
      .then((res) => {
        if (res.state === 1 && res.databases) setDatabases(res.databases)
        else setDatabases([])
      })
      .catch(() => setDatabases([]))
      .finally(() => setDbLoading(false))
    getPostRestoreScripts(selectedRepo)
      .then((res) => {
        setScriptsResponse(res)
        if (res.enabled) {
          setSelectedOptionalScripts(res.optional.map((s) => s.filename))
        }
      })
      .catch(() => setScriptsResponse(null))
  }, [selectedRepo, migrationFeatureEnabled])

  useEffect(() => {
    if (!open) return
    if (snapshot) {
      setTargetDb(buildSnapshotTargetDbName(snapshot))
      setSelectedRepo(snapshot.repository)
    } else if (dump) {
      setTargetDb(buildTargetDbName(dump))
    }
  }, [open, dump, snapshot])

  useEffect(() => {
    const name = targetDb.trim()
    if (name && databases.includes(name)) {
      getDatabaseConflicts(name)
        .then((conflict) => setInUseBy(conflict.inUseByContainers ?? []))
        .catch(() => setInUseBy([]))
    } else {
      setInUseBy([])
    }
  }, [targetDb, databases])

  function resetForm() {
    setSelectedRepo('')
    setDatabases([])
    setTargetDb('')
    setCreateDb(false)
    setPassword('')
    sse.reset()
    setCancelling(false)
    setScriptsResponse(null)
    setSelectedOptionalScripts([])
    setConfirmOverrideOpen(false)
    setInUseBy([])
    setMigrationEnabled(false)
    setMigrationConfig(null)
    setMigrationModalOpen(false)
    migrationPreview.reset()
  }

  function handleClose() {
    sse.cleanup()
    onClose()
    setTimeout(resetForm, 300)
  }

  async function handleCancel() {
    if (!selectedRepo || !targetDb.trim()) return
    setCancelling(true)
    await cancelRestore(selectedRepo, targetDb.trim())
  }

  function handleRestoreClick() {
    if (!source) return
    if (!selectedRepo) return notify(t('restoreDump.selectRepoWarning'), 'warning')
    if (!targetDb.trim()) return notify(t('restoreDump.enterTargetDbWarning'), 'warning')
    if (!password) return notify(t('restoreDump.enterPasswordWarning'), 'warning')

    if (dbExists) {
      setConfirmOverrideOpen(true)
      return
    }

    proceedToRestore()
  }

  async function proceedToRestore() {
    setConfirmOverrideOpen(false)

    // If migration validation is enabled, show preview first
    if (migrationEnabled && migrationConfig) {
      const shown = await migrationPreview.showPreview(migrationConfig, selectedRepo)
      if (shown) return
    }

    executeRestore()
  }

  async function executeRestore() {

    try {
      const isNew = !databases.includes(targetDb.trim())

      const ticket = await prepareRestoreDump({
        dumpId: isSnapshot ? undefined : (source as DatabaseDump).id,
        snapshotId: isSnapshot ? (source as DatabaseSnapshot).id : undefined,
        repository: selectedRepo,
        targetDatabase: targetDb.trim(),
        createDatabase: createDb || isNew,
        password,
        selectedOptionalScripts: scriptsResponse?.enabled ? selectedOptionalScripts : undefined,
        migrationMode: migrationEnabled && migrationConfig ? migrationConfig.mode : null,
        migrationSql: migrationEnabled && migrationConfig?.mode === 'MANUAL' ? migrationConfig.sql : null,
        migrationSourceVersion: migrationEnabled && migrationConfig ? migrationConfig.sourceVersion : null,
        migrationTargetVersion: migrationEnabled && migrationConfig ? migrationConfig.targetVersion : null,
      })

      sse.start(
        (onEvent, onDone, onError) => streamRestoreDump(ticket, onEvent, onDone, onError),
        () => {
          setTimeout(() => {
            onClose()
            onRestored()
            notify(isSnapshot ? t('restoreDump.snapshotRestored') : t('restoreDump.dumpRestored'), 'success')
            setTimeout(resetForm, 300)
          }, 1500)
        },
      )
    } catch (e) {
      notify(e instanceof Error ? e.message : t('common.unexpectedError'), 'error')
    }
  }

  const usedBySuffix = inUseBy.length > 0
    ? t('restoreDump.dbUsedBySuffix', { containers: inUseBy.join(', ') })
    : ''

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
        <Restore sx={{ mr: 1 }} /> {isSnapshot
          ? t('restoreDump.restoreSnapshot', { name: snapshot!.label || snapshot!.sourceDatabaseName })
          : dump ? t('restoreDump.restoreDumpNamed', { filename: dump.originalFilename }) : t('restoreDump.restoreDump')}
        <IconButton onClick={handleClose} sx={{ ml: 'auto', color: 'white' }}>
          <Close />
        </IconButton>
      </DialogTitle>
      <DialogContent dividers sx={{ pt: 3 }}>
        {sse.isRunning || sse.events.length > 0 ? (
          <OperationProgress events={sse.events} steps={
            scriptsResponse?.enabled
              ? (migrationEnabled && migrationConfig ? RESTORE_WITH_SCRIPTS_AND_MIGRATION_STEPS : RESTORE_WITH_SCRIPTS_STEPS)
              : (migrationEnabled && migrationConfig ? RESTORE_WITH_MIGRATION_STEPS : RESTORE_STEPS)
          } />
        ) : (
          <>
            <TextField
              fullWidth
              type="password"
              label={t('common.operationsPassword')}
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              size="small"
              sx={{ mb: 3 }}
              autoComplete="off"
            />

            <Grid container spacing={2} sx={{ mb: 3 }}>
              <Grid size={{ xs: 12, md: 6 }}>
                <TextField
                  select
                  fullWidth
                  label={t('restoreDump.repository')}
                  value={selectedRepo}
                  onChange={(e) => setSelectedRepo(e.target.value)}
                  size="small"
                >
                  <MenuItem value="">{t('restoreDump.selectRepository')}</MenuItem>
                  {repositories.map((r) => (
                    <MenuItem key={r} value={r}>{r}</MenuItem>
                  ))}
                </TextField>
              </Grid>
              <Grid size={{ xs: 12, md: 6 }}>
                <Autocomplete
                  freeSolo
                  options={databases}
                  value={targetDb}
                  onInputChange={(_e, value) => {
                    setTargetDb(value)
                    if (value && !databases.includes(value)) {
                      setCreateDb(true)
                    }
                  }}
                  loading={dbLoading}
                  disabled={!selectedRepo}
                  renderInput={(params) => (
                    <TextField
                      {...params}
                      label={t('restoreDump.targetDatabase')}
                      placeholder={t('restoreDump.targetDbPlaceholder')}
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
            </Grid>

            {dbExists && (
              <Alert severity="warning" variant="outlined" icon={<Warning />} sx={{ mb: 2 }}>
                <span dangerouslySetInnerHTML={{ __html: t('restoreDump.dbAlreadyExists', { database: targetDb.trim(), usedBy: usedBySuffix }) }} />
              </Alert>
            )}

            <FormControlLabel
              control={
                <Switch
                  checked={createDb}
                  onChange={(e) => setCreateDb(e.target.checked)}
                />
              }
              label={t('restoreDump.createDatabase')}
            />

            {scriptsResponse?.enabled && (scriptsResponse.mandatory.length > 0 || scriptsResponse.optional.length > 0) && (
              <Box sx={{ mt: 3 }}>
                <Typography variant="subtitle2" sx={{ mb: 1, color: 'text.secondary' }}>
                  {t('restoreDump.postRestoreScripts')}
                </Typography>

                {scriptsResponse.mandatory.length > 0 && (
                  <Box sx={{ mb: 1 }}>
                    <Typography variant="caption" color="text.secondary">{t('restoreDump.mandatoryScripts')}</Typography>
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
                    <Typography variant="caption" color="text.secondary">{t('restoreDump.optionalScripts')}</Typography>
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
                  {scriptsResponse.onFailure === 'stop' ? t('restoreDump.onFailureStop') : t('restoreDump.onFailureContinue')}
                </Typography>
              </Box>
            )}

            {migrationFeatureEnabled && (
              <Box sx={{ mt: 3 }}>
                <FormControlLabel
                  control={
                    <Switch
                      checked={migrationEnabled}
                      onChange={(e) => {
                        setMigrationEnabled(e.target.checked)
                        if (e.target.checked && !migrationConfig) {
                          setMigrationModalOpen(true)
                        }
                      }}
                    />
                  }
                  label={t('restoreDump.migration')}
                />
                {migrationEnabled && migrationConfig && (
                  <Chip
                    label={formatMigrationSummary(migrationConfig, t, 'restoreDump')}
                    size="small"
                    variant="outlined"
                    color="info"
                    onClick={() => setMigrationModalOpen(true)}
                    sx={{ ml: 1 }}
                  />
                )}
              </Box>
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
              onClick={() => sse.reset()}
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
            {cancelling ? t('common.cancelling') : t('restoreDump.cancelRestore')}
          </Button>
        ) : (
          <>
            <Button onClick={handleClose} color="inherit">{t('common.cancel')}</Button>
            <Button
              variant="contained"
              color="success"
              onClick={handleRestoreClick}
              disabled={sse.isRunning || !selectedRepo || !targetDb.trim() || !password}
              startIcon={<Restore />}
            >
              {t('common.restore')}
            </Button>
          </>
        )}
      </DialogActions>

      <Dialog open={confirmOverrideOpen} onClose={() => setConfirmOverrideOpen(false)} maxWidth="sm" fullWidth>
        <DialogTitle sx={{ bgcolor: 'warning.main', color: 'white', display: 'flex', alignItems: 'center' }}>
          <Warning sx={{ mr: 1 }} /> {t('restoreDump.confirmDbOverride')}
        </DialogTitle>
        <DialogContent dividers sx={{ pt: 3 }}>
          <Alert severity="warning" sx={{ mb: 2 }}>
            <span dangerouslySetInnerHTML={{ __html: t('restoreDump.dbOverrideWarning', { database: targetDb.trim(), usedBy: usedBySuffix }) }} />
          </Alert>
          <Typography>
            <span dangerouslySetInnerHTML={{ __html: t('restoreDump.dbOverrideText') }} />
          </Typography>
          <Typography variant="body2" color="text.secondary" sx={{ mt: 2 }}>
            {t('restoreDump.dbOverrideHint')}
          </Typography>
        </DialogContent>
        <DialogActions sx={{ px: 3, py: 2 }}>
          <Button onClick={() => setConfirmOverrideOpen(false)} color="inherit">
            {t('common.goBack')}
          </Button>
          <Button
            variant="contained"
            color="warning"
            onClick={proceedToRestore}
            startIcon={<Restore />}
          >
            {t('restoreDump.overrideAndRestore')}
          </Button>
        </DialogActions>
      </Dialog>

      <MigrationConfigModal
        open={migrationModalOpen}
        config={migrationConfig}
        apiModeAvailable={migrationApiAvail}
        suggestedSourceVersion={dump?.version || undefined}

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
          executeRestore()
        }}
        onDecline={migrationPreview.closePreview}
      />
    </Dialog>
  )
}
