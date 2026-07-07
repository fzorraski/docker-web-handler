import { useState, useEffect } from 'react'
import { useSseOperation } from '../hooks/useSseOperation'
import {
  Dialog,
  DialogTitle,
  DialogContent,
  DialogActions,
  Button,
  TextField,
  IconButton,
  CircularProgress,
  Typography,
  Box,
  Autocomplete,
  Alert,
  Chip,
  FormControlLabel,
  Switch,
} from '@mui/material'
import { Close, SystemUpdateAlt, SwapHoriz, ArrowForward } from '@mui/icons-material'
import { useTranslation } from 'react-i18next'
import { getRepositoryTags, isMigrationApiAvailable } from '../services/containerService'
import { formatMigrationSummary, compareTagsDesc } from '../utils/format'
import { prepareUpgradeContainer, streamUpgradeContainer, cancelUpgradeContainer } from '../services/sseService'
import { useNotification } from './NotificationProvider'
import { useAuth } from './AuthProvider'
import { useMigrationPreview } from '../hooks/useMigrationPreview'
import OperationProgress, { UPGRADE_STEPS, UPGRADE_WITH_MIGRATION_STEPS, MIGRATION_STEPS } from './OperationProgress'
import MigrationConfigModal, { type MigrationConfig } from './MigrationConfigModal'
import MigrationPreviewModal from './MigrationPreviewModal'

interface Props {
  open: boolean
  containerId: string
  containerName: string
  currentTag: string
  repository: string
  databaseName?: string
  upgradeEnabled: boolean
  migrationFeatureEnabled: boolean
  onClose: () => void
  onCompleted: () => void
}

