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
} from '@mui/material'
import { Close, SwapHoriz } from '@mui/icons-material'
import { useTranslation } from 'react-i18next'
import { isMigrationApiAvailable } from '../services/containerService'
import { formatMigrationSummary } from '../utils/format'
import { prepareRunMigration, streamRunMigration, cancelRunMigration } from '../services/sseService'
import { useNotification } from './NotificationProvider'
import { useAuth } from './AuthProvider'
import { useMigrationPreview } from '../hooks/useMigrationPreview'
import OperationProgress, { MIGRATION_STEPS } from './OperationProgress'
import MigrationConfigModal, { type MigrationConfig } from './MigrationConfigModal'
import MigrationPreviewModal from './MigrationPreviewModal'

interface Props {
  open: boolean
  repository: string
  databaseName: string
  onClose: () => void
  onCompleted: () => void
}

export default function RunMigrationModal({ open, repository, databaseName, onClose, onCompleted }: Props) {
  const { t } = useTranslation()
  const { notify } = useNotification()
  const { rbacEnabled } = useAuth()
  const sse = useSseOperation()

  const [password, setPassword] = useState('')
  const [migrationApiAvail, setMigrationApiAvail] = useState(false)
  const [migrationConfig, setMigrationConfig] = useState<MigrationConfig | null>(null)
  const [configModalOpen, setConfigModalOpen] = useState(false)
  const [ticket, setTicket] = useState<string | null>(null)
  const [cancelling, setCancelling] = useState(false)
  const migrationPreview = useMigrationPreview()

  useEffect(() => {
    if (open && repository) {
      isMigrationApiAvailable(repository).then(setMigrationApiAvail).catch(() => setMigrationApiAvail(false))
    }
  }, [open, repository])

  function resetForm() {
    setPassword('')
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
    if (!rbacEnabled && !password) return notify(t('runMigration.enterPasswordWarning'), 'warning')
    if (!migrationConfig) return notify(t('runMigration.configureMigrationWarning'), 'warning')

    const shown = await migrationPreview.showPreview(migrationConfig, repository)
    if (shown) return

    executeMigration()
  }

  async function executeMigration() {
    if (!migrationConfig) return

    try {
      const migrationTicket = await prepareRunMigration({
        repository,
        targetDatabase: databaseName,
        password,
        migrationMode: migrationConfig.mode,
        migrationSql: migrationConfig.mode === 'MANUAL' ? migrationConfig.sql : null,
        migrationSourceVersion: migrationConfig.sourceVersion,
        migrationTargetVersion: migrationConfig.targetVersion,
      })

      setTicket(migrationTicket)

      sse.start(
        (onEvent, onDone, onError) => streamRunMigration(migrationTicket, onEvent, onDone, onError),
        () => {
          setTimeout(() => {
            onClose()
            onCompleted()
            notify(t('runMigration.completed'), 'success')
            setTimeout(resetForm, 300)
          }, 1500)
        },
      )
    } catch (e) {
      notify(e instanceof Error ? e.message : 'Unexpected error.', 'error')
    }
  }

  const configSummary = migrationConfig
    ? formatMigrationSummary(migrationConfig, t, 'runMigration')
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
          <SwapHoriz sx={{ mr: 1 }} /> {t('runMigration.title', { database: databaseName })}
          <IconButton onClick={handleClose} sx={{ ml: 'auto', color: 'white' }}>
            <Close />
          </IconButton>
        </DialogTitle>
        <DialogContent dividers sx={{ pt: 3 }}>
          {sse.isRunning || sse.events.length > 0 ? (
            <OperationProgress events={sse.events} steps={MIGRATION_STEPS} />
          ) : (
            <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2.5 }}>
              <Typography variant="body2" color="text.secondary">
                {t('runMigration.description', { database: databaseName, repository })}
              </Typography>

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

              <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5 }}>
                <Button
                  variant="outlined"
                  startIcon={<SwapHoriz />}
                  onClick={() => setConfigModalOpen(true)}
                >
                  {migrationConfig ? t('runMigration.editConfig') : t('runMigration.configureButton')}
                </Button>
                {configSummary && (
                  <Typography variant="body2" color="info.main">{configSummary}</Typography>
                )}
              </Box>
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
                  await cancelRunMigration(ticket)
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
                disabled={(!rbacEnabled && !password) || !migrationConfig}
                startIcon={<SwapHoriz />}
              >
                {t('runMigration.execute')}
              </Button>
            </>
          )}
        </DialogActions>
      </Dialog>

      <MigrationConfigModal
        open={configModalOpen}
        config={migrationConfig}
        apiModeAvailable={migrationApiAvail}
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
          executeMigration()
        }}
        onDecline={migrationPreview.closePreview}
      />
    </>
  )
}