export default function UpgradeContainerModal({
  open, containerId, containerName, currentTag, repository, databaseName,
  upgradeEnabled, migrationFeatureEnabled, onClose, onCompleted,
}: Props) {
  const { t } = useTranslation()
  const { notify } = useNotification()
  const { rbacEnabled } = useAuth()
  const sse = useSseOperation()

  const [tags, setTags] = useState<string[]>([])
  const [tagsLoading, setTagsLoading] = useState(false)
  const [newTag, setNewTag] = useState<string | null>(null)
  const [password, setPassword] = useState('')
  const [migrationEnabled, setMigrationEnabled] = useState(false)
  const [migrationApiAvail, setMigrationApiAvail] = useState(false)
  const [migrationConfig, setMigrationConfig] = useState<MigrationConfig | null>(null)
  const [configModalOpen, setConfigModalOpen] = useState(false)
  const [ticket, setTicket] = useState<string | null>(null)
  const [cancelling, setCancelling] = useState(false)
  const migrationPreview = useMigrationPreview()

  // Keep migration target version in sync with selected tag
  useEffect(() => {
    if (migrationConfig && newTag) {
      setMigrationConfig((prev) => prev ? { ...prev, targetVersion: newTag } : prev)
    }
  }, [newTag])

  const hasTagChange = !!newTag
  const isSameTag = !!newTag && newTag === currentTag
  const isMigrationOnly = !hasTagChange && migrationEnabled && !!migrationConfig
  const canExecute = (hasTagChange || (migrationEnabled && !!migrationConfig)) && (rbacEnabled || !!password)

  useEffect(() => {
    if (!open || !repository) return
    if (upgradeEnabled) {
      setTagsLoading(true)
      getRepositoryTags(repository)
        .then((resp) => {
          const available = [...(resp.tags ?? [])].sort(compareTagsDesc)
          setTags(available)
        })
        .catch(() => setTags([]))
        .finally(() => setTagsLoading(false))
    }
    if (migrationFeatureEnabled) {
      isMigrationApiAvailable(repository).then(setMigrationApiAvail).catch(() => setMigrationApiAvail(false))
    }
  }, [open, repository, currentTag, upgradeEnabled, migrationFeatureEnabled])

  function resetForm() {
    setNewTag(null)
    setPassword('')
    setMigrationEnabled(false)
    setMigrationConfig(null)
    setConfigModalOpen(false)
    sse.reset()
    setTicket(null)
    setCancelling(false)
    migrationPreview.reset()
  }

  function handleClose() {
    sse.cleanup()
    onClose()
    setTimeout(resetForm, 300)
  }

  async function handleRun() {
    if (!rbacEnabled && !password) return notify(t('upgradeContainer.passwordRequired'), 'warning')
    if (!hasTagChange && !migrationEnabled) return notify(t('upgradeContainer.selectTagOrMigration'), 'warning')
    if (migrationEnabled && !migrationConfig) return notify(t('upgradeContainer.configureMigration'), 'warning')

    if (migrationEnabled && migrationConfig) {
      const shown = await migrationPreview.showPreview(migrationConfig, repository)
      if (shown) return
    }

    executeUpgrade()
  }

  async function executeUpgrade() {
    try {
      const upgradeTicket = await prepareUpgradeContainer({
        containerId,
        newTag: hasTagChange ? newTag : null,
        password,
        migrationMode: migrationEnabled && migrationConfig ? migrationConfig.mode : null,
        migrationSql: migrationEnabled && migrationConfig?.mode === 'MANUAL' ? migrationConfig.sql : null,
        migrationSourceVersion: migrationEnabled && migrationConfig ? migrationConfig.sourceVersion : null,
        migrationTargetVersion: migrationEnabled && migrationConfig ? migrationConfig.targetVersion : null,
      })

      setTicket(upgradeTicket)

      sse.start(
        (onEvent, onDone, onError) => streamUpgradeContainer(upgradeTicket, onEvent, onDone, onError),
        () => {
          setTimeout(() => {
            onClose()
            onCompleted()
            notify(t('upgradeContainer.completed'), 'success')
            setTimeout(resetForm, 300)
          }, 1500)
        },
      )
    } catch (e) {
      notify(e instanceof Error ? e.message : 'Unexpected error.', 'error')
    }
  }

  const steps = isMigrationOnly ? MIGRATION_STEPS
    : migrationEnabled ? UPGRADE_WITH_MIGRATION_STEPS
    : UPGRADE_STEPS

  const configSummary = migrationConfig
    ? formatMigrationSummary(migrationConfig, t, 'upgradeContainer')
    : null

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
          <SystemUpdateAlt sx={{ mr: 1 }} /> {t('upgradeContainer.title', { name: containerName })}
          <IconButton onClick={handleClose} sx={{ ml: 'auto', color: 'white' }}>
            <Close />
          </IconButton>
        </DialogTitle>
        <DialogContent dividers sx={{ pt: 3 }}>
          {sse.isRunning || sse.events.length > 0 ? (
            <OperationProgress events={sse.events} steps={steps} />
          ) : (
            <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2.5 }}>
              {/* Version selector */}
              {upgradeEnabled && <Box sx={{ display: 'flex', alignItems: 'center', gap: 2 }}>
                <TextField
                  label={t('upgradeContainer.currentTag')}
                  value={currentTag}
                  size="small"
                  slotProps={{ input: { readOnly: true } }}
                  sx={{ flex: 1, '& .MuiInputBase-input': { fontFamily: "'JetBrains Mono', monospace" } }}
                />
                <ArrowForward sx={{ color: 'text.secondary' }} />
                <Autocomplete
                  options={tags}
                  value={newTag}
                  onChange={(_, v) => setNewTag(v)}
                  loading={tagsLoading}
                  size="small"
                  sx={{ flex: 1 }}
                  renderInput={(params) => (
                    <TextField
                      {...params}
                      label={t('upgradeContainer.newTag')}
                      slotProps={{
                        input: {
                          ...params.InputProps,
                          sx: { fontFamily: "'JetBrains Mono', monospace" },
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
              </Box>}

              {isSameTag && (
                <Alert severity="info" variant="outlined">
                  {t('upgradeContainer.sameTagInfo')}
                </Alert>
              )}

              {/* Migration toggle */}
              {migrationFeatureEnabled && databaseName && (
                <Box>
                  <FormControlLabel
                    control={
                      <Switch
                        checked={migrationEnabled}
                        onChange={(e) => {
                          setMigrationEnabled(e.target.checked)
                          if (e.target.checked && !migrationConfig) {
                            setConfigModalOpen(true)
                          } else if (!e.target.checked) {
                            setMigrationConfig(null)
                          }
                        }}
                      />
                    }
                    label={t('upgradeContainer.runMigration')}
                  />
                  {migrationEnabled && (
                    <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, ml: 4, mt: 0.5 }}>
                      <Button
                        variant="outlined"
                        size="small"
                        startIcon={<SwapHoriz />}
                        onClick={() => setConfigModalOpen(true)}
                      >
                        {migrationConfig ? t('runMigration.editConfig') : t('runMigration.configureButton')}
                      </Button>
                      {configSummary && (
                        <Chip label={configSummary} size="small" variant="outlined" color="info" />
                      )}
                    </Box>
                  )}
                </Box>
              )}

              {/* Warning */}
              {hasTagChange && (
                <Alert severity="info" variant="outlined">
                  {t('upgradeContainer.warning')}
                </Alert>
              )}

              {/* Password */}
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
            </Box>
          )}
        </DialogContent>
        <DialogActions sx={{ px: 3, py: 2 }}>
          {sse.hasError ? (
            <>
              <Button onClick={handleClose} color="inherit">{t('common.close')}</Button>
              <Button variant="contained" color="primary" onClick={() => { setCancelling(false); sse.reset() }}>
                {t('common.backToForm')}
              </Button>
            </>
          ) : sse.isRunning ? (
            <Button
              onClick={async () => {
                if (ticket) {
                  setCancelling(true)
                  await cancelUpgradeContainer(ticket)
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
                disabled={!canExecute}
                startIcon={<SystemUpdateAlt />}
              >
                {hasTagChange ? t('upgradeContainer.upgrade') : t('runMigration.execute')}
              </Button>
            </>
          )}
        </DialogActions>
      </Dialog>

      <MigrationConfigModal
        open={configModalOpen}
        config={migrationConfig}
        apiModeAvailable={migrationApiAvail}
        suggestedSourceVersion={currentTag}
        suggestedTargetVersion={newTag || undefined}
        onSave={(cfg) => {
          setMigrationConfig(cfg)
          setConfigModalOpen(false)
        }}
        onClose={() => setConfigModalOpen(false)}
      />

      <MigrationPreviewModal
        open={migrationPreview.previewOpen}
        preview={migrationPreview.previewData}
        loading={migrationPreview.previewLoading}
        error={migrationPreview.previewError}
        onApprove={() => {
          migrationPreview.closePreview()
          executeUpgrade()
        }}
        onDecline={migrationPreview.closePreview}
      />
    </>
  )
}
